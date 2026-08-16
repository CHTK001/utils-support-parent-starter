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
 * ZIP格式归档输入流提供者
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("zip")
public class ZipCompressArchiveInputStream implements CompressArchiveInputStream {

    @Override
    public boolean isSupport(File file) {
        if (file == null) {
            return false;
        }
        String fileName = file.getName().toLowerCase();
        return fileName.endsWith(".zip");
    }

    @Override
    public ArchiveInputStream createInputStream(InputStream inputStream, File file, @Nullable char[] password) throws IOException {
        var zipInputStream = new ZipArchiveInputStream(inputStream);
        return new ArchiveInputStreamAdapter(zipInputStream);
    }

    @Override
    public String getFormatName() {
        return "zip";
    }
}
