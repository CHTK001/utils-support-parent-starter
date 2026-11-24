// [P3C A20 豁免] A20/A30/A31: 图像预处理含大量像素级魔法值，豁免。
package com.chua.deeplearning.support.onnx.uniface;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import com.chua.deeplearning.support.utils.ImageUtils;

import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import org.opencv.imgproc.Imgproc;

/**
 * UniFace 系列模型通用图像预处理工具。
 *
 * <p>统一约定：输入 {@link Image} 为已裁剪的人脸/图像区域；内部完成缩放、
 * 通道整理与归一化，输出 {@code [1, 3, H, W]} 的 float32 张量（RGB 通道顺序）。
 * 归一化公式为 {@code (pixel / 255 - mean) / std}，与 UniFace(Python) 各任务的
 * 预处理保持一致。</p>
 *
 * <p>注意：ONNX Runtime 引擎的 NDArray 不支持 transpose/resize 等算子，
 * 因此全部在 Java 侧完成像素处理，一次性构建张量。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
final class UniFaceImagePreprocessor {

    /**
     * ImageNet 均值（RGB，0~1 区间）。
     */
    static final float[] IMAGENET_MEAN = {0.485f, 0.456f, 0.406f};

    /**
     * ImageNet 标准差（RGB，0~1 区间）。
     */
    static final float[] IMAGENET_STD = {0.229f, 0.224f, 0.225f};

    /**
     * 仅缩放到 [0,1] 的均值（不做过原点平移）。
     */
    static final float[] ZERO_MEAN = {0f, 0f, 0f};

    /**
     * 仅缩放到 [0,1] 的标准差。
     */
    static final float[] ONE_STD = {1f, 1f, 1f};

    /**
     * (pixel - 127.5) / 127.5 等价的归一化参数（0~1 空间 mean=0.5, std=0.5）。
     */
    static final float[] HALF_MEAN = {0.5f, 0.5f, 0.5f};

    /**
     * (pixel - 127.5) / 127.5 等价的归一化参数（0~1 空间 mean=0.5, std=0.5）。
     */
    static final float[] HALF_STD = {0.5f, 0.5f, 0.5f};

    private UniFaceImagePreprocessor() {
    }

    /**
     * 缩放图像并归一化为 {@code [1, 3, H, W]} 张量。
     *
     * @param image 输入图像
     * @param w     目标宽度
     * @param h     目标高度
     * @param mean  各通道均值（0~1 区间，RGB 顺序）
     * @param std   各通道标准差（0~1 区间，RGB 顺序）
     * @param mgr   NDManager
     * @return [1, 3, H, W] 的 nd列表
     */
    static NDList toTensor(Image image, int w, int h, float[] mean, float[] std, NDManager mgr) {
        float[] chw = rgbChw(unwrap(image), w, h, mean, std);
        return wrap(chw, w, h, mgr);
    }

    /**
     * letterbox 缩放（保持长宽比 + 居中零填充）后归一化为 {@code [1, 3, S, S]} 张量。
     *
     * <p>与 UniFace(Python) {@code letterbox_resize(fill_value=0)} 对齐：
     * 先按 {@code target / max(w, h)} 等比缩放，再在像素空间以 0 居中填充，
     * 最后统一做 {@code (pixel/255 - mean) / std} 归一化。</p>
     *
     * @param image  输入图像
     * @param target 目标边长（正方形）
     * @param mean   各通道均值（0~1 区间，RGB 顺序）
     * @param std    各通道标准差（0~1 区间，RGB 顺序）
     * @param mgr    NDManager
     * @return [1, 3, S, S] 的 nd列表
     */
    static NDList toLetterboxTensor(Image image, int target, float[] mean, float[] std, NDManager mgr) {
        BufferedImage src = unwrap(image);
        int w = src.getWidth();
        int h = src.getHeight();
        float scale = target / (float) Math.max(w, h);
        int newW = Math.max(1, Math.min(target, (int) (w * scale)));
        int newH = Math.max(1, Math.min(target, (int) (h * scale)));
        BufferedImage resized = ImageUtils.resize(src, newW, newH, Imgproc.INTER_LINEAR);
        int padH = (target - newH) / 2;
        int padW = (target - newW) / 2;

        int plane = target * target;
        float[] chw = new float[3 * plane];
        // 像素空间填充值 0 归一化后的各通道取值
        java.util.Arrays.fill(chw, 0, plane, -mean[0] / std[0]);
        java.util.Arrays.fill(chw, plane, 2 * plane, -mean[1] / std[1]);
        java.util.Arrays.fill(chw, 2 * plane, 3 * plane, -mean[2] / std[2]);

        int[] pixels = resized.getRGB(0, 0, newW, newH, null, 0, newW);
        for (int y = 0; y < newH; y++) {
            int dstRow = (padH + y) * target + padW;
            int srcRow = y * newW;
            for (int x = 0; x < newW; x++) {
                int p = pixels[srcRow + x];
                chw[dstRow + x] = (((p >> 16) & 0xff) / 255f - mean[0]) / std[0];
                chw[plane + dstRow + x] = (((p >> 8) & 0xff) / 255f - mean[1]) / std[1];
                chw[2 * plane + dstRow + x] = ((p & 0xff) / 255f - mean[2]) / std[2];
            }
        }
        return wrap(chw, target, target, mgr);
    }

    /**
     * 缩放 + RGB 通道整理 + 归一化，输出 CHW float 数组（不创建张量）。
     *
     * @param src  源图像
     * @param w    目标宽度
     * @param h    目标高度
     * @param mean 各通道均值（0~1 区间，RGB 顺序）
     * @param std  各通道标准差（0~1 区间，RGB 顺序）
     * @return 长度 3*w*h 的 CHW 数组
     */
    static float[] rgbChw(BufferedImage src, int w, int h, float[] mean, float[] std) {
        BufferedImage resized = (src.getWidth() == w && src.getHeight() == h)
                ? src : ImageUtils.resize(src, w, h, Imgproc.INTER_LINEAR);
        int[] pixels = resized.getRGB(0, 0, w, h, null, 0, w);
        float[] chw = new float[3 * w * h];
        int plane = w * h;
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            chw[i] = (((p >> 16) & 0xff) / 255f - mean[0]) / std[0];
            chw[plane + i] = (((p >> 8) & 0xff) / 255f - mean[1]) / std[1];
            chw[2 * plane + i] = ((p & 0xff) / 255f - mean[2]) / std[2];
        }
        return chw;
    }

    /**
     * 将 CHW float 数组包装为 {@code [1, 3, H, W]} 张量。
     *
     * @param chw CHW 数据
     * @param w   宽
     * @param h   高
     * @param mgr NDManager
     * @return nd列表
     */
    static NDList wrap(float[] chw, int w, int h, NDManager mgr) {
        NDArray array = mgr.create(FloatBuffer.wrap(chw), new Shape(1, 3, h, w), DataType.FLOAT32);
        return new NDList(array);
    }

    /**
     * 从 DJL Image 中取出底层 BufferedImage。
     *
     * @param image DJL 图像
     * @return BufferedImage
     */
    static BufferedImage unwrap(Image image) {
        Object wrapped = image.getWrappedImage();
        if (wrapped instanceof BufferedImage bi) {
            return bi;
        }
        throw new IllegalStateException("不支持的图像实现: " + wrapped.getClass().getName());
    }
}
