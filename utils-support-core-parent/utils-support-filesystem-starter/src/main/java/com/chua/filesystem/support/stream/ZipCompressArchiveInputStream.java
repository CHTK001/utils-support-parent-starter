package com.chua.filesystem.support.stream;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.io.file.stream.ArchiveInputStream;
import com.chua.common.support.io.file.stream.CompressArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;

/**
 * 压缩格式归档输入流提供者
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("zip")
public class ZipCompressArchiveInputStream implements CompressArchiveInputStream {

    @Override
    /** 是否支持 */
    public boolean isSupport(File file) {
        if (file == null) {
            return false;
        }
        String fileName = file.getName().toLowerCase();
        return fileName.endsWith(".zip");
    }

    @Override
    /** 创建输入流 */
    public ArchiveInputStream createInputStream(InputStream inputStream, File file, @Nullable char[] password) throws IOException {
        var zipInputStream = new ZipArchiveInputStream(inputStream);
        return new ArchiveInputStreamAdapter(zipInputStream);
    }

    @Override
    /** 获取格式化名称 */
    public String getFormatName() {
        return "zip";
    }
}
