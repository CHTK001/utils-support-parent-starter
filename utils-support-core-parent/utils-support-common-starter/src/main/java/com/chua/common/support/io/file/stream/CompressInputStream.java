package com.chua.common.support.io.file.stream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import org.jspecify.annotations.NullUnmarked;

/**
 * 压缩输入流提供者接口。
 *
 * <p>用于提供解压缩输入流，适用于 gz、bz2 等单文件压缩格式。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
public interface CompressInputStream {

    /**
     * 判断是否支持处理指定文件。
     *
     * @param file 待处理的文件
     * @return true 表示支持
     */
    boolean isSupport(File file);

    /**
     * 创建解压缩输入流。
     *
     * @param inputStream 原始输入流
     * @param file        原始文件
     * @return 解压缩后的输入流
     * @throws IOException IO 异常
     */
    Object createInputStream(InputStream inputStream, File file) throws IOException;

    /**
     * 获取压缩格式名称。
     *
     * @return 格式名称
     */
    String getFormatName();
}