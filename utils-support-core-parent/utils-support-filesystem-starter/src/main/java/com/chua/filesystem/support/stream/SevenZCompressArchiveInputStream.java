package com.chua.filesystem.support.stream;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.io.file.stream.ArchiveInputStream;
import com.chua.common.support.io.file.stream.CompressArchiveInputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;

/**
 * 7Z格式归档输入流提供者
 * <p>
 * 注意：7Z格式需要随机访问，因此需要文件对象而不是输入流
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("7z")
public class SevenZCompressArchiveInputStream implements CompressArchiveInputStream {

    @Override
    /** 是否支持 */
    public boolean isSupport(File file) {
        if (file == null) {
            return false;
        }
        String fileName = file.getName().toLowerCase();
        return fileName.endsWith(".7z");
    }

    @Override
    /** 创建输入流 */
    public ArchiveInputStream createInputStream(InputStream inputStream, File file, @Nullable char[] password) throws IOException {
 // 7Z格式需要随机访问，必须使用文件对象
        if (file == null) {
            throw new IOException("7Z格式需要File对象，不能使用InputStream");
        }
        var sevenZFile = new SevenZFile(file);
        return new SevenZArchiveInputStreamAdapter(sevenZFile);
    }

    @Override
    /** 获取格式化名称 */
    public String getFormatName() {
        return "7z";
    }
}
