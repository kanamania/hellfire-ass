package com.hellfire.ass.media;

import java.nio.file.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** Real pinned whisper.cpp through the production JNI bridge, on a host JVM. */
public class WhisperNative {
    private native long load(String path);
    private native byte[] transcribe(long handle, float[] samples, String language);
    private native void abort(long handle);
    private native void reset(long handle);
    private native void release(long handle);

    public static void main(String[] args) throws Exception {
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        byte[] wav = Files.readAllBytes(Path.of(args[2]));
        ByteBuffer data = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (data.getInt() != 0x46464952 || data.getInt(8) != 0x45564157) throw new AssertionError("Expected RIFF WAV");
        int offset = 12, count = -1;
        boolean format = false;
        while (offset + 8 <= wav.length) {
            int tag = data.getInt(offset), size = data.getInt(offset + 4);
            if (size < 0 || (long)offset + 8 + size > wav.length) throw new AssertionError("Truncated WAV");
            if (tag == 0x20746d66) {
                format = size >= 16 && data.getShort(offset + 8) == 1 && data.getShort(offset + 10) == 1
                    && data.getInt(offset + 12) == 16000 && data.getShort(offset + 22) == 16;
            }
            if (tag == 0x61746164) { count = size / 2; offset += 8; break; }
            offset += 8 + size + (size & 1);
        }
        if (!format || count <= 0) throw new AssertionError("Expected mono PCM16 16 kHz");
        float[] samples = new float[count];
        data.position(offset);
        for (int i = 0; i < count; i++) samples[i] = data.getShort() / 32768f;
        WhisperNative api = new WhisperNative();
        long start = System.nanoTime();
        long handle = api.load(args[1]);
        long loaded = System.nanoTime();
        try {
            String result = new String(api.transcribe(handle, samples, "en"), StandardCharsets.UTF_8);
            String normalized = result.toLowerCase().replaceAll("[^a-z ]", " ").replaceAll(" +", " ");
            if (!normalized.contains("ask not what your country can do for you")
                || !normalized.contains("ask what you can do for your country")) throw new AssertionError(result);
            System.out.println(result);
            System.out.printf("HOST_REAL_WHISPER load_ms=%.3f infer_ms=%.3f audio_ms=%.3f%n",
                (loaded - start)/1e6, (System.nanoTime() - loaded)/1e6, count/16.0);
            api.abort(handle);
            try { api.transcribe(handle, samples, "en"); throw new AssertionError("Abort ignored"); }
            catch (IllegalStateException expected) { }
            api.reset(handle);
        } finally { api.release(handle); }
    }
}
