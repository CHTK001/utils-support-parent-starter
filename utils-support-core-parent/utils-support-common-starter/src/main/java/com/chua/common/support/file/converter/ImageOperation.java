package com.chua.common.support.file.converter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import reactor.core.publisher.Mono;

/**
* 图像操作 SPI 接口。
*
* @author CH
* @since 4.0.0.42
 */
public interface ImageOperation {

    /**
    * 将给定图像字节调整大小到指定尺寸或缩放比例。
    *
    * @param imageBytes 输入图像数据，以字节数组形式提供。
    * @param format     图像格式（例如："png", "jpg"）。
    * @param width      目标宽度，如果使用缩放则为 null。
    * @param height     目标高度，如果使用缩放则为 null。
    * @param scale      缩放因子，如果使用显式尺寸则为 null。
    * @return 发出调整大小后图像字节的 Mono。
     */
    Mono<byte[]> resize(byte[] imageBytes, String format, Integer width, Integer height, Double scale);

    /**
    * 将给定图像字节裁剪到特定区域。
    *
    * @param imageBytes 输入图像数据，以字节数组形式提供。
    * @param format     图像格式（例如："png", "jpg"）。
    * @param x          裁剪区域左上角的 X 坐标。
    * @param y          裁剪区域左上角的 Y 坐标。
    * @param width      裁剪区域的宽度。
    * @param height     裁剪区域的高度。
    * @return 发出裁剪后图像字节的 Mono。
     */
    Mono<byte[]> crop(byte[] imageBytes, String format, int x, int y, int width, int height);

    /**
    * 将给定图像字节按指定角度旋转。
    *
    * @param imageBytes 输入图像数据，以字节数组形式提供。
    * @param format     图像格式（例如："png", "jpg"）。
    * @param angle      旋转角度（单位：度）。
    * @return 发出旋转后图像字节的 Mono。
     */
    Mono<byte[]> rotate(byte[] imageBytes, String format, int angle);

    /**
    * 对给定图像字节应用滤镜。
    *
    * @param imageBytes 输入图像数据，以字节数组形式提供。
    * @param format     图像格式（例如："png", "jpg"）。
    * @param filterType 要应用的滤镜类型（例如："blur", "sharpen"）。
    * @param params     特定于滤镜类型的参数。
    * @return 发出经过滤镜处理后的图像字节的 Mono。
     */
    Mono<byte[]> filter(byte[] imageBytes, String format, String filterType, FilterParams params);

    /**
    * 压缩给定图像字节并保存到文件。
    *
    * @param imageBytes   输入图像数据，以字节数组形式提供。
    * @param format       源图像格式（例如："png", "jpg"）。
    * @param quality      压缩质量（0.0f 到 1.0f）。
    * @param outputFormat 目标图像格式（例如："jpg", "png"）。
    * @param output       目标文件路径。
    * @return 压缩完成时完成的 Mono。
     */
    Mono<Void> compress(byte[] imageBytes, String format, float quality, String outputFormat, java.io.File output);

    /**
    * 向给定图像字节添加水印。
    *
    * @param imageBytes 输入图像数据，以字节数组形式提供。
    * @param format     图像格式（例如："png", "jpg"）。
    * @param params     水印操作的参数。
    * @return 发出带水印图像字节的 Mono。
     */
    Mono<byte[]> watermark(byte[] imageBytes, String format, WatermarkParams params);

    /**
    * 滤镜操作参数。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class FilterParams {

        /**
        * 模糊/锐化滤镜的半径。
         */
        private Float radius;

        /**
        * 亮度调整值。
         */
        private Float brightness;
    }

    /**
    * 水印操作参数。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class WatermarkParams {

        /**
        * 包含水印图像的字节数组。
         */
        private byte[] watermarkImageBytes;

        /**
        * 水印图像的格式（例如："png", "jpg"）。
         */
        private String watermarkFormat;

        /**
        * 基于文本的水印内容。
         */
        private String text;

        /**
        * 文本水印的字体大小。
         */
        private Integer fontSize;

        /**
        * 文本水印的字体颜色。
         */
        private java.awt.Color fontColor;

        /**
        * 水印的不透明度级别（0.0f 到 1.0f）。
         */
        private Float opacity;

        /**
        * 水印的旋转角度。
         */
        private Integer rotation;

        /**
        * 位置字符串（例如："top-left", "center"）。
         */
        private String position;

        /**
        * 相对于指定位置的水平偏移量。
         */
        private Integer offsetX;

        /**
        * 相对于指定位置的垂直偏移量。
         */
        private Integer offsetY;
    }
}
