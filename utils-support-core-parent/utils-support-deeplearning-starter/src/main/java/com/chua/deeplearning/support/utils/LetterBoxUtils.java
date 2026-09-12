package com.chua.deeplearning.support.utils;

import ai.djl.modality.cv.output.Landmark;
import ai.djl.modality.cv.output.Point;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.index.NDIndex;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;

import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.awt.image.BufferedImage;
import java.util.ArrayList;


/**
* letterbox 图像缩放填充工具，将图像等比缩放后填充到目标尺寸，满足 YOLO 等模型输入要求
*
* @author CH
* @since 4.0.0.42
 */
public class LetterBoxUtils {

    /**
    * letterbox 填充位置枚举，定义图像缩放后在画布中的放置位置
    * @author CH
    * @since 4.0.0
     */
    public enum PaddingPosition {
        /**
        * 居中填充：图像缩放后放置在画布中央
         */
        CENTER,
        /**
        * 左上角填充：图像缩放后放置在画布左上角
         */
        LEFT_TOP,
        /**
        * 右下角填充：图像缩放后放置在画布右下角
         */
        RIGHT_BOTTOM
    }

    /**
    * letterbox 缩放结果，保存缩放后的图像和缩放填充参数
    * @author CH
    * @since 4.0.0
     */
    public static class ResizeResult {
        /**
        * letterbox 处理后的 ndarray 图像张量（HWC 格式）
         */
        public NDArray image;
        /**
        * 等比缩放比例
         */
        public float r;
        /**
        * 左侧填充宽度（像素）
         */
        public int left;
        /**
        * 上方填充高度（像素）
         */
        public int top;
        /**
        * 水平方向填充总宽度（左侧+右侧，像素）
         */
        public int padW;
        /**
        * 垂直方向填充总高度（上方+下方，像素）
         */
        public int padH;
    }

    /**
    * 使用已有的缩放参数构造 resize结果 结果对象
    *
    * @param paddingImg 填充后的 ndarray 图像
    * @param r          等比缩放比例
    * @param left       左侧填充像素数
    * @param top        顶部填充像素数
    * @return 封装了缩放元数据的 resize结果 对象
     */
    public static ResizeResult letterboxWithMeta(NDArray paddingImg, float r, int left, int top) {
        var result = new ResizeResult();
        result.image = paddingImg;
        result.r = r;
        result.left = left;
        result.top = top;
        result.padW = left * 2;
        result.padH = top * 2;
        return result;
    }

