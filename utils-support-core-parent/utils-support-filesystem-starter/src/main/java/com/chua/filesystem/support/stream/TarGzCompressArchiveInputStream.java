package com.chua.filesystem.support.stream;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.io.file.stream.ArchiveInputStream;
import com.chua.common.support.io.file.stream.CompressArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;

/**
   * 焦油.GZ格式归档输入流提供者
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"tar.gz", "tgz"})
public class TarGzCompressArchiveInputStream implements CompressArchiveInputStream {

    @Override
    /** 是否支持 */
    public boolean isSupport(File file) {
        if (file == null) {
            return false;
        }
        String fileName = file.getName().toLowerCase();
        return fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz");
    }

    @Override
    /** 创建输入流 */
    public ArchiveInputStream createInputStream(InputStream inputStream, File file, @Nullable char[] password) throws IOException {
        var gzipInputStream = new GzipCompressorInputStream(inputStream);
        var tarInputStream = new TarArchiveInputStream(gzipInputStream);
        return new ArchiveInputStreamAdapter(tarInputStream);
    }

    @Override
    /** 获取格式化名称 */
    public String getFormatName() {
        return "tar.gz";
    }
}
