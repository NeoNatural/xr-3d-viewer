package com.limelight;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Collections;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.gpu.GpuDelegateFactory;

/** ADB-only CPU reference benchmark. Never participates in the OpenXR frame loop. */
public final class DepthV2BenchmarkActivity extends Activity {
    private static final String TAG = "XR3D-V2-BENCH";
    private static final String MODEL = "depth_anything_v2_vits_dynamic.onnx";
    private static final String TFLITE_MODEL = "depth_anything_v2_qualcomm_518.tflite";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView result = new TextView(this);
        result.setText("Depth Anything V2 benchmark running; see logcat " + TAG);
        setContentView(result);
        new Thread(() -> benchmark(result), "Depth V2 benchmark").start();
    }

    private void benchmark(TextView result) {
        String backend = getIntent().getStringExtra("backend");
        if ("litert_gpu".equals(backend) || "litert_cpu".equals(backend)
                || "litert_gpu_batch4".equals(backend)) {
            benchmarkLiteRt(result, backend);
            return;
        }
        File file = new File(getExternalFilesDir(null), MODEL);
        if (!file.isFile()) {
            report(result, "Model missing: " + file);
            return;
        }
        try {
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            if (backend == null) backend = "cpu";
            if (backend.equals("nnapi")) {
                options.addNnapi();
            } else if (backend.equals("xnnpack")) {
                options.addXnnpack(Collections.emptyMap());
            } else if (!backend.equals("cpu")) {
                report(result, "Unknown backend: " + backend);
                options.close();
                return;
            }
            options.setIntraOpNumThreads(4);
            Log.i(TAG, "backend=" + backend);
            long start = System.nanoTime();
            try (OrtSession session = env.createSession(file.getAbsolutePath(), options)) {
                Log.i(TAG, "model_load_ms=" + ms(start) + " bytes=" + file.length());
                for (int size : new int[]{252, 392, 518}) {
                    float[] input = new float[3 * size * size];
                    long[] shape = {1, 3, size, size};
                    try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape)) {
                        for (int run = 0; run < 3; run++) {
                            start = System.nanoTime();
                            try (OrtSession.Result ignored = session.run(
                                    Collections.singletonMap("image", tensor))) {
                                Log.i(TAG, "size=" + size + " run=" + run
                                        + " inference_ms=" + ms(start));
                            }
                        }
                    }
                }
            }
            options.close();
            report(result, "Done. See logcat " + TAG);
        } catch (Exception e) {
            Log.e(TAG, "Benchmark failed", e);
            report(result, "Failed: " + e.getMessage());
        }
    }

    private void benchmarkLiteRt(TextView result, String backend) {
        File file = new File(getExternalFilesDir(null), TFLITE_MODEL);
        if (!file.isFile()) {
            report(result, "Model missing: " + file);
            return;
        }
        GpuDelegate delegate = null;
        try (FileInputStream stream = new FileInputStream(file)) {
            MappedByteBuffer model = stream.getChannel().map(
                    FileChannel.MapMode.READ_ONLY, 0, file.length());
            Interpreter.Options options = new Interpreter.Options();
            if (backend.startsWith("litert_gpu")) {
                GpuDelegateFactory.Options gpu = new GpuDelegateFactory.Options();
                gpu.setPrecisionLossAllowed(true);
                gpu.setInferencePreference(
                        GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED);
                delegate = new GpuDelegate(gpu);
                options.addDelegate(delegate);
            } else {
                options.setNumThreads(4);
            }
            Log.i(TAG, "backend=" + backend);
            long start = System.nanoTime();
            try (Interpreter interpreter = new Interpreter(model, options)) {
                int batch = backend.endsWith("batch4") ? 4 : 1;
                if (batch == 4) {
                    interpreter.resizeInput(0, new int[]{4, 518, 518, 3}, false);
                    interpreter.allocateTensors();
                }
                Log.i(TAG, "model_load_ms=" + ms(start) + " bytes=" + file.length()
                        + " input=" + java.util.Arrays.toString(interpreter.getInputTensor(0).shape())
                        + " output=" + java.util.Arrays.toString(interpreter.getOutputTensor(0).shape()));
                ByteBuffer input = ByteBuffer.allocateDirect(batch * 518 * 518 * 3 * Float.BYTES)
                        .order(ByteOrder.nativeOrder());
                ByteBuffer output = ByteBuffer.allocateDirect(batch * 518 * 518 * Float.BYTES)
                        .order(ByteOrder.nativeOrder());
                File sample = new File(getExternalFilesDir(null), "v2_benchmark.jpg");
                if (sample.isFile()) {
                    Bitmap image = BitmapFactory.decodeFile(sample.getAbsolutePath());
                    if (image == null) throw new IllegalStateException("Cannot decode " + sample);
                    Bitmap resized = Bitmap.createScaledBitmap(image, 518, 518, true);
                    int[] pixels = new int[518 * 518];
                    resized.getPixels(pixels, 0, 518, 0, 0, 518, 518);
                    input.clear();
                    for (int copy = 0; copy < batch; copy++) for (int pixel : pixels) {
                        input.putFloat(((pixel >> 16) & 255) / 255f);
                        input.putFloat(((pixel >> 8) & 255) / 255f);
                        input.putFloat((pixel & 255) / 255f);
                    }
                    resized.recycle();
                    if (resized != image) image.recycle();
                    Log.i(TAG, "sample=" + sample.getName());
                } else {
                    Log.i(TAG, "sample=zero_tensor");
                }
                int runs = Math.max(1, Math.min(120, getIntent().getIntExtra("runs", 5)));
                for (int run = 0; run < runs; run++) {
                    input.rewind();
                    output.rewind();
                    start = System.nanoTime();
                    interpreter.run(input, output);
                    Log.i(TAG, "size=518 run=" + run + " inference_ms=" + ms(start));
                    if (run == 0) {
                        float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
                        output.rewind();
                        FloatBuffer values = output.asFloatBuffer();
                        while (values.hasRemaining()) {
                            float value = values.get();
                            low = Math.min(low, value);
                            high = Math.max(high, value);
                        }
                        Log.i(TAG, "depth_min=" + low + " depth_max=" + high);
                        if (backend.equals("litert_gpu") && sample.isFile()) {
                            File depthFile = new File(getExternalFilesDir(null), "v2_litert_depth_518.f32");
                            output.rewind();
                            try (FileOutputStream depthStream = new FileOutputStream(depthFile)) {
                                depthStream.getChannel().write(output);
                            }
                            Log.i(TAG, "depth_dump=" + depthFile);
                        }
                    }
                }
            }
            report(result, "Done. See logcat " + TAG);
        } catch (Exception e) {
            Log.e(TAG, "Benchmark failed", e);
            report(result, "Failed: " + e.getMessage());
        } finally {
            if (delegate != null) delegate.close();
        }
    }

    private static long ms(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private void report(TextView result, String message) {
        Log.i(TAG, message);
        runOnUiThread(() -> result.setText(message));
    }
}
