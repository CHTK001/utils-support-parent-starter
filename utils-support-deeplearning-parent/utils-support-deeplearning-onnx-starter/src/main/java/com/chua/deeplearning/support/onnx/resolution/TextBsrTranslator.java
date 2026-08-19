package com.chua.deeplearning.support.onnx.resolution;
import com.chua.deeplearning.support.utils.ImageUtils;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * TextBSR 文字超分辨率（ORT 原生 + OpenCV）。
 *
 * <p>基于 RRDBNet（scale=4）的文字图像盲超分模型，提升模糊文字清晰度，OCR 预处理。
 * 模型 {@code vision/text_restore/textbsr/textbsr.onnx} 由 jar
 * {@code utils-support-models-onnx-textbsr} 提供。输入 {@code input [1,3,H,W]}
 * （归一化 (v/255-0.5)/0.5），输出 {@code output [1,3,H*4,W*4]} 高清图。</p>
 *
 * <p>模型固定 4x 重建。默认 {@code scale=2}（相对原图 2x）：输入高度按 {@code 原高*2/4} 缩放，
 * 推理面积约为旧 4x 逻辑的 1/4，速度提升约 4 倍，输出对 OCR 足够清晰。可配置为 4x
 * 获取更强细节。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class TextBsrTranslator implements ITranslator<byte[], BufferedImage> {

    /**
     * 最小输入高度（保底：即使原图很矮，也保证输入不小于该高度，输出至少 4 倍于它，
     * 避免超分后文字仍模糊）。
     */
    private static final int MIN_INPUT_HEIGHT = 24;

    /**
     * 输入宽度上限（超宽文本块按比例整体缩小，防内存爆炸）。
     */
    private static final int MAX_RESIZED_WIDTH = 2048;

    /**
     * 最终放大倍数（相对原图），默认 2x；可配置为 4x。
     */
    private int scale = 2;

    /**
     * 无参构造（默认 2x）。
     */
    public TextBsrTranslator() {
    }

    /**
     * 指定放大倍数构造。
     *
     * @param scale 放大倍数（1~4）
     */
    public TextBsrTranslator(int scale) {
        this.scale = Math.max(1, Math.min(4, scale));
    }

    /**
     * 获取放大倍数。
     *
     * @return 放大倍数
     */
    public int getScale() {
        return scale;
    }

    /**
     * 设置放大倍数。
     *
     * @param scale 放大倍数（1~4）
     */
    public void setScale(int scale) {
        this.scale = Math.max(1, Math.min(4, scale));
    }

    /** 均值数组 */
    /** Mean */
    private static final float[] MEAN = {0.5f, 0.5f, 0.5f};
    /** 标准差数组 */
    /** STD */
    private static final float[] STD = {0.5f, 0.5f, 0.5f};

    /** 资源基础路径 */
    /** Resource_base */
    private static final String RESOURCE_BASE = "vision/text_restore/textbsr/";
    /** 模型文件路径 */
    /** Model_file */
    private static final String MODEL_FILE = "textbsr.onnx";

    /** ONNX 运行时环境 */
    /** ORTENV */
    private OrtEnvironment ortEnv;
    /** 会话 */
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
            ImageUtils.load();
            Mat src = ImageUtils.decode(imageData);
            if (src == null || src.empty()) {
                throw new IllegalArgumentException("无法解码图像");
            }
            try {
                int srcW = src.cols();
                int srcH = src.rows();
                // 模型固定 4x 重建：目标相对原图 scale 倍 → 输入高度 = 原高 * scale / 4。
                // 例如 scale=2 时输入高度仅原高一半，推理面积降为旧 4x 逻辑的 1/4。
                int inH = Math.max(MIN_INPUT_HEIGHT, srcH * scale / 4);
                float inScale = (float) inH / srcH;
                int inW = Math.max(1, (int) (inScale * srcW));
                if (inW > MAX_RESIZED_WIDTH) {
                    // 超宽时按比例整体缩小，保持内存可控
                    float shrink = (float) MAX_RESIZED_WIDTH / inW;
                    inW = MAX_RESIZED_WIDTH;
                    inH = Math.max(1, (int) (inH * shrink));
                }

                Mat resized = ImageUtils.resize(src, inW, inH, Imgproc.INTER_LINEAR);

                float[] pixels = new float[3 * inH * inW];
                for (int y = 0; y < inH; y++) {
                    for (int x = 0; x < inW; x++) {
                        double[] bgr = resized.get(y, x);
                        int idx = y * inW + x;
                        // RGB 顺序 + 归一化 (v/255 - 0.5) / 0.5
                        pixels[idx] = (((float) bgr[2] / 255.0f) - MEAN[0]) / STD[0];
                        pixels[idx + inH * inW] = (((float) bgr[1] / 255.0f) - MEAN[1]) / STD[1];
                        pixels[idx + 2 * inH * inW] = (((float) bgr[0] / 255.0f) - MEAN[2]) / STD[2];
                    }
                }
                resized.release();

                long[] shape = {1, 3, inH, inW};
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
                        return toBufferedImage(output[0], inW * 4, inH * 4);
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
