package com.chua.filesystem.support.stream;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.io.file.stream.CompressArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import javax.annotation.Nullable;

/**
 * TAR格式归档输出流提供者
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("tar")
public class TarArchiveOutputStreamProvider implements CompressArchiveOutputStream {

    @Override
    public boolean isSupport(File file) {
        if (file == null) {
            return false;
        }
        String fileName = file.getName().toLowerCase();
        return fileName.endsWith(".tar") && !fileName.endsWith(".tar.gz") && !fileName.endsWith(".tgz");
    }

    @Override
    public Object createOutputStream(OutputStream outputStream, File file, @Nullable char[] password) throws IOException {
        return new TarArchiveOutputStream(outputStream);
    }

    @Override
    public String getFormatName() {
        return "tar";
    }
}
