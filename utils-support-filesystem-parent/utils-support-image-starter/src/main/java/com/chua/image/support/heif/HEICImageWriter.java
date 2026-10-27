package com.chua.image.support.heif;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.IIOException;
import javax.imageio.ImageWriteParam;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

/**
 * HEIC/HEIF 图像写入器（纯Java实现）。
 *
 * @author CH
 * @since 4.0.0.42
 * @param type 类型
 * @return 能否encode镜像的结果
 * @param param 参数
 * @param metadata metadata
 * @param spi spi
 */
public class HEICImageWriter extends ImageWriter {

    private ImageOutputStream output; // 输出

    /**
     * heic镜像writer。
     * @param spi spi
     */
    public HEICImageWriter(HEICImageWriterSpi spi) {
        super(spi);
    }

    @Override
    public void setOutput(Object output) {
        this.output = (ImageOutputStream) output;
    }

    @Override
    public void prepareWriteEmpty(IIOMetadata streamMetadata, ImageTypeSpecifier type,
                                   int minWidth, int minHeight, IIOMetadata imgMeta,
                                   List<? extends BufferedImage> thumbnails, ImageWriteParam param) throws IOException {
        throw new UnsupportedOperationException("Not supported");
    }

    /**
     * 写入 HEIC 图像。
     *
     * @param metadata 元数据
     * @param image    待写入图像
     * @param param    写入参数
     * @throws IOException 写出失败时抛出
     */
    @Override
    public void write(IIOMetadata metadata, javax.imageio.IIOImage image, ImageWriteParam param) throws IOException {
        if (output == null) {
            throw new IOException("Output not set");
        }
        java.awt.image.RenderedImage ri = image.getRenderedImage();
        if (!(ri instanceof BufferedImage)) {
            throw new IOException("Only BufferedImage supported");
        }
        BufferedImage img = (BufferedImage) ri;
        try {
            HeifNativeEncoder.encode(img, output);
        } catch (Exception e) {
            throw new IIOException("HEIC encode failed", e);
        }
    }

    /**
     * 转换图像元数据。
     *
     * @param metadata 元数据
     * @param type     图像类型
     * @param param    写入参数
     * @return 原样返回的元数据
     */
    @Override
    public IIOMetadata convertImageMetadata(IIOMetadata metadata, ImageTypeSpecifier type, ImageWriteParam param) {
        return metadata;
    }

    /**
     * 转换流元数据。
     *
     * @param metadata 元数据
     * @param param    写入参数
     * @return 原样返回的元数据
     */
    @Override
    public IIOMetadata convertStreamMetadata(IIOMetadata metadata, ImageWriteParam param) {
        return metadata;
    }

    /**
     * 获取默认流元数据。
     *
     * @param param 写入参数
     * @return 当前实现不支持，固定返回 null
     */
    @Override
    public IIOMetadata getDefaultStreamMetadata(ImageWriteParam param) {
        return null;
    }

    /**
     * 获取默认图像元数据。
     *
     * @param type  图像类型
     * @param param 写入参数
     * @return 当前实现不支持，固定返回 null
     */
    @Override
    public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier type, ImageWriteParam param) {
        return null;
    }

    /**
     * 获取ImageTypes。
     *
     * @param index 索引，不允许为 null
     * @return 迭代器 对象
     * @throws IOException 当执行过程不满足前置条件时
     */
    public Iterator<ImageTypeSpecifier> getImageTypes(int index) throws IOException {
        return null;
    }

    /**
     * 能否编码Image。
     *
     * @param type 类型，不允许为 null
     * @return 是否成功（true 表示成功）
     */
    public boolean canEncodeImage(ImageTypeSpecifier type) {
        return type != null && (type.getColorModel().getNumComponents() == 3 ||
                type.getColorModel().getNumComponents() == 4);
    }
}
