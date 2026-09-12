package com.chua.filesystem.support.stream;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.io.file.stream.ArchiveInputStream;
import com.chua.common.support.io.file.stream.CompressArchiveInputStream;
import org.apache.commons.compress.archivers.jar.JarArchiveInputStream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;

/**
* JAR格式归档输入流提供者
* <p>
* JAR（Java Arch Linux Linux）格式基于压缩格式，用于Java应用程序打包
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("jar")
public class JarCompressArchiveInputStream implements CompressArchiveInputStream {

    @Override
    /** 是否支持 */
    public boolean isSupport(File file) {
        if (file == null) {
            return false;
        }
        String fileName = file.getName().toLowerCase();
        return fileName.endsWith(".jar");
    }

    @Override
    /** 创建输入流 */
    public ArchiveInputStream createInputStream(InputStream inputStream, File file, @Nullable char[] password) throws IOException {
        var jarInputStream = new JarArchiveInputStream(inputStream);
        return new ArchiveInputStreamAdapter(jarInputStream);
    }

    @Override
    /** 获取格式化名称 */
    public String getFormatName() {
        return "jar";
    }
}