    /**
    * 对图像执行 letterbox 等比缩放 + 填充操作，返回缩放后的图像和元数据
    *
    * @param manager  nd管理器，用于创建新的 ndarray 张量
    * @param img      输入图像 ndarray（HWC 格式）
    * @param targetW  目标宽度（像素）
    * @param targetH  目标高度（像素）
    * @param padColor 填充颜色值（RGB 归一化到 0-1 范围）
    * @param position 填充位置策略，可选 CENTER / LEFT_TOP / RIGHT_BOTTOM
    * @return 包含缩放后图像和元数据的 resize结果 对象
    * @param ndManager nd管理器
     */
    public static ResizeResult letterbox(NDManager ndManager, NDArray img, int targetW, int targetH, float padColor, PaddingPosition position) {
        long origH = img.getShape().get(0);
        long origW = img.getShape().get(1);

        float r = Math.min(targetW / (float) origW, targetH / (float) origH);
        int newW = Math.round(origW * r);
        int newH = Math.round(origH * r);

 // 用 AWT 完成 resize + padding（规避 DJL ndarray 不支持 设置/nd索引）
        long[] shape = img.getShape().getShape();
        int oH = (int) shape[0], oW = (int) shape[1];
        float[] pixels = img.toType(DataType.FLOAT32, false).toFloatArray();
        int len = oW * oH;
        BufferedImage bi = new BufferedImage(oW, oH, BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < oH; y++) {
            for (int x = 0; x < oW; x++) {
                int idx = y * oW + x;
                int rv = (int) (pixels[idx] * 255f);
                int gv = (int) (pixels[len + idx] * 255f);
                int bv = (int) (pixels[2 * len + idx] * 255f);
                int rgb = (Math.min(255, Math.max(0, rv)) << 16) | (Math.min(255, Math.max(0, gv)) << 8) | Math.min(255, Math.max(0, bv));
                bi.setRGB(x, y, rgb);
            }
        }
        // 缩放
        BufferedImage resized = ImageUtils.resize(bi, newW, newH, org.opencv.imgproc.Imgproc.INTER_LINEAR);
        // 填充到目标尺寸
        BufferedImage padded = new BufferedImage(targetW, targetH, BufferedImage.TYPE_3BYTE_BGR);
        java.awt.Graphics2D g2d = padded.createGraphics();
        int pc = Math.round(padColor);
        g2d.setColor(new java.awt.Color(pc, pc, pc));
        g2d.fillRect(0, 0, targetW, targetH);
        int padW = targetW - newW;
        int padH = targetH - newH;
        int top = 0, left = 0;
        switch (position) {
            case CENTER -> { left = padW / 2; top = padH / 2; }
            case LEFT_TOP -> { left = 0; top = 0; }
            case RIGHT_BOTTOM -> { left = padW; top = padH; }
        }
        g2d.drawImage(resized, left, top, null);
        g2d.dispose();
 // 转回 ndarray
        int tLen = targetW * targetH;
        float[] out = new float[3 * tLen];
        for (int y = 0; y < targetH; y++) {
            for (int x = 0; x < targetW; x++) {
                int rgb = padded.getRGB(x, y);
                int idx = y * targetW + x;
                out[idx] = ((rgb >> 16) & 0xFF) / 255f;
                out[tLen + idx] = ((rgb >> 8) & 0xFF) / 255f;
                out[2 * tLen + idx] = (rgb & 0xFF) / 255f;
            }
        }
        NDArray paddingImg = ndManager.create(out, new Shape(targetH, targetW, 3));
        var resizeResult = letterboxWithMeta(paddingImg, r, left, top);
        resizeResult.padW = padW;
        resizeResult.padH = padH;
        return resizeResult;
    }

    /**
    * 将 letterbox 处理后的边界框坐标还原到原始图像坐标系
    *
    * @param boxes           待还原的边界框 ndarray
    * @param scaleRatio       缩放比例
    * @param left             左侧填充偏移量
    * @param top              顶部填充偏移量
    * @param keypointStart    关键点起始列的索引位置
    * @param keypointDim      关键点维度，取 0 表示没有关键点
    * @return 还原到原始图像坐标系后的边界框 ndarray
     */
    public static NDArray restoreBox(NDArray boxes, float scaleRatio, float left, float top, int keypointStart, int keypointDim) {
        // 还原 bbox 坐标
        var x1 = boxes.get(":, 0").sub(left).div(scaleRatio);
        var y1 = boxes.get(":, 1").sub(top).div(scaleRatio);
        var x2 = boxes.get(":, 2").sub(left).div(scaleRatio);
        var y2 = boxes.get(":, 3").sub(top).div(scaleRatio);

        boxes.set(new NDIndex(":, 0"), x1);
        boxes.set(new NDIndex(":, 1"), y1);
        boxes.set(new NDIndex(":, 2"), x2);
        boxes.set(new NDIndex(":, 3"), y2);

        if (keypointDim > 0) {
            for (int i = 0; i < keypointDim; i += 2) {
                int xIdx = keypointStart + i;
                int yIdx = keypointStart + i + 1;
                var keyX = boxes.get(":, " + xIdx).sub(left).div(scaleRatio);
                var keyY = boxes.get(":, " + yIdx).sub(top).div(scaleRatio);
                boxes.set(new NDIndex(":, " + xIdx), keyX);
                boxes.set(new NDIndex(":, " + yIdx), keyY);
            }
        }
        return boxes;
    }

