package com.chua.filesystem.support.stream;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.io.file.stream.CompressInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nonnull;

/**
 * GZIP压缩输入流提供者
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("gz")
public class GzipCompressInputStreamProvider implements CompressInputStream {

    @Override
    public boolean isSupport(@Nonnull File file) {
        if (file == null) {
            return false;
        }
        String fileName = file.getName().toLowerCase();
        // 支持 .gz 和 .gzip，但不包括 .tar.gz 和 .tgz（这些应该走归档流程）
        return (fileName.endsWith(".gz") || fileName.endsWith(".gzip"))
                && !fileName.endsWith(".tar.gz")
                && !fileName.endsWith(".tgz");
    }

    @Override
    public Object createInputStream(@Nonnull InputStream inputStream, @Nonnull File file) throws IOException {
        return new GzipCompressorInputStream(inputStream);
    }

    @Override
    public String getFormatName() {
        return "gz";
    }
}
