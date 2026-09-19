package com.chua.filesystem.support.stream;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.io.file.stream.ArchiveInputStream;
import com.chua.common.support.io.file.stream.CompressArchiveInputStream;
import org.apache.commons.compress.archivers.dump.DumpArchiveInputStream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;

/**
 * DUMP格式归档输入流提供者
 * <p>
 * DUMP格式通常用于Unix/Linux系统备份
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("dump")
public class DumpCompressArchiveInputStream implements CompressArchiveInputStream {

    @Override
    /**
     * 是否支持
    */
    public boolean isSupport(File file) {
        if (file == null) {
            return false;
        }
        String fileName = file.getName().toLowerCase();
        return fileName.endsWith(".dump");
    }

    @Override
    /**
     * 创建输入流
    */
    public ArchiveInputStream createInputStream(InputStream inputStream, File file, @Nullable char[] password) throws IOException {
        var dumpInputStream = new DumpArchiveInputStream(inputStream);
        return new ArchiveInputStreamAdapter(dumpInputStream);
    }

    @Override
    /**
     * 获取格式化名称
    */
    public String getFormatName() {
        return "dump";
    }
}