    /**
    * 将 letterbox 处理后的单个 Rectangle 边界框还原到原始图像坐标系
    *
    * @param rectangle        待还原的矩形边界框
    * @param scale            缩放比例
    * @param origImageWidth   原始图像宽度（像素）
    * @param origImageHeight                    原图像高度（像素）
    * @param inputWidth      模型输入宽度（像素），即 letterbox 的目标宽度
    * @param inputHeight                       模型输入高度（像素），即 letterbox 的目标高度
    * @return 还原后的归一化 Rectangle 对象
     */
    public static Rectangle restoreBox(Rectangle rectangle, float scale, int origImageWidth, int origImageHeight, int inputWidth, int inputHeight) {
        double paddingWidth = (inputWidth - origImageWidth * scale) / 2;
        double paddingHeight = (inputHeight - origImageHeight * scale) / 2;

        // 扣除 padding
        double xNoPad = rectangle.getX() - paddingWidth;
        double yNoPad = rectangle.getY() - paddingHeight;

        // 还原到原图坐标系并归一化
        double x1 = xNoPad / scale / origImageWidth;
        double y1 = yNoPad / scale / origImageHeight;
        double boxW = rectangle.getWidth() / scale / origImageWidth;
        double boxH = rectangle.getHeight() / scale / origImageHeight;
        return new Rectangle(x1, y1, boxW, boxH);
    }

    /**
    * 将 letterbox 处理后的 Landmark 关键点还原到原始图像坐标系
    *
    * @param landmark                 Landmark 关键点对象
    * @param scale                    缩放比例
    * @param origImageWidth  原始图像宽度（像素）
    * @param origImageHeight                      原始图像高度（像素）
    * @param inputWidth      模型输入图像宽度（像素），即 letterbox 的目标宽度
    * @param inputHeight                       模型输入图像高度（像素），即 letterbox 的目标高度
    * @param isNormalized             关键点坐标是否为归一化坐标（0-1 范围）
    * @return 还原后的 Landmark 对象
     */
    public static Landmark restoreBox(Landmark landmark, float scale, int origImageWidth, int origImageHeight, int inputWidth, int inputHeight, boolean isNormalized) {
        double x = 0;
        double y = 0;
        double width = 0;
        double height = 0;
        if (isNormalized) {
            x = landmark.getX() * inputWidth;
            y = landmark.getY() * inputHeight;
            width = landmark.getWidth() * inputWidth;
            height = landmark.getHeight() * inputHeight;
        } else {
            x = landmark.getX();
            y = landmark.getY();
            width = landmark.getWidth();
            height = landmark.getHeight();
        }
        double paddingWidth = (inputWidth - origImageWidth * scale) / 2;
        double paddingHeight = (inputHeight - origImageHeight * scale) / 2;

        // 扣除 padding
        double xNoPad = x - paddingWidth;
        double yNoPad = y - paddingHeight;

        // 还原到原图坐标系并归一化
        double x1 = xNoPad / scale / origImageWidth;
        double y1 = yNoPad / scale / origImageHeight;
        double boxW = width / scale / origImageWidth;
        double boxH = height / scale / origImageHeight;

        var points = new ArrayList<Point>();
        // 还原关键点路径上的坐标
        landmark.getPath().forEach(point -> {
            double pointX = (point.getX() - paddingWidth) / scale;
            double pointY = (point.getY() - paddingHeight) / scale;
            points.add(new Point(pointX, pointY));
        });
        return new Landmark(x1, y1, boxW, boxH, points);
    }

    /**
    * 计算图像在等比缩放后、填充前的实际尺寸
    *
    * @param origW                       原始图像宽度（像素）
    * @param origH                       原始图像高度（像素）
    * @param targetWidth                目标宽度（像素）
    * @param targetHeight                        目标高度（像素）
    * @return 长度为 2 的 int 数组 [width, height]，即等比缩放后的宽度和高度
     */
    public static int[] getResizeSize(int origW, int origH, int targetWidth, int targetHeight) {
        float r = Math.min(targetWidth / (float) origW, targetHeight / (float) origH);
        int newW = Math.round(origW * r);
        int newH = Math.round(origH * r);
        return new int[]{newW, newH};
    }

