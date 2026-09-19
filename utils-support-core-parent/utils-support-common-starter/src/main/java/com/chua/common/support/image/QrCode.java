package com.chua.common.support.image;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 二维码/条形码编解码接口。
 * <p>实现类通过 {@code @Spi} 注册，支持多种格式。</p>
 *
 * @author CH
 * @since 2026-07-16
 */
public interface QrCode {

    /**
     * 编码类型：二维码 / 条形码
     */
    enum BarcodeType {
        /**
         * 二维码（QR Code）
        */
        QR_CODE,
        /**
         * 条形码
        */
        BARCODE
    }

    /**
     * 生成二维码/条形码
     *
     * @param content  编码内容
     * @param type     码类型
     * @param width    宽度（像素）
     * @param height   高度（像素）
     * @param format   图片格式（如 png、jpg）
     * @param output   输出文件
     * @throws Exception 生成失败
     */
    void encode(String content, BarcodeType type, int width, int height,
                String format, File output) throws Exception;

    /**
     * 生成二维码/条形码到输出流
     *
     * @param content  编码内容
     * @param type     码类型
     * @param width    宽度（像素）
     * @param height   高度（像素）
     * @param format   图片格式（如 png、jpg）
     * @param output   输出流
     * @throws Exception 生成失败
     */
    void encode(String content, BarcodeType type, int width, int height,
                String format, OutputStream output) throws Exception;

    /**
     * 解码二维码/条形码
     *
     * @param input 图片输入流
     * @return 解码内容
     * @throws Exception 解码失败
     */
    String decode(InputStream input) throws Exception;

    /**
     * 解码二维码/条形码
     *
     * @param file 图片文件
     * @return 解码内容
     * @throws Exception 解码失败
     */
    String decode(File file) throws Exception;
}
