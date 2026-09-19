package com.chua.deeplearning.support.pytorch.face.seg;

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
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * 解析net 人脸分割 Translator（AIAS face_restoration_sdk 同款）。
 *
 * <p>PyTorch TorchScript 模型（parsenet_traced_model.pt），输入 512×512 人脸图，
 * 输出人脸软 mask（0~255 灰度，含皮肤/五官/头发/耳朵，排除背景/颈部/眼镜/口罩/衣领），
 * 供修复后贴回原图使用。包含两次高斯模糊（101×101）+ 去除 10Px 黑边。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FaceSegTranslator implements Translator<Image, Image> {

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
     * 黑边去除像素数。
     */
    private static final int THRESHOLD = 10;

    @Override
    /**
     * 处理输入
    */
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDManager manager = ctx.getNDManager();
        NDArray array = input.toNDArray(manager).toType(DataType.FLOAT32, false);
        array = array.transpose(2, 0, 1).div(255.0f);
        NDArray mean = manager.create(MEAN, new Shape(3, 1, 1));
        NDArray std = manager.create(STD, new Shape(3, 1, 1));
        array = array.sub(mean).div(std);
        return new NDList(array);
    }

    @Override
    /**
     * 处理输出
    */
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDManager manager = ctx.getNDManager();
        NDArray out = list.getFirst();
        if (out.getShape().dimension() == 4 && out.getShape().get(0) == 1) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
            out = out.squeeze(0);
        }
        // [19, H, W] -> [H, W] 类别索引
        NDArray cls = out.argMax(0);
        long h = cls.getShape().get(0); // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
        long w = cls.getShape().get(1);
        long[] clsArr = cls.toLongArray();
        int hh = (int) h;
        int ww = (int) w;

        // 按 MASK_COLORMAP 生成二值 mask（单通道 CV_8UC1）
        byte[] bytes = new byte[hh * ww];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) MASK_COLORMAP[(int) clsArr[i]];
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

 // 去除 10Px 黑边
        if (THRESHOLD < hh && THRESHOLD < ww) {
            blur2.submat(0, THRESHOLD, 0, ww).setTo(org.opencv.core.Scalar.all(0));
            blur2.submat(hh - THRESHOLD, hh, 0, ww).setTo(org.opencv.core.Scalar.all(0));
            blur2.submat(0, hh, 0, THRESHOLD).setTo(org.opencv.core.Scalar.all(0));
            blur2.submat(0, hh, ww - THRESHOLD, ww).setTo(org.opencv.core.Scalar.all(0));
        }

        // Mat -> float[] -> 3 通道 RGB NDArray（Image 通用表示，避免单通道特例）
        byte[] outBytes = new byte[hh * ww];
        blur2.get(0, 0, outBytes);
        blur2.release();
        byte[] rgb = new byte[hh * ww * 3];
        for (int i = 0; i < hh * ww; i++) {
            byte v = outBytes[i];
            rgb[i * 3] = v;
            rgb[i * 3 + 1] = v;
            rgb[i * 3 + 2] = v;
        }
        NDArray rgbArr = manager.create(rgb, new Shape(hh, ww, 3));
        return ImageFactory.getInstance().fromNDArray(rgbArr);
    }

    @Override
    /**
     * 获取Batchifier
    */
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