    /**
    * 将 letterbox 图像中的绝对坐标还原为原始图像坐标
    *
    * @param targetW letterbox 目标宽度（像素）
    * @param targetH                letterbox 目标高度（像素）
    * @param x1      letterbox 图像中的 x1 坐标（像素）
    * @param y1              letterbox 图像中的 y1 坐标（像素）
    * @param x2              letterbox 图像中的 x2 坐标（像素）
    * @param y2              letterbox 图像中的 y2 坐标（像素）
    * @param result  letterbox 缩放结果对象，包含缩放比例和 padding 偏移
    * @param origW          原始图像宽度（像素）
    * @param origH                         原始图像高度（像素）
    * @return 还原后的坐标数组 [x1, y1, x2, y2]
     */
    public static float[] scaleCoords(int targetW, int targetH, float x1, float y1, float x2, float y2, ResizeResult result, int origW, int origH) {
        float scale = result.r;
        float scaledX1 = (x1 - result.left) / scale;
        float scaledY1 = (y1 - result.top) / scale;
        float scaledX2 = (x2 - result.left) / scale;
        float scaledY2 = (y2 - result.top) / scale;

        scaledX1 = Math.max(0, Math.min(origW, scaledX1));
        scaledY1 = Math.max(0, Math.min(origH, scaledY1));
        scaledX2 = Math.max(0, Math.min(origW, scaledX2));
        scaledY2 = Math.max(0, Math.min(origH, scaledY2));

        return new float[]{scaledX1, scaledY1, scaledX2, scaledY2};
    }

    /**
    * 使用 AWT 缓冲镜像 缩放图像（替代 DJL nd镜像工具.resize，规避 Rust ndarray 不支持 resize 的问题）。
    *
    * @param ndManager nd管理器
    * @param img       输入图像 ndarray（HWC float32，值范围 0-1）
    * @param newW      目标宽度
    * @param newH      目标高度
    * @return 缩放后的 ndarray（HWC float32）
     */
    public static NDArray resizeWithAwt(NDManager ndManager, NDArray img, int newW, int newH) {
        long[] shape = img.getShape().getShape();
        int origH = (int) shape[0];
        int origW = (int) shape[1];
        float[] pixels = img.toType(DataType.FLOAT32, false).toFloatArray();
        int len = origW * origH;
        BufferedImage bi = new BufferedImage(origW, origH, BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < origH; y++) {
            for (int x = 0; x < origW; x++) {
                int idx = y * origW + x;
                int r = (int) (pixels[idx] * 255f);
                int g = (int) (pixels[len + idx] * 255f);
                int b = (int) (pixels[2 * len + idx] * 255f);
                int rgb = (r << 16) | (g << 8) | (b);
                if (rgb < 0) {
                    rgb = 0;
                }
                bi.setRGB(x, y, rgb);
            }
        }
        Mat src = ImageUtils.toMat(bi);
        Mat resized;
        try {
            resized = ImageUtils.resize(src, newW, newH, Imgproc.INTER_LINEAR);
        } finally {
            src.release();
        }
        BufferedImage resizedImage = ImageUtils.toBufferedImage(resized);
        resized.release();
        int newLen = newW * newH;
        float[] out = new float[3 * newLen];
        for (int y = 0; y < newH; y++) {
            for (int x = 0; x < newW; x++) {
                int rgb = resizedImage.getRGB(x, y);
                int idx = y * newW + x;
                out[idx] = ((rgb >> 16) & 0xFF) / 255f;
                out[newLen + idx] = ((rgb >> 8) & 0xFF) / 255f;
                out[2 * newLen + idx] = (rgb & 0xFF) / 255f;
            }
        }
        return ndManager.create(out, new Shape(newH, newW, 3));
    }
}