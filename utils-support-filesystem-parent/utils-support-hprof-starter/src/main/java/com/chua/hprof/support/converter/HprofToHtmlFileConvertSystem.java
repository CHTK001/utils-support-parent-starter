package com.chua.hprof.support.converter;

import com.chua.common.support.file.converter.ConvertSetting;
import com.chua.common.support.file.converter.FileConvertSystem;
import com.chua.common.support.file.converter.FileSource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.hprof.support.ai.HprofAiSummarizer;
import com.chua.hprof.support.parser.HprofParser;
import com.chua.hprof.support.serializer.HprofToHtmlSerializer;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * HPROF 转 HTML 的文件转换器。
 *
 * <p>解析二进制 hprof 堆转储，并渲染为自包含的 HTML
 * 报告，内含 ECharts 可视化（retained 大小 / 实例数排名、
 * GC-root 饼图）、KPI 卡片、算法检出的内存泄漏结论以及
 * 通俗语言的总结。输出为单个 HTML 文件：CSS / JS 内联，
 * ECharts 由 CDN 加载。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("hprof2html")
public class HprofToHtmlFileConvertSystem implements FileConvertSystem {

    /**
     * Source file format identifier.
     */
    private static final String SOURCE_TYPE = "hprof";

    /**
     * Target file format identifier.
     */
    private static final String TARGET_TYPE = "html";

    /**
     * 可选的 AI 摘要器。为 null 时，HTML 报告省略 AI 区块。
     */
    private volatile HprofAiSummarizer aiSummarizer;

    /**
     * Default no-arg constructor (no AI summary).
     */
    public HprofToHtmlFileConvertSystem() {
    }

    /**
     * 创建一个会内嵌 AI 摘要区块的转换器。
     *
     * @param aiSummarizer summarizer, null disables the AI block
     */
    public HprofToHtmlFileConvertSystem(HprofAiSummarizer aiSummarizer) {
        this.aiSummarizer = aiSummarizer;
    }

    /**
     * 设置要内嵌到 HTML 报告中的 AI 摘要器。
     *
     * @param aiSummarizer summarizer, null clears
     * @return this converter for chaining
     */
    public HprofToHtmlFileConvertSystem withAiSummarizer(HprofAiSummarizer aiSummarizer) {
        this.aiSummarizer = aiSummarizer;
        return this;
    }

    @Override
    /** Whether supported */
    public boolean isSupported(String sourceType, String targetType) {
        return SOURCE_TYPE.equalsIgnoreCase(sourceType)
                && TARGET_TYPE.equalsIgnoreCase(targetType);
    }

    @Override
    /** Convert */
    public void convert(FileSource source, FileSource target, ConvertSetting setting) {
        try {
            HprofParser.Result result = HprofParser.parse(toInputStream(source), source.getPath());
            String aiSummary = aiSummarizer == null ? null : aiSummarizer.summarize(result);
            String html = HprofToHtmlSerializer.serialize(result, source.getPath(), aiSummary);
            write(target, html, setting);
            log.info("hprof to html converted: {} -> {}", source.getPath(), target.getPath());
        } catch (Exception e) {
            throw new RuntimeException("HPROF to HTML conversion failed", e);
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
     * Write the generated HTML to the target.
     *
     * @param target  target file descriptor
     * @param content HTML text
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
