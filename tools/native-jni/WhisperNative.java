package com.hellfire.ass.media;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** Uses the production exported JNI functions with a synthetic whisper implementation. */
public class WhisperNative {
    private native long load(String path);
    private native byte[] transcribe(long handle, float[] samples, String language);
    private native void abort(long handle);
    private native void reset(long handle);
    private native void release(long handle);
    private native boolean entered();
    private native void clearEntered();

    private static void fails(Runnable block) {
        try { block.run(); throw new AssertionError("Expected a Java exception"); }
        catch (IllegalStateException expected) { /* Production JNI contract. */ }
    }

    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        WhisperNative api = new WhisperNative();
        fails(() -> api.load(null));
        fails(() -> api.load("invalid"));
        fails(() -> api.transcribe(0, new float[0], "auto"));
        api.release(0);
        api.abort(0);
        api.reset(0);
        long handle = api.load("synthetic");
        try {
            fails(() -> api.transcribe(handle, null, "en"));
            fails(() -> api.transcribe(handle, new float[0], "en"));
            fails(() -> api.transcribe(handle, new float[16000 * 300 + 1], "en"));
            fails(() -> api.transcribe(handle, new float[2], null));
            float[] samples = {0.25f, -0.25f};
            String json = new String(api.transcribe(handle, samples, "auto"), StandardCharsets.UTF_8);
            String expected = "{\"language\":\"en\",\"segments\":[{\"start_ms\":70,\"end_ms\":420,\"text\":\"Habari \\\"dunia\\\"\\u000a\\u0009\\\\ café\"}]}";
            if (!json.equals(expected)) throw new AssertionError(json);
            if (samples[0] != 0.25f || samples[1] != -0.25f) throw new AssertionError("Input mutated");
            api.abort(handle);
            fails(() -> api.transcribe(handle, samples, "en"));
            api.reset(handle);
            api.clearEntered();
            AtomicReference<Throwable> result = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { api.transcribe(handle, samples, "sw"); result.set(new AssertionError("Abort ignored")); }
                catch (IllegalStateException expectedFailure) { result.set(expectedFailure); }
                catch (Throwable failure) { result.set(failure); }
            });
            worker.start();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!api.entered() && System.nanoTime() < deadline) Thread.sleep(1);
            if (!api.entered()) throw new AssertionError("Inference did not start");
            api.abort(handle);
            worker.join(5000);
            if (worker.isAlive() || !(result.get() instanceof IllegalStateException)) throw new AssertionError("Cancellation failed", result.get());
            api.reset(handle);
            api.transcribe(handle, samples, "en"); // Context remains reusable after cancellation.
            System.out.println("PASS: real JNI bridge, synthetic whisper: bounds/nulls, JSON/timestamps/UTF-8, input ownership, abort/reset/reuse");
        } finally { api.release(handle); }
    }
}
