package com.chua.hprof.support;

import com.chua.hprof.support.parser.HprofParser;
import com.chua.hprof.support.serializer.HprofToJsonSerializer;
import com.chua.hprof.support.serializer.HprofToHtmlSerializer;
import com.chua.hprof.support.serializer.HprofToMarkdownSerializer;

import java.io.File;
import java.io.IOException;

/**
 * HPROF heap dump module facade.
 *
 * <p>Provides a one-call entry point to turn a binary hprof heap dump into
 * structured Java objects and the two report formats (JSON / Markdown) that
 * feed LLM analysis:</p>
 *
 * <pre>
 * hprof binary
 *   | Java parse (hprof-parser)
 * Java objects / structured data
 *   | serialize (Jackson / hand written template)
 * JSON / Markdown
 *   | hand to AI
 * AI gives plain language conclusions
 * </pre>
 *
 * <p>JSON output shape:</p>
 * <pre>
 * {
 *   "leak_suspects": [
 *     {
 *       "class": "java.util.HashMap",
 *       "retained_size": "1.2GB",
 *       "gc_root": "static OrderCache.cache"
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>Markdown output shape:</p>
 * <pre>
 * | Class | Instance Count | Memory Used | Reference Chain |
 * |-------|----------------|-------------|-----------------|
 * | HashMap | 1 | 1.2 GB | static OrderCache.cache |
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class HprofSupport {

    /**
    * HPROF source format identifier.
    */
    public static final String SOURCE_HPROF = "hprof";

    /**
    * JSON target format identifier.
    */
    public static final String TARGET_JSON = "json";

    /**
    * Markdown target format identifier.
    */
    public static final String TARGET_MARKDOWN = "markdown";

    /**
    * HTML target format identifier.
    */
    public static final String TARGET_HTML = "html";

    private HprofSupport() {
    }

    /**
    * Parse an hprof file into structured data.
    *
    * @param file hprof binary file
    * @return parsed result (objects, class histogram, top retained)
    * @throws IOException when the file cannot be read
    */
    public static HprofParser.Result parse(File file) throws IOException {
        return HprofParser.parse(file);
    }

    /**
    * Convert an hprof file to the JSON document.
    *
    * @param file hprof binary file
    * @return JSON string
    * @throws IOException when the file cannot be read
    */
    public static String toJson(File file) throws IOException {
        HprofParser.Result result = HprofParser.parse(file);
        return HprofToJsonSerializer.serialize(result, file.getName());
    }

    /**
    * Convert an hprof file to a Markdown report.
    *
    * @param file hprof binary file
    * @return Markdown string
    * @throws IOException when the file cannot be read
    */
    public static String toMarkdown(File file) throws IOException {
        HprofParser.Result result = HprofParser.parse(file);
        return HprofToMarkdownSerializer.serialize(result, file.getName());
    }

    /**
    * Convert an hprof file to a self-contained HTML report (charts + findings).
    *
    * @param file hprof binary file
    * @return HTML document
    * @throws IOException when the file cannot be read
    */
    public static String toHtml(File file) throws IOException {
        HprofParser.Result result = HprofParser.parse(file);
        return HprofToHtmlSerializer.serialize(result, file.getName());
    }

    /**
    * Convert an hprof file to a self-contained HTML report with an AI summary block.
    *
    * @param file hprof binary file
    * @param summarizer AI summarizer (null disables the AI block)
    * @return HTML document
    * @throws IOException when the file cannot be read
    */
    public static String toHtml(File file, com.chua.hprof.support.ai.HprofAiSummarizer summarizer) throws IOException {
        HprofParser.Result result = HprofParser.parse(file);
        String aiSummary = summarizer == null ? null : summarizer.summarize(result);
        return HprofToHtmlSerializer.serialize(result, file.getName(), aiSummary);
    }
}
