package com.chua.filesystem.support.stream;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.io.file.stream.ArchiveInputStream;
import com.chua.common.support.io.file.stream.CompressArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;

/**
 * 焦油.BZ2格式归档输入流提供者
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"tar.bz2", "tbz2", "tbz"})
public class TarBz2CompressArchiveInputStream implements CompressArchiveInputStream {

    /**
     * 是否支持
    */
    @Override
    public boolean isSupport(File file) {
        if (file == null) {
            return false;
        }
        String fileName = file.getName().toLowerCase();
        return fileName.endsWith(".tar.bz2") || fileName.endsWith(".tbz2") || fileName.endsWith(".tbz");
    }

    /**
     * 创建输入流
    */
    @Override
    public ArchiveInputStream createInputStream(InputStream inputStream, File file,
                                                @Nullable char[] password) throws IOException {
        var bzip2InputStream = new BZip2CompressorInputStream(inputStream);
        var tarInputStream = new TarArchiveInputStream(bzip2InputStream);
        return new ArchiveInputStreamAdapter(tarInputStream);
    }

    /**
     * 获取格式化名称
    */
    @Override
    public String getFormatName() {
        return "tar.bz2";
    }
}
