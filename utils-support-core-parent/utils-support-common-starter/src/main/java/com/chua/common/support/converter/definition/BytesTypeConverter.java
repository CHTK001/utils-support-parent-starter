package com.chua.common.support.converter.definition;

import com.chua.common.support.utils.ArrayUtils;
import com.chua.common.support.utils.FileUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;

import static com.chua.common.support.constant.CommonConstant.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * byte[] 类型转换器（替代实现，支持数组格式字符串解析）。
 * <p>将各种类型的值转换为 {@code byte[]}，支持以下输入类型：</p>
 * <ul>
 *   <li>{@link String} — 支持 JSON 数组格式（[1,2,3]）和文件路径读取</li>
 *   <li>数组 / 集合类型 — 通过 ArrayUtils.transToByteArray 转换</li>
 * </ul>
 *
 * @author CH
 * @version 1.0.0
 */
public class BytesTypeConverter implements TypeConverter<byte[]> {


    /**
     * 将给定值转换为 byte[]。
     *
     * @param value 源值
     * @return byte[] 值，如果为 null 则返回空数组
     */
    @Override
    public byte[] convert(Object value) {
        if (null == value) {
            return new byte[0];
        }

        if(value instanceof String) {
            String stringValue = value.toString();
            if (stringValue.startsWith(SYMBOL_LEFT_SQUARE_BRACKET) && stringValue.endsWith(SYMBOL_RIGHT_SQUARE_BRACKET)) {
                stringValue = stringValue.substring(1, stringValue.length() - 1);
                return ArrayUtils.transToByteArray(stringValue.split(SYMBOL_COMMA));
            }

            File file = new File(stringValue);
            if(file.exists()) {
                try {
                    return readBytes(Files.newInputStream(file.toPath()));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            try {
                URL url = new URL(stringValue);
                try (InputStream is = url.openStream()) {
                    return readBytes(is);
                }
            } catch (Exception ignored) {
            }
        }

        byte[] bytes = ArrayUtils.transToByteArray(value);
        if (bytes.length != 0) {
            return bytes;
        }

        return new byte[0];
    }

    /**
     * 从 InputStream 中读取全部字节。
     *
     * @param is 输入流
     * @return 字节数组
     * @throws IOException 读取异常
     */
    private byte[] readBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return baos.toByteArray();
    }

    /**
     * 获取当前转换器支持的目标类型。
     *
     * @return byte[].class
     */
    @Override
    public Class<byte[]> getType() {
        return byte[].class;
    }

}
