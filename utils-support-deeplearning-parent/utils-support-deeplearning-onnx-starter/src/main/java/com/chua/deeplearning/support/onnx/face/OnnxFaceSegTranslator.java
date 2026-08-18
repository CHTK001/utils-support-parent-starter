package com.chua.deeplearning.support.onnx.face;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.ImageUtils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * ParseNet 人脸分割 Translator（ONNX 版，AIAS traced 导出）。
 *
 * <p>模型输入 512×512 人脸图（RGB 归一化 mean/std=0.5），输出 [1,19,512,512] 分割 logits；
 * 内部 argmax + 二值化 + 高斯模糊，输出人脸软 mask 图像（0~255 灰度）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OnnxFaceSegTranslator implements Translator<Image, Image> {

    /**
     * mask 类别映射（0/255 二值）：0 表示排除（背景/颈部/眼镜/口罩/衣领）。
     */
    private static final int[] MASK_COLORMAP = {
            0, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 0, 255, 0, 0, 0
    };

    /**
     * 输入均值。
     */
    private static final float[] MEAN = {0.5f, 0.5f, 0.5f};

    /**
     * 输入标准差。
     */
    private static final float[] STD = {0.5f, 0.5f, 0.5f};

    /**
     * 输入尺寸。
     */
    private static final int INPUT_SIZE = 512;

    /**
     * 黑边去除像素数。
     */
    private static final int THRESHOLD = 10;

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDManager manager = ctx.getNDManager();
        int w = input.getWidth();
        int h = input.getHeight();
        java.awt.image.BufferedImage src = (java.awt.image.BufferedImage) input.getWrappedImage();
        java.awt.image.BufferedImage resized = ImageUtils.resize(src, INPUT_SIZE, INPUT_SIZE, org.opencv.imgproc.Imgproc.INTER_LINEAR);
        int[] pixels = resized.getRGB(0, 0, INPUT_SIZE, INPUT_SIZE, null, 0, INPUT_SIZE);
        float[] data = new float[3 * INPUT_SIZE * INPUT_SIZE];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            data[i] = (((p >> 16) & 0xff) / 255f - 0.5f) / 0.5f;
            data[i + INPUT_SIZE * INPUT_SIZE] = (((p >> 8) & 0xff) / 255f - 0.5f) / 0.5f;
            data[i + 2 * INPUT_SIZE * INPUT_SIZE] = ((p & 0xff) / 255f - 0.5f) / 0.5f;
        }
        NDArray array = manager.create(data, new Shape(3, INPUT_SIZE, INPUT_SIZE));
        return new NDList(array);
    }

    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray out = list.get(0);
        long[] shape = out.getShape().getShape();
        if (shape.length == 4) {
            out = out.squeeze(0);
            shape = out.getShape().getShape();
        }
        // [19, H, W] logits
        long cls = shape[0];
        long h = shape[1];
        long w = shape[2];
        float[] logits = out.toFloatArray();
        int hh = (int) h;
        int ww = (int) w;

        // argmax 得到类别索引
        byte[] bytes = new byte[hh * ww];
        for (int y = 0; y < hh; y++) {
            for (int x = 0; x < ww; x++) {
                int idx = y * ww + x;
                float maxv = Float.NEGATIVE_INFINITY;
                int maxc = 0;
                for (int c = 0; c < cls; c++) {
                    float v = logits[c * hh * ww + idx];
                    if (v > maxv) {
                        maxv = v;
                        maxc = c;
                    }
                }
                bytes[idx] = (byte) MASK_COLORMAP[maxc];
            }
        }
        Mat mask = new Mat(hh, ww, CvType.CV_8UC1);
        mask.put(0, 0, bytes);

        // 高斯模糊两次（AIAS 同款，101×101，sigma 11）
        Mat blur1 = new Mat();
        Mat blur2 = new Mat();
        Imgproc.GaussianBlur(mask, blur1, new Size(101, 101), 11);
        Imgproc.GaussianBlur(blur1, blur2, new Size(101, 101), 11);
        mask.release();
        blur1.release();

        // 去除 10px 黑边
        if (THRESHOLD < hh && THRESHOLD < ww) {
            blur2.submat(0, THRESHOLD, 0, ww).setTo(org.opencv.core.Scalar.all(0));
            blur2.submat(hh - THRESHOLD, hh, 0, ww).setTo(org.opencv.core.Scalar.all(0));
            blur2.submat(0, hh, 0, THRESHOLD).setTo(org.opencv.core.Scalar.all(0));
            blur2.submat(0, hh, ww - THRESHOLD, ww).setTo(org.opencv.core.Scalar.all(0));
        }

        byte[] outBytes = new byte[hh * ww];
        blur2.get(0, 0, outBytes);
        blur2.release();
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(ww, hh, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < hh; y++) {
            for (int x = 0; x < ww; x++) {
                int v = outBytes[y * ww + x] & 0xff;
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return ImageFactory.getInstance().fromImage(img);
    }

    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}