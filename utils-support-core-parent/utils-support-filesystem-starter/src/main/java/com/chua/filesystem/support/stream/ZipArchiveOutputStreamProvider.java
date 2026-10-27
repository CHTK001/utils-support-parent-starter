package com.chua.filesystem.support.stream;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.io.file.stream.CompressArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import javax.annotation.Nullable;

/**
 * 压缩格式归档输出流提供者
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("zip")
public class ZipArchiveOutputStreamProvider implements CompressArchiveOutputStream {

    /**
     * 是否支持
    */
    @Override
    public boolean isSupport(File file) {
        if (file == null) {
            return false;
        }
        String fileName = file.getName().toLowerCase();
        return fileName.endsWith(".zip");
    }

    /**
     * 创建输出流
    */
    @Override
    public Object createOutputStream(OutputStream outputStream, File file, @Nullable char[] password) throws IOException {
        return new ZipArchiveOutputStream(outputStream);
    }

    /**
     * 获取格式化名称
    */
    @Override
    public String getFormatName() {
        return "zip";
    }
}
