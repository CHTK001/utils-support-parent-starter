package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.ocr.OcrPipeline;
import com.chua.deeplearning.support.ocr.OcrResult;
import com.chua.deeplearning.support.utils.ImageUtils;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 对比 rotate 实现：当前 SPI 版 vs 纯 OpenCV 版，对旋转车票识别票号。
 *
 * @since 4.0.0.42
 */
public final class OcrRotateCompare {

    /** 创建 OcrRotateCompare 实例 */
    private OcrRotateCompare() {
    }

    /** Main */
    public static void main(String[] args) throws Exception {
        ImageUtils.load();
        byte[] img0 = Files.readAllBytes(Path.of("G:\\images\\车票.png"));
        byte[] img90 = Files.readAllBytes(Path.of("G:\\images\\车票ticket_90.png"));
        byte[] img270 = Files.readAllBytes(Path.of("G:\\images\\车票ticket_270.png"));

        // 正向原图直接识别
        recognize("正向原图", img0);

        // 用正向原图生成 90° 旋转（无损），再旋转回正向，验证"旋转往返"是否丢 Z
        byte[] gen90 = ImageUtils.rotate(img0, 90);
        byte[] gen90_back = ImageUtils.rotate(gen90, 270);
        recognize("正向图->转90->转回", gen90_back);

        // 现有 ticket_90 图旋转回正向
        byte[] rot90_spi = ImageUtils.rotate(img90, 270);
        recognize("ticket_90图->转回", rot90_spi);

        // 保留旋转回正向的图和生成图，做票号区域像素对比
        byte[] gen_back = ImageUtils.rotate(gen90, 270);
        java.nio.file.Files.write(java.nio.file.Path.of("G:\\images\\output\\diag_ticket90_back.png"), rot90_spi);
        java.nio.file.Files.write(java.nio.file.Path.of("G:\\images\\output\\diag_orig.png"), img0);
        // 将 ticket_90 转回图缩放对齐到正向尺寸后裁剪放大，供肉眼核对 Z 字形
        saveZoomedAligned(img0, rot90_spi, 103, 65, 253, 20,
                "G:\\images\\output\\diag_ticketno_orig.png",
                "G:\\images\\output\\diag_ticketno_from90.png");
        System.out.println("[cmp] 已保存: diag_ticket90_back.png, diag_orig.png, diag_ticketno_orig.png, diag_ticketno_from90.png");
        System.out.println("[cmp] 票号区域像素对比(正向原图 vs ticket_90转回):");
    }

    /**
     * 保存ZoomedAligned
     * @param ref ref
     * @param src src
     * @param x x
     * @param y y
     * @param w w
     * @param h h
     * @param outRef outRef
     * @param outSrc outSrc
     */
    private static void saveZoomedAligned(byte[] ref, byte[] src, int x, int y, int w, int h,
                                          String outRef, String outSrc) {
        try {
            ImageUtils.load();
            Mat mr = ImageUtils.decode(ref);
            Mat ms = ImageUtils.decode(src);
            // 将 src 缩放到 ref 尺寸
            Mat msScaled = new Mat();
            org.opencv.imgproc.Imgproc.resize(ms, msScaled, new org.opencv.core.Size(mr.cols(), mr.rows()),
                    0, 0, org.opencv.imgproc.Imgproc.INTER_LINEAR);
            saveZoomedHelper(mr, x, y, w, h, outRef);
            saveZoomedHelper(msScaled, x, y, w, h, outSrc);
            msScaled.release(); mr.release(); ms.release();
        } catch (Exception e) {
            System.out.println("[cmp] saveZoomedAligned 失败: " + e.getMessage());
        }
    }

    /** 保存ZoomedHelper */
    private static void saveZoomedHelper(Mat src, int x, int y, int w, int h, String out) {
        Mat crop = new Mat(src, new org.opencv.core.Rect(x, y, w, h));
        Mat big = new Mat();
        org.opencv.imgproc.Imgproc.resize(crop, big, new org.opencv.core.Size(w * 8, h * 8),
                0, 0, org.opencv.imgproc.Imgproc.INTER_NEAREST);
        org.opencv.imgcodecs.Imgcodecs.imwrite(out, big);
        crop.release(); big.release();
    }

    /** RegionMae */
    private static double regionMae(byte[] a, byte[] b, int x, int y, int w, int h) {
        try {
            ImageUtils.load();
            Mat ma = ImageUtils.decode(a);
            Mat mb = ImageUtils.decode(b);
            if (ma == null || mb == null || x + w > ma.cols() || y + h > ma.rows()
                    || x + w > mb.cols() || y + h > mb.rows()) {
                return -1;
            }
            try {
                Mat ra = new Mat(ma, new org.opencv.core.Rect(x, y, w, h));
                Mat rb = new Mat(mb, new org.opencv.core.Rect(x, y, w, h));
                ra.convertTo(ra, org.opencv.core.CvType.CV_32F);
                rb.convertTo(rb, org.opencv.core.CvType.CV_32F);
                Mat diff = new Mat();
                org.opencv.core.Core.absdiff(ra, rb, diff);
                org.opencv.core.Core.meanStdDev(diff, new org.opencv.core.MatOfDouble(), new org.opencv.core.MatOfDouble());
                double mean = org.opencv.core.Core.mean(diff).val[0];
                ra.release(); rb.release(); diff.release();
                return mean;
            } finally {
                ma.release(); mb.release();
            }
        } catch (Exception e) {
            return -999;
        }
    }

    /** Recognize */
    private static void recognize(String label, byte[] img) {
        try {
            OcrPipeline ocr = OcrPipeline.builder()
                    .detector("paddleocrv6-medium-det")
                    .recognizer("paddleocrv6-medium-rec")
                    .direction("doc-orientation")
                    .build();
            List<OcrResult> results = ocr.recognizeDetail(img);
            System.out.println("[cmp] " + label + " 块数=" + results.size());
            for (OcrResult r : results) {
                String t = r.text();
                System.out.println("      '" + t + "' conf=" + String.format("%.2f", r.confidence()));
            }
        } catch (Exception e) {
            System.out.println("[cmp] " + label + " 异常: " + e.getMessage());
        }
    }

    /** Dims */
    private static String dims(byte[] data) {
        Mat m = ImageUtils.decode(data);
        if (m == null) return "null";
        String s = m.cols() + "x" + m.rows();
        m.release();
        return s;
    }

    /** SameBytes */
    private static boolean sameBytes(byte[] a, byte[] b) {
        return java.util.Arrays.equals(a, b);
    }

    /** RotateLocal */
    private static byte[] rotateLocal(byte[] imageData, int degree) {
        Mat src = Imgcodecs.imdecode(new MatOfByte(imageData), Imgcodecs.IMREAD_COLOR);
        if (src == null) return imageData;
        Mat out = new Mat();
        try {
            switch (degree) {
                case 90 -> org.opencv.core.Core.rotate(src, out, org.opencv.core.Core.ROTATE_90_CLOCKWISE);
                case 270 -> org.opencv.core.Core.rotate(src, out, org.opencv.core.Core.ROTATE_90_COUNTERCLOCKWISE);
                default -> org.opencv.core.Core.rotate(src, out, org.opencv.core.Core.ROTATE_180);
            }
            MatOfByte mob = new MatOfByte();
            Imgcodecs.imencode(".png", out, mob);
            return mob.toArray();
        } finally {
            out.release();
            src.release();
        }
    }
}