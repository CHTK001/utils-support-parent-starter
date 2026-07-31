package com.chua.common.support.io.file.stream;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 归档压缩输出流提供者接口。
 *
 * <p>用于提供归档压缩文件的输出流，支持 tar、tar.gz、zip 等归档格式。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface CompressArchiveOutputStream {

    /**
     * 判断是否支持处理指定文件。
     *
     * @param file 待处理的文件
     * @return true 表示支持
     */
    boolean isSupport(File file);

    /**
     * 创建归档输出流。
     *
     * @param outputStream 目标输出流
     * @param file         原始文件
     * @param password     加密密码（可为 null）
     * @return 归档输出流
     * @throws IOException IO 异常
     */
    Object createOutputStream(OutputStream outputStream, File file, char[] password) throws IOException;

    /**
     * 获取归档格式名称。
     *
     * @return 格式名称
     */
    String getFormatName();
}