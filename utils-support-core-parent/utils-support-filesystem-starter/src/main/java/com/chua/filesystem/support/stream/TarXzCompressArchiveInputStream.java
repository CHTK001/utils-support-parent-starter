package com.chua.filesystem.support.stream;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.io.file.stream.ArchiveInputStream;
import com.chua.common.support.io.file.stream.CompressArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;

/**
 * 焦油.XZ格式归档输入流提供者
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"tar.xz", "txz"})
public class TarXzCompressArchiveInputStream implements CompressArchiveInputStream {

    @Override
    /** 是否支持 */
    public boolean isSupport(File file) {
        if (file == null) {
            return false;
        }
        String fileName = file.getName().toLowerCase();
        return fileName.endsWith(".tar.xz") || fileName.endsWith(".txz");
    }

    @Override
    /** 创建输入流 */
    public ArchiveInputStream createInputStream(InputStream inputStream, File file, @Nullable char[] password) throws IOException {
        var xzInputStream = new XZCompressorInputStream(inputStream);
        var tarInputStream = new TarArchiveInputStream(xzInputStream);
        return new ArchiveInputStreamAdapter(tarInputStream);
    }

    @Override
    /** 获取格式化名称 */
    public String getFormatName() {
        return "tar.xz";
    }
}
