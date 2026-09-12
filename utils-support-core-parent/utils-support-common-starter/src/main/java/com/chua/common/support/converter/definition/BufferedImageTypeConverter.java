package com.chua.common.support.converter.definition;

import com.chua.common.support.utils.ClassUtils;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* BufferedImage 类型转换器。
* <p>将各种类型的值转换为 {@link BufferedImage}，支持以下输入类型：</p>
* <ul>
*   <li>{@link File} / {@link Path} / {@link String}（文件路径）— 通过 ImageIO.read 读取</li>
*   <li>{@link String}（类路径资源）— 通过 ClassUtils.getResourceUrl 获取后读取</li>
*   <li>{@link InputStream} / {@link ImageInputStream} — 通过 ImageIO.read 读取</li>
*   <li>{@link URL} — 通过 ImageIO.read 读取</li>
*   <li>{@code byte[]} / {@link ByteArrayOutputStream} — 通过 ByteArrayInputStream 读取</li>
*   <li>第三方 BufferedImage 包装类 — 通过反射调用 getBufferedImage()</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
public class BufferedImageTypeConverter implements TypeConverter<BufferedImage> {
    /** 图像预测结果类全限定名 */
    private static final String BUFFERED_IMAGE_CLASS = "com.chua.deeplearning.support.ml.BufferedImagePredictResult";

    @Override
    /** 获取Type */
    public Class<BufferedImage> getType() {
        return BufferedImage.class;
    }

    /**
    * 将给定值转换为 BufferedImage。
    *
    * @param value 源值
    * @return BufferedImage 值，如果无法转换则返回 null
     */
    @Override
    public BufferedImage convert(Object value) {
        try {
            if (value instanceof File) {
                return ImageIO.read((File) value);
            }

            if (value instanceof Path) {
                return ImageIO.read(((Path) value).toFile());
            }

            if (value instanceof String str) {
                File file = new File(str);
                if (file.exists()) {
                    return ImageIO.read(file);
                }

                // Try to get resource from classpath
                URL resourceUrl = ClassUtils.getResourceUrl(str);
                if (resourceUrl != null) {
                    return ImageIO.read(resourceUrl);
                }

                return null;
            }

            if (value instanceof InputStream) {
                return ImageIO.read((InputStream) value);
            }

            if (value instanceof ImageInputStream) {
                return ImageIO.read((ImageInputStream) value);
            }

            if (value instanceof URL) {
                return ImageIO.read((URL) value);
            }


            if (value instanceof byte[]) {
                try (ByteArrayInputStream arrayInputStream = new ByteArrayInputStream((byte[]) value)) {
                    return ImageIO.read(arrayInputStream);
                }
            }

            if (value instanceof ByteArrayOutputStream byteArrayOutputStream) {
                try (ByteArrayInputStream arrayInputStream = new ByteArrayInputStream(byteArrayOutputStream.toByteArray())) {
                    return ImageIO.read(arrayInputStream);
                }
            }

            if (ClassUtils.isPresent(BUFFERED_IMAGE_CLASS)) {
                return (BufferedImage) ClassUtils.invokeBean(value, "getBufferedImage");
            }
        } catch (IOException ignored) {
        }

        return null;
    }
}
