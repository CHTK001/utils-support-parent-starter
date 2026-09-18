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
 * HPROF to HTML file converter.
 *
 * <p>Parses a binary hprof heap dump and renders a self-contained HTML
 * report with ECharts visualisations (retained / instance-count ranking,
 * GC-root pie), KPI cards, the algorithmic leak findings and the
 * plain-language conclusions. The output is a single HTML file: inline
 * CSS / JS, ECharts loaded from CDN.</p>
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
    * Optional AI summarizer. When null, the HTML report omits the AI block.
    */
    private volatile HprofAiSummarizer aiSummarizer;

    /**
    * Default no-arg constructor (no AI summary).
    */
    public HprofToHtmlFileConvertSystem() {
    }

    /**
    * Create a converter that embeds an AI summary block.
    *
    * @param aiSummarizer summarizer, null disables the AI block
    */
    public HprofToHtmlFileConvertSystem(HprofAiSummarizer aiSummarizer) {
        this.aiSummarizer = aiSummarizer;
    }

    /**
    * Set the AI summarizer to embed into the HTML report.
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
    * @throws IOException when the source cannot be read
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
    * @throws IOException when the target cannot be written
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
