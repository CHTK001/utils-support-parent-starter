package com.chua.filesystem.support.stream;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.io.file.stream.ArchiveInputStream;
import com.chua.common.support.io.file.stream.CompressArchiveInputStream;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;

/**
 * AR格式归档输入流提供者
 * <p>
 * AR（Archive）格式通常用于Unix/Linux静态库文件（.a文件）
 * </p>
 *
 * @author CH
 */
@Spi("ar")
public class ArCompressArchiveInputStream implements CompressArchiveInputStream {

    @Override
    public boolean isSupport(File file) {
        if (file == null) {
            return false;
        }
        String fileName = file.getName().toLowerCase();
        return fileName.endsWith(".ar") || fileName.endsWith(".a");
    }

    @Override
    public ArchiveInputStream createInputStream(InputStream inputStream, File file, @Nullable char[] password) throws IOException {
        var arInputStream = new ArArchiveInputStream(inputStream);
        return new ArchiveInputStreamAdapter(arInputStream);
    }

    @Override
    public String getFormatName() {
        return "ar";
    }
}
