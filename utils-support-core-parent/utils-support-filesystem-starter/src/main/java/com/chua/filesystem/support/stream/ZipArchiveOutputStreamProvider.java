package com.chua.filesystem.support.stream;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.io.file.stream.CompressArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import javax.annotation.Nullable;

/**
 * ZIP格式归档输出流提供者
 *
 * @author CH
 */
@Spi("zip")
public class ZipArchiveOutputStreamProvider implements CompressArchiveOutputStream {

    @Override
    public boolean isSupport(File file) {
        if (file == null) {
            return false;
        }
        String fileName = file.getName().toLowerCase();
        return fileName.endsWith(".zip");
    }

    @Override
    public Object createOutputStream(OutputStream outputStream, File file, @Nullable char[] password) throws IOException {
        return new ZipArchiveOutputStream(outputStream);
    }

    @Override
    public String getFormatName() {
        return "zip";
    }
}
