package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;

/**
* 文件扩展名 Mock 生成器
*
* <p>从常见文件扩展名池中随机返回一个（不含点号），
* 如 {@code json}、{@code mp4}。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"file-ext", "file-extension", "extension"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class FileExtMockString implements MockString {

    /**
    * 文件扩展名池
    * @param environment 环境
    * @return 获取字符串的结果
    */
    private static final String[] EXTENSIONS = {
            "txt", "md", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv",
            "json", "xml", "html", "css", "js", "ts", "java", "py", "go", "c", "cpp",
            "zip", "tar", "gz", "jpg", "jpeg", "png", "gif", "bmp", "svg", "ico",
            "mp3", "wav", "mp4", "avi", "mov", "sql", "log", "yml", "yaml", "properties",
            "exe", "apk", "jar", "war", "sh", "bat"
    };

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        return environment.randomOf(EXTENSIONS);
    }
}
