#!/usr/bin/env python3
"""Verify APK ABI isolation and 16 KiB ELF/ZIP alignment without executing native code."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile


def inspect(path, abi):
    machines = {'arm64-v8a': 183, 'x86_64': 62}
    libraries = []
    with zipfile.ZipFile(path) as archive, path.open('rb') as raw:
        for entry in archive.infolist():
            if not entry.filename.startswith('lib/') or not entry.filename.endswith('.so'):
                continue
            parts = entry.filename.split('/')
            if len(parts) != 3 or parts[1] != abi:
                raise ValueError(f'Unexpected native ABI: {entry.filename}')
            data = archive.read(entry)
            if data[:6] != b'\x7fELF\x02\x01':
                raise ValueError(f'Expected ELF64 little-endian: {entry.filename}')
            machine = struct.unpack_from('<H', data, 18)[0]
            if machine != machines[abi]:
                raise ValueError(f'Wrong ELF machine: {entry.filename}: {machine}')
            offset = struct.unpack_from('<Q', data, 32)[0]
            stride, count = struct.unpack_from('<HH', data, 54)
            if stride < 56 or offset + stride * count > len(data):
                raise ValueError(f'Invalid program headers: {entry.filename}')
            loads = []
            for index in range(count):
                kind, _, at, virtual, _, _, _, alignment = struct.unpack_from('<IIQQQQQQ', data, offset + stride * index)
                if kind == 1:
                    if alignment < 16384 or alignment & (alignment - 1) or at % 16384 != virtual % 16384:
                        raise ValueError(f'ELF load segment is not 16 KiB compatible: {entry.filename}')
                    loads.append(alignment)
            if not loads:
                raise ValueError(f'No load segments: {entry.filename}')
            raw.seek(entry.header_offset)
            header = raw.read(30)
            name_size, extra_size = struct.unpack_from('<HH', header, 26)
            data_offset = entry.header_offset + 30 + name_size + extra_size
            if entry.compress_type == zipfile.ZIP_STORED and data_offset % 16384:
                raise ValueError(f'Uncompressed JNI library is not ZIP aligned: {entry.filename}')
            libraries.append(dict(name=entry.filename, bytes=len(data), elf_load_alignments=loads,
                zip_data_offset=data_offset, compressed=entry.compress_type != zipfile.ZIP_STORED,
                sha256=hashlib.sha256(data).hexdigest()))
    names = {Path(item['name']).name for item in libraries}
    for required in ['libhellfire_whisper.so', 'libsqlcipher.so', 'libonnxruntime.so', 'liblitertlm_jni.so']:
        if required not in names:
            raise ValueError(f'Missing real native runtime: {required}')
    if abi == 'x86_64' and 'libmediapipe_tasks_text_jni.so' in names:
        raise ValueError('Pinned MediaPipe capability assumption changed; review emulator status')
    with path.open('rb') as source:
        digest = hashlib.file_digest(source, 'sha256').hexdigest()
    return dict(evidence='host-artifact-inspection', apk=str(path), bytes=path.stat().st_size,
        sha256=digest, abi=abi, libraries=libraries,
        limitation='Packaging checks do not establish Android execution, delegate support or physical-device compatibility.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--abi', required=True, choices=['arm64-v8a', 'x86_64'])
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    report = inspect(args.apk, args.abi)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(f"PASS: {args.abi}, {len(report['libraries'])} real JNI libraries, ELF and APK 16 KiB alignment; SHA-256 {report['sha256']}")
