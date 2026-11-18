#!/usr/bin/env python3
"""Real host ONNX evaluation; synthetic layout fixtures are not classifier-accuracy evidence."""
import argparse
import hashlib
import json
from pathlib import Path
import wave
import numpy as np
import onnx
from onnx import TensorProto, helper
import onnxruntime as ort

ort.disable_telemetry_events()


def session(path):
    options = ort.SessionOptions()
    options.intra_op_num_threads = options.inter_op_num_threads = 1
    return ort.InferenceSession(str(path), sess_options=options, providers=['CPUExecutionProvider'])


def generate_layouts(folder):
    folder.mkdir(parents=True, exist_ok=True)
    results = []
    for first in (False, True):
        layout = 'nchw' if first else 'nhwc'
        shape = [1, 3, 2, 4] if first else [1, 2, 4, 3]
        axes = [2, 3] if first else [1, 2]
        graph = helper.make_graph([helper.make_node('ReduceMean', ['input'], ['output'], axes=axes, keepdims=0)],
            'synthetic-channel-mean', [helper.make_tensor_value_info('input', TensorProto.FLOAT, shape)],
            [helper.make_tensor_value_info('output', TensorProto.FLOAT, [1, 3])])
        model = helper.make_model(graph, opset_imports=[helper.make_opsetid('', 17)], producer_name='HellFire synthetic test generator')
        model.ir_version = 9
        onnx.checker.check_model(model)
        path = folder / f'color-{layout}.onnx'
        onnx.save(model, path)
        pixels = np.zeros(shape, np.float32)
        if first: pixels[:, 0, :, :] = 1
        else: pixels[:, :, :, 0] = 1
        actual = session(path).run(None, {'input': pixels})[0]
        np.testing.assert_array_equal(actual, [[1, 0, 0]])
        results.append({'model': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'output': actual.tolist()})
    return results


def evaluate_vad(model, speech_wav):
    runtime = session(model)
    def score(samples):
        state = np.zeros((2, 1, 128), np.float32)
        context = np.zeros((1, 64), np.float32)
        probabilities = []
        for start in range(0, len(samples) - 511, 512):
            frame = samples[start:start+512].reshape(1, 512)
            output, state = runtime.run(None, {'input': np.concatenate([context, frame], axis=1),
                'state': state, 'sr': np.array(16000, np.int64)})
            value = float(output[0, 0])
            assert np.isfinite(state).all() and 0 <= value <= 1
            probabilities.append(value)
            context = frame[:, -64:]
        return probabilities
    silence = score(np.zeros(16000 * 3, np.float32))
    assert max(silence) < .5, f'Silence classified as speech: {max(silence)}'
    with wave.open(str(speech_wav)) as source:
        assert source.getnchannels() == 1 and source.getsampwidth() == 2 and source.getframerate() == 16000
        speech = np.frombuffer(source.readframes(source.getnframes()), dtype='<i2').astype(np.float32) / 32768
    voiced = score(speech)
    assert sum(value >= .5 for value in voiced) >= len(voiced) * .25
    repeated = score(speech)
    np.testing.assert_allclose(voiced, repeated, atol=1e-7, rtol=0)
    return dict(model_sha256=hashlib.sha256(model.read_bytes()).hexdigest(), silence_frames=len(silence),
        silence_max=max(silence), speech_frames=len(voiced), speech_frames_above_threshold=sum(v >= .5 for v in voiced),
        recurrent_reset_reproducible=True, limits='Public English sample and digital silence; not microphone, Kiswahili, speaker identity or battery validation.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--models', type=Path, required=True)
    parser.add_argument('--sample', type=Path, required=True)
    parser.add_argument('--fixtures', type=Path, default=Path('android/app/src/androidTest/assets/onnx'))
    parser.add_argument('--report', type=Path, default=Path('android/build/reports/predevice/onnx-host.json'))
    args = parser.parse_args()
    report = dict(evidence='host-tested', runtime=ort.__version__, providers=['CPUExecutionProvider'],
        synthetic_layouts=generate_layouts(args.fixtures), real_vad=evaluate_vad(args.models / 'silero_vad.onnx', args.sample))
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
