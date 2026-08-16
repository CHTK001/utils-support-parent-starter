package com.chua.filesystem.support.stream;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.io.file.stream.CompressArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import javax.annotation.Nullable;

/**
 * TAR.GZ格式归档输出流提供者
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"tar.gz", "tgz"})
public class TarGzArchiveOutputStreamProvider implements CompressArchiveOutputStream {

    @Override
    public boolean isSupport(File file) {
        if (file == null) {
            return false;
        }
        String fileName = file.getName().toLowerCase();
        return fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz");
    }

    @Override
    public Object createOutputStream(OutputStream outputStream, File file, @Nullable char[] password) throws IOException {
        var gzipOutputStream = new GzipCompressorOutputStream(outputStream);
        return new TarArchiveOutputStream(gzipOutputStream);
    }

    @Override
    public String getFormatName() {
        return "tar.gz";
    }
}
