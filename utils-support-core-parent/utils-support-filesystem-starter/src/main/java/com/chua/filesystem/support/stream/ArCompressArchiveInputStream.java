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
 * 增强现实格式归档输入流提供者
 * <p>
 * 增强现实（Arch Linux Linux）格式通常用于Unix/Linux静态库文件（.a文件）
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("ar")
public class ArCompressArchiveInputStream implements CompressArchiveInputStream {

    /**
     * 是否支持
    */
    @Override
    public boolean isSupport(File file) {
        if (file == null) {
            return false;
        }
        String fileName = file.getName().toLowerCase();
        return fileName.endsWith(".ar") || fileName.endsWith(".a");
    }

    /**
     * 创建输入流
    */
    @Override
    public ArchiveInputStream createInputStream(InputStream inputStream, File file,
                                                @Nullable char[] password) throws IOException {
        var arInputStream = new ArArchiveInputStream(inputStream);
        return new ArchiveInputStreamAdapter(arInputStream);
    }

    /**
     * 获取格式化名称
    */
    @Override
    public String getFormatName() {
        return "ar";
    }
}
