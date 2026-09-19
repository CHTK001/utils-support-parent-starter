package com.chua.common.support.io.file.stream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * 归档压缩输入流提供者接口。
 *
 * <p>用于提供归档压缩文件的输入流，支持 zip、tar、7z、rar 等归档格式。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface CompressArchiveInputStream {

    /**
     * 判断是否支持处理指定文件。
     *
     * @param file 待处理的文件
     * @return true 表示支持
     */
    boolean isSupport(File file);

    /**
     * 创建归档输入流。
     *
     * @param inputStream 原始输入流
     * @param file        原始文件
     * @param password    解密密码（可为 null）
     * @return 归档输入流
     * @throws IOException IO 异常
     */
    ArchiveInputStream createInputStream(InputStream inputStream, File file, char[] password) throws IOException;

    /**
     * 获取归档格式名称。
     *
     * @return 格式名称
     */
    String getFormatName();
}
