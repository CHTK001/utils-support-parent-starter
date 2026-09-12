package com.chua.filesystem.support.stream;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.io.file.stream.CompressArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import javax.annotation.Nullable;

/**
* 焦油格式归档输出流提供者
*
* @author CH
* @since 4.0.0.42
 */
@Spi("tar")
public class TarArchiveOutputStreamProvider implements CompressArchiveOutputStream {

    @Override
    /** 是否支持 */
    public boolean isSupport(File file) {
        if (file == null) {
            return false;
        }
        String fileName = file.getName().toLowerCase();
        return fileName.endsWith(".tar") && !fileName.endsWith(".tar.gz") && !fileName.endsWith(".tgz");
    }

    @Override
    /** 创建输出流 */
    public Object createOutputStream(OutputStream outputStream, File file, @Nullable char[] password) throws IOException {
        return new TarArchiveOutputStream(outputStream);
    }

    @Override
    /** 获取格式化名称 */
    public String getFormatName() {
        return "tar";
    }
}
