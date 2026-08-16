package com.chua.deeplearning.support.onnx.resolution;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * TextBSR 文字超分辨率（ORT 原生 + OpenCV）。
 *
 * <p>基于 RRDBNet（scale=4）的文字图像盲超分模型，提升模糊文字清晰度，OCR 预处理。
 * 模型 {@code vision/text_restore/textbsr/textbsr.onnx} 由 jar
 * {@code utils-support-models-onnx-textbsr} 提供。输入 {@code input [1,3,H,W]}
 * （OpenCV resize 高 512、归一化 (v/255-0.5)/0.5），输出 {@code output [1,3,H*4,W*4]}
 * 高清图。替代 DJL 版（djl-onnx 不支持 NDArray 张量运算）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class TextBsrTranslator implements ITranslator<byte[], BufferedImage> {

    private static final int DETECT_RESOLUTION = 512;

    /**
     * resize 后的最大宽度限制（512×2048 输入约需 ~300MB，超宽时整体缩放防内存爆炸）。
     */
    private static final int MAX_RESIZED_WIDTH = 2048;

    private static final float[] MEAN = {0.5f, 0.5f, 0.5f};
    private static final float[] STD = {0.5f, 0.5f, 0.5f};

    private static final String RESOURCE_BASE = "vision/text_restore/textbsr/";
    private static final String MODEL_FILE = "textbsr.onnx";

    private OrtEnvironment ortEnv;
    private OrtSession session;

    private synchronized void prepare() throws Exception {
        if (session != null) {
            return;
        }
        Path tmpDir = Files.createTempDirectory("textbsr-");
        tmpDir.toFile().deleteOnExit();
        Path modelDir = tmpDir.resolve("textbsr");
        Files.createDirectories(modelDir);
        NativeLoader.of("textbsr")
                .from(TextBsrTranslator.class.getClassLoader())
                .basePath(RESOURCE_BASE)
                .toTarget(modelDir)
                .glob("*.onnx")
                .withMd5(true)
                .extractOnly(true)
                .load();
        Path modelPath = modelDir.resolve(MODEL_FILE);
        if (!Files.isRegularFile(modelPath)) {
            throw new IllegalArgumentException("TextBSR 模型缺失: " + modelPath);
        }
        this.ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        this.session = ortEnv.createSession(modelPath.toString(), opts);
        log.info("[TextBSR] ONNX loaded: {}", modelPath.getFileName());
    }

    @Override
    public String name() {
        return "text-bsr";
    }

    @Override
    public BufferedImage translate(byte[] imageData) {
        try {
            prepare();
            return enhance(imageData);
        } catch (Exception e) {
            throw new RuntimeException("[text-bsr] 文字超分辨率失败: " + e.getMessage(), e);
        }
    }

    private BufferedImage enhance(byte[] imageData) {
        try {
            nu.pattern.OpenCV.loadLocally();
            Mat src = Imgcodecs.imdecode(new MatOfByte(imageData), Imgcodecs.IMREAD_COLOR);
            if (src == null || src.empty()) {
                throw new IllegalArgumentException("无法解码图像");
            }
            try {
                int srcW = src.cols();
                int srcH = src.rows();
                // resize 高到 512，保持比例；限制最大宽度避免超长文本块导致内存爆炸
                float upScale = (float) DETECT_RESOLUTION / srcH;
                int resizedW = Math.max(1, (int) (upScale * srcW));
                if (resizedW > MAX_RESIZED_WIDTH) {
                    // 超宽时按比例整体缩小（高度随之 < 512），保持内存可控
                    float shrink = (float) MAX_RESIZED_WIDTH / resizedW;
                    resizedW = MAX_RESIZED_WIDTH;
                    upScale = (float) DETECT_RESOLUTION / srcH * shrink;
                }
                int targetH = Math.max(1, (int) (upScale * srcH));

                Mat resized = new Mat();
                Imgproc.resize(src, resized, new Size(resizedW, targetH), 0, 0, Imgproc.INTER_LINEAR);

                float[] pixels = new float[3 * targetH * resizedW];
                for (int y = 0; y < targetH; y++) {
                    for (int x = 0; x < resizedW; x++) {
                        double[] bgr = resized.get(y, x);
                        int idx = y * resizedW + x;
                        // RGB 顺序 + 归一化 (v/255 - 0.5) / 0.5
                        pixels[idx] = (((float) bgr[2] / 255.0f) - MEAN[0]) / STD[0];
                        pixels[idx + targetH * resizedW] = (((float) bgr[1] / 255.0f) - MEAN[1]) / STD[1];
                        pixels[idx + 2 * targetH * resizedW] = (((float) bgr[0] / 255.0f) - MEAN[2]) / STD[2];
                    }
                }
                resized.release();

                long[] shape = {1, 3, targetH, resizedW};
                try (OnnxTensor tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(pixels), shape)) {
                    try (OrtSession.Result result = session.run(java.util.Map.of("input", tensor))) {
                        Object out = result.get(0).getValue();
                        float[][][][] output;
                        if (out instanceof float[][][][]) {
                            output = (float[][][][]) out;
                        } else if (out instanceof float[][][]) {
                            float[][][] arr = (float[][][]) out;
                            output = new float[1][][][];
                            output[0] = arr;
                        } else {
                            throw new IllegalArgumentException("TextBSR 输出格式不识别: " + out.getClass());
                        }
                        return toBufferedImage(output[0], resizedW * 4, targetH * 4);
                    }
                }
            } finally {
                src.release();
            }
        } catch (Exception e) {
            throw new RuntimeException("[text-bsr] 文字超分辨率失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 ONNX 输出 [3,H*4,W*4]（归一化）转为 BufferedImage。
     */
    private BufferedImage toBufferedImage(float[][][] data, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float r = (data[0][y][x] * 0.5f + 0.5f) * 255f;
                float g = (data[1][y][x] * 0.5f + 0.5f) * 255f;
                float b = (data[2][y][x] * 0.5f + 0.5f) * 255f;
                int rv = Math.max(0, Math.min(255, Math.round(r)));
                int gv = Math.max(0, Math.min(255, Math.round(g)));
                int bv = Math.max(0, Math.min(255, Math.round(b)));
                img.setRGB(x, y, (rv << 16) | (gv << 8) | bv);
            }
        }
        return img;
    }

    /**
     * 关闭底层 ONNX Session。
     */
    public synchronized void close() {
        try {
            if (session != null) {
                session.close();
            }
        } catch (Exception ignore) {
        }
        session = null;
        ortEnv = null;
    }
}
