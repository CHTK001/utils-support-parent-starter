package com.chua.hprof.support.converter;

import com.chua.common.support.file.converter.ConvertSetting;
import com.chua.common.support.file.converter.FileConvertSystem;
import com.chua.common.support.file.converter.FileSource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.hprof.support.parser.HprofParser;
import com.chua.hprof.support.serializer.HprofToMarkdownSerializer;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * HPROF 转 Markdown 的文件转换器。
 *
 * <p>解析二进制 hprof 堆转储，并渲染为便于人和 AI 阅读的
 * Markdown 报告，内容包含类直方图与泄漏可疑对象。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("hprof2markdown")
public class HprofToMarkdownFileConvertSystem implements FileConvertSystem {

    /**
     * Source file format identifier.
     */
    private static final String SOURCE_TYPE = "hprof";

    /**
     * Target file format identifier.
     */
    private static final String TARGET_TYPE = "markdown";

    @Override
    /**
     * Whether supported
    */
    public boolean isSupported(String sourceType, String targetType) {
        return SOURCE_TYPE.equalsIgnoreCase(sourceType)
                && TARGET_TYPE.equalsIgnoreCase(targetType);
    }

    @Override
    /**
     * Convert
    */
    public void convert(FileSource source, FileSource target, ConvertSetting setting) {
        try {
            HprofParser.Result result = HprofParser.parse(toInputStream(source), source.getPath());
            String markdown = HprofToMarkdownSerializer.serialize(result, source.getPath());
            write(target, markdown, setting);
            log.info("hprof to markdown converted: {} -> {}", source.getPath(), target.getPath());
        } catch (Exception e) {
            throw new RuntimeException("HPROF to Markdown conversion failed", e);
        }
    }

    /**
     * Resolve the source to an input stream.
     *
     * @param source source file descriptor
     * @return input stream
     * @throws IOException 无法读取源文件时
     */
    private static java.io.InputStream toInputStream(FileSource source) throws IOException {
        if (source.isPath()) {
            return Files.newInputStream(Path.of(source.getPath()));
        }
        if (source.isUrl()) {
            return source.getUrl().openStream();
        }
        if (source.isInputStream()) {
            return source.getInputStream();
        }
        throw new IOException("unsupported hprof source: " + source);
    }

    /**
     * Write the generated content to the target.
     *
     * @param target  target file descriptor
     * @param content content text
     * @param setting conversion settings
     * @throws IOException 无法写入目标文件时
     */
    private static void write(FileSource target, String content, ConvertSetting setting) throws IOException {
        if (target.isPath()) {
            File file = new File(target.getPath());
            if (!setting.isOverwrite() && file.exists()) {
                throw new IOException("target exists and overwrite disabled: " + file);
            }
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                log.warn("failed to create target directory: {}", parent);
            }
            Files.writeString(file.toPath(), content);
            return;
        }
        if (target.isOutputStream()) {
            target.getOutputStream().write(content.getBytes());
            return;
        }
        throw new IOException("unsupported hprof target: " + target);
    }
}
