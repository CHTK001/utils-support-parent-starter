package com.chua.hprof.support;

import com.chua.hprof.support.differ.HprofDiffer;
import com.chua.hprof.support.parser.HprofParser;
import com.chua.hprof.support.serializer.HprofToJsonSerializer;
import com.chua.hprof.support.serializer.HprofToHtmlSerializer;
import com.chua.hprof.support.serializer.HprofToMarkdownSerializer;

import java.io.File;
import java.io.IOException;

/**
 * HPROF 堆转储模块门面。
 *
 * <p>提供一次调用的入口，把二进制 hprof 堆转储转换为结构化 Java 对象，
 * 以及供 LLM 分析的两种报告格式（JSON / Markdown）：</p>
 *
 * <pre>
 * hprof 二进制
 *   | Java 解析（hprof-parser）
 * Java 对象 / 结构化数据
 *   | 序列化（Jackson / 手写模板）
 * JSON / Markdown
 *   | 交给 AI
 * AI 给出通俗语言的结论
 * </pre>
 *
 * <p>JSON 输出形态：</p>
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
 * <p>Markdown 输出形态：</p>
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
     * HPROF 源格式标识。
     */
    public static final String SOURCE_HPROF = "hprof";

    /**
     * JSON 目标格式标识。
     */
    public static final String TARGET_JSON = "json";

    /**
     * Markdown 目标格式标识。
     */
    public static final String TARGET_MARKDOWN = "markdown";

    /**
     * HTML 目标格式标识。
     */
    public static final String TARGET_HTML = "html";

    /**
     * 构造方法，创建 HprofSupport 实例。
     */
    private HprofSupport() {
    }

    /**
     * 解析 hprof 文件为结构化数据。
     *
     * @param file hprof 二进制文件
     * @return 解析结果（对象、类直方图、保留量排行）
     * @throws IOException 文件不可读时抛出
     */
    public static HprofParser.Result parse(File file) throws IOException {
        return HprofParser.parse(file);
    }

    /**
     * 把 hprof 文件转换为 JSON 文档。
     *
     * @param file hprof 二进制文件
     * @return JSON 字符串
     * @throws IOException 文件不可读时抛出
     */
    public static String toJson(File file) throws IOException {
        HprofParser.Result result = HprofParser.parse(file);
        return HprofToJsonSerializer.serialize(result, file.getName());
    }

    /**
     * 把 hprof 文件转换为 Markdown 报告。
     *
     * @param file hprof 二进制文件
     * @return Markdown 字符串
     * @throws IOException 文件不可读时抛出
     */
    public static String toMarkdown(File file) throws IOException {
        HprofParser.Result result = HprofParser.parse(file);
        return HprofToMarkdownSerializer.serialize(result, file.getName());
    }

    /**
     * 把 hprof 文件转换为自包含的 HTML 报告（图表 + 判定结论）。
     *
     * @param file hprof 二进制文件
     * @return HTML 文档
     * @throws IOException 文件不可读时抛出
     */
    public static String toHtml(File file) throws IOException {
        HprofParser.Result result = HprofParser.parse(file);
        return HprofToHtmlSerializer.serialize(result, file.getName());
    }

    /**
     * 把 hprof 文件转换为带 AI 总结区块的自包含 HTML 报告。
     *
     * @param file       hprof 二进制文件
     * @param summarizer AI 总结器（为 null 时不输出 AI 区块）
     * @return HTML 文档
     * @throws IOException 文件不可读时抛出
     */
    public static String toHtml(File file, com.chua.hprof.support.ai.HprofAiSummarizer summarizer) throws IOException {
        HprofParser.Result result = HprofParser.parse(file);
        String aiSummary = summarizer == null ? null : summarizer.summarize(result);
        return HprofToHtmlSerializer.serialize(result, file.getName(), aiSummary);
    }

    /**
     * 对比两个 hprof 文件，返回按类统计的内存增长排行。
     *
     * <p>单个转储看不出内存在如何增长——本方法对两份转储做差值比较
     * （例如 OOM 之前与紧随其后各取一份），从而让报告能点名导致崩溃的
     * 泄漏类。</p>
     *
     * @param before 较早的转储（内存占用较少）
     * @param after  较晚的转储（内存占用较多）
     * @return 差值比较结果
     * @throws IOException 任一文件不可读时抛出
     */
    public static HprofDiffer.Diff diff(File before, File after) throws IOException {
        return HprofDiffer.compareFiles(before, after);
    }
}
