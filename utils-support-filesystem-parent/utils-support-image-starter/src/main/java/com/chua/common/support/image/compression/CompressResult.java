package com.chua.common.support.image.compression;

import java.util.Map;

/**
 * 图片压缩结果。
 *
 * <p>除输出字节外，还记录压缩前后的体积、最终格式与耗时，便于上层做批量统计或展示压缩率。
 *
 * @param data          压缩后的图片字节
 * @param originalSize  压缩前的字节数
 * @param format        最终输出格式（小写）
 * @param engine        执行压缩的引擎名称
 * @param cost          压缩耗时，单位毫秒
 * @param discarded     是否因为「压缩后反而更大」而返回了原始字节
 * @param sourceSize    源图片的像素宽
 * @param sourceHeight  源图片的像素高
 * @author CH
 * @since 4.0.0.43
 */
public record CompressResult(
        byte[] data,
        long originalSize,
        String format,
        String engine,
        long cost,
        boolean discarded,
        int sourceSize,
        int sourceHeight) {

    /**
     * 构造压缩结果
     *
     * @throws IllegalArgumentException 当输出字节为 {@code null} 或长度为 0 时
     */
    public CompressResult {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("压缩结果字节不能为空");
        }
        originalSize = originalSize <= 0 ? data.length : originalSize;
    }

    /**
     * 创建成功压缩的结果
     *
     * @param data         压缩后的字节
     * @param originalSize 原始字节数
     * @param format       输出格式
     * @param engine       引擎名称
     * @param cost         耗时毫秒
     * @param width        源图宽度
     * @param height       源图高度
     * @return 压缩结果
     */
    public static CompressResult of(byte[] data, long originalSize, String format,
                                    String engine, long cost, int width, int height) {
        return new CompressResult(data, originalSize, format, engine, cost, false, width, height);
    }

    /**
     * 创建「压缩无收益、返回原始字节」的结果
     *
     * @param data         原始字节
     * @param format       原始格式
     * @param engine       引擎名称
     * @param cost         耗时毫秒
     * @param width        源图宽度
     * @param height       源图高度
     * @return 压缩结果
     */
    public static CompressResult discarded(byte[] data, String format, String engine,
                                           long cost, int width, int height) {
        return new CompressResult(data, data.length, format, engine, cost, true, width, height);
    }

    /**
     * 获取压缩后的字节数
     *
     * @return 输出字节数
     */
    public int compressedSize() {
        return data.length;
    }

    /**
     * 获取节省的字节数
     *
     * @return 节省字节数，结果为负表示压缩后更大
     */
    public long savedSize() {
        return originalSize - data.length;
    }

    /**
     * 获取压缩率
     *
     * @return 节省比例，取值 0~1；未节省时为 0，压缩后更大时为负数
     */
    public double savedRatio() {
        if (originalSize <= 0) {
            return 0D;
        }
        return (double) savedSize() / originalSize;
    }

    /**
     * 输出用于日志打印的摘要
     *
     * @return 形如 {@code tinypng png: 1.0KB -> 312.0B (-69.5%)} 的摘要文本
     */
    public String describe() {
        return String.format("%s %s: %s -> %s (%s%.1f%%)",
                engine, format, toSize(originalSize), toSize(data.length),
                savedRatio() >= 0 ? "-" : "+", Math.abs(savedRatio()) * 100);
    }

    /**
     * 把字节数格式化为可读文本
     *
     * @param bytes 字节数
     * @return 形如 {@code 1.5MB} 的文本
     */
    private static String toSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1fKB", bytes / 1024D);
        }
        return String.format("%.1fMB", bytes / (1024D * 1024D));
    }

    /**
     * 输出压缩结果的属性视图
     *
     * @return 属性映射，键为属性名，值为属性值
     */
    public Map<String, Object> toMap() {
        return Map.of(
                "engine", engine,
                "format", format,
                "originalSize", originalSize,
                "compressedSize", (long) data.length,
                "savedRatio", savedRatio(),
                "cost", cost,
                "discarded", discarded,
                "width", (long) sourceSize,
                "height", (long) sourceHeight);
    }
}