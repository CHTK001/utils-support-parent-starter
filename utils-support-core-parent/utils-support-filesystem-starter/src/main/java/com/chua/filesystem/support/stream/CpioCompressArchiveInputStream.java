package com.chua.filesystem.support.stream;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.io.file.stream.ArchiveInputStream;
import com.chua.common.support.io.file.stream.CompressArchiveInputStream;
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;

/**
 * CPIO格式归档输入流提供者
 * <p>
 * CPIO（Copy In, Copy Out）格式通常用于Unix/Linux系统
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("cpio")
public class CpioCompressArchiveInputStream implements CompressArchiveInputStream {

    @Override
    public boolean isSupport(File file) {
        if (file == null) {
            return false;
        }
        String fileName = file.getName().toLowerCase();
        return fileName.endsWith(".cpio");
    }

    @Override
    public ArchiveInputStream createInputStream(InputStream inputStream, File file, @Nullable char[] password) throws IOException {
        var cpioInputStream = new CpioArchiveInputStream(inputStream);
        return new ArchiveInputStreamAdapter(cpioInputStream);
    }

    @Override
    public String getFormatName() {
        return "cpio";
    }
}
