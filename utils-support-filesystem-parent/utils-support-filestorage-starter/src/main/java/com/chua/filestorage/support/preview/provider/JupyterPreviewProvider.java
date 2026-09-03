package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Jupyter Notebook (IPYNB) 预览提供器。
 * <p>SPI 类型：{@code preview-jupyter}。解析 nbformat JSON 并渲染为单元格列表，
 * 支持 Markdown 与代码单元格及其执行输出。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-jupyter")
public class JupyterPreviewProvider implements FileStoragePreviewProvider {

    private static final Set<String> SUPPORTED_EXTS = Set.of("ipynb");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase(Locale.ENGLISH));
    }

    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(content);
        String title = readTitle(root);
        List<CellInfo> cells = parseCells(root);
        String html = buildHtml(title, cells, content.length);

        return PreviewResult.builder()
                .htmlContent(html)
                .build();
    }

    /**
     * 读取 Notebook 标题（首个 Markdown 一级标题或文件名占位）。
     *
     * @param root nbformat 根节点
     * @return 笔记本标题；未找到时返回默认标题
     */
    private String readTitle(JsonNode root) {
        JsonNode cells = root.path("cells");
        if (cells.isArray()) {
            for (JsonNode cell : cells) {
                if ("markdown".equals(cell.path("cell_type").asText())) {
                    String source = readSource(cell);
                    if (source != null && !source.isEmpty()) {
                        String firstLine = source.split("\r?\n", 2)[0].trim();
                        if (firstLine.startsWith("#")) {
                            return firstLine.replaceFirst("^#+\\s*", "").trim();
                        }
                    }
                }
            }
        }
        return "Jupyter Notebook";
    }

    /**
     * 解析所有单元格。
     *
     * @param root nbformat 根节点
     * @return 单元格信息列表
     */
    private List<CellInfo> parseCells(JsonNode root) {
        List<CellInfo> cells = new ArrayList<>();
        JsonNode cellNodes = root.path("cells");
        if (!cellNodes.isArray()) {
            return cells;
        }
        for (JsonNode cellNode : cellNodes) {
            String type = cellNode.path("cell_type").asText("");
            String source = readSource(cellNode);
            if (source == null) {
                continue;
            }
            List<String> outputs = new ArrayList<>();
            if ("code".equals(type)) {
                outputs.addAll(readOutputs(cellNode.path("outputs")));
            }
            int executionCount = cellNode.path("execution_count").asInt(-1);
            cells.add(new CellInfo(type, source, outputs, executionCount));
        }
        return cells;
    }

    /**
     * 读取单元格源码（source 可为字符串或字符串数组）。
     *
     * @param cell 单元格节点
     * @return 拼接后的源码；缺失时返回 null
     */
    private String readSource(JsonNode cell) {
        JsonNode sourceNode = cell.path("source");
        if (sourceNode.isMissingNode() || sourceNode.isNull()) {
            return null;
        }
        if (sourceNode.isTextual()) {
            return sourceNode.asText();
        }
        if (sourceNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode line : sourceNode) {
                if (line.isTextual()) {
                    sb.append(line.asText());
                }
            }
            return sb.toString();
        }
        return null;
    }

    /**
     * 读取代码单元格的文本形式输出。
     *
     * @param outputsNode 输出数组节点
     * @return 输出内容列表
     */
    private List<String> readOutputs(JsonNode outputsNode) {
        List<String> outputs = new ArrayList<>();
        if (!outputsNode.isArray()) {
            return outputs;
        }
        for (JsonNode output : outputsNode) {
            String type = output.path("output_type").asText("");
            switch (type) {
                case "stream":
                    appendTextOutput(outputs, output, "text");
                    break;
                case "execute_result":
                case "display_data":
                    appendDataOutput(outputs, output);
                    break;
                case "error":
                    StringBuilder err = new StringBuilder();
                    Iterator<JsonNode> traceback = output.path("traceback").elements();
                    while (traceback.hasNext()) {
                        JsonNode line = traceback.next();
                        if (line.isTextual()) {
                            err.append(line.asText()).append('\n');
                        }
                    }
                    if (err.length() > 0) {
                        outputs.add(err.toString());
                    }
                    break;
                default:
                    break;
            }
        }
        return outputs;
    }

    /**
     * 追加纯文本输出。
     *
     * @param outputs 输出容器
     * @param output  输出节点
     * @param field   文本字段名
     */
    private void appendTextOutput(List<String> outputs, JsonNode output, String field) {
        JsonNode text = output.path(field);
        if (text.isTextual()) {
            outputs.add(text.asText());
        } else if (text.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode line : text) {
                if (line.isTextual()) {
                    sb.append(line.asText()).append('\n');
                }
            }
            if (sb.length() > 0) {
                outputs.add(sb.toString());
            }
        }
    }

    /**
     * 追加 data 中的纯文本回复（优先 text/plain，无则取首个字符串类型值）。
     *
     * @param outputs 输出容器
     * @param output  输出节点
     */
    private void appendDataOutput(List<String> outputs, JsonNode output) {
        JsonNode data = output.path("data");
        if (!data.isObject()) {
            return;
        }
        JsonNode plain = data.path("text/plain");
        if (!plain.isMissingNode()) {
            appendTextOutput(outputs, plain, "value");
            if (!outputs.isEmpty()) {
                return;
            }
        }
        Iterator<Map.Entry<String, JsonNode>> fields = data.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getValue().isTextual()) {
                outputs.add(field.getValue().asText());
                return;
            }
        }
    }

    /**
     * 构建预览 HTML。
     *
     * @param title 笔记本标题
     * @param cells 单元格列表
     * @param size  文件大小
     * @return 完整 HTML
     */
    private String buildHtml(String title, List<CellInfo> cells, long size) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        sb.append("<style>");
        sb.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:20px}");
        sb.append(".header{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:20px;margin-bottom:20px}");
        sb.append("h1{margin:0 0 8px;font-size:20px;font-weight:600}");
        sb.append(".meta{color:#6b7280;font-size:13px}");
        sb.append(".notebook{max-width:860px;margin:0 auto}");
        sb.append(".cell{background:#fff;border:1px solid #e5e7eb;border-radius:10px;margin-bottom:14px;overflow:hidden}");
        sb.append(".cell-head{display:flex;align-items:center;padding:8px 16px;background:#f9fafb;border-bottom:1px solid #f0f0f0;font-size:12px;color:#6b7280}");
        sb.append(".badge{padding:2px 8px;border-radius:4px;font-size:12px;font-weight:600;margin-right:8px}");
        sb.append(".badge-md{background:#ecfdf5;color:#047857}.badge-code{background:#eff6ff;color:#1d4ed8}");
        sb.append(".cell-body{padding:16px}");
        sb.append(".cell-body p{margin:0 0 10px}");
        sb.append(".cell-body h1,.cell-body h2,.cell-body h3,.cell-body h4{margin:10px 0 8px}");
        sb.append(".cell-body ul,.cell-body ol{margin:4px 0;padding-left:22px}");
        sb.append(".cell-body blockquote{margin:8px 0;padding:2px 12px;border-left:3px solid #d1d5db;color:#6b7280}");
        sb.append(".cell-body code{background:#f3f4f6;padding:2px 5px;border-radius:4px;font-family:Consolas,monospace;font-size:12px}");
        sb.append(".cell-body pre{background:#1e1e1e;color:#d4d4d4;border-radius:6px;padding:12px;overflow-x:auto}");
        sb.append(".cell-body pre code{background:none;color:inherit;padding:0}");
        sb.append(".output{background:#fbfbfb;border-top:1px solid #eee;padding:8px 16px;font-size:12px;font-family:Consolas,monospace;white-space:pre-wrap;word-break:break-all}");
        sb.append(".empty{color:#9ca3af}");
        sb.append("</style></head><body>");
        sb.append("<div class=\"header\"><h1>").append(escape(title)).append("</h1>");
        sb.append("<div class=\"meta\">文件大小: ").append(readableSize(size)).append(" · 单元格: ").append(cells.size()).append("</div></div>");
        sb.append("<div class=\"notebook\">");
        if (cells.isEmpty()) {
            sb.append("<div class=\"empty\">笔记本无单元格</div>");
        } else {
            for (CellInfo cell : cells) {
                renderCell(sb, cell);
            }
        }
        sb.append("</div></body></html>");
        return sb.toString();
    }

    /**
     * 渲染单个单元格。
     *
     * @param sb   输出缓冲区
     * @param cell 单元格信息
     */
    private void renderCell(StringBuilder sb, CellInfo cell) {
        boolean isMarkdown = "markdown".equals(cell.type);
        sb.append("<div class=\"cell\"><div class=\"cell-head\">");
        if (isMarkdown) {
            sb.append("<span class=\"badge badge-md\">MD</span>");
        } else {
            sb.append("<span class=\"badge badge-code\">代码</span>");
            if (cell.executionCount >= 0) {
                sb.append("In [").append(cell.executionCount).append("]");
            }
        }
        sb.append("</div><div class=\"cell-body\">");
        if (isMarkdown) {
            sb.append(renderMarkdown(cell.source));
        } else {
            sb.append("<pre><code>").append(escape(cell.source)).append("</code></pre>");
        }
        sb.append("</div>");
        for (String output : cell.outputs) {
            sb.append("<div class=\"output\">").append(escape(output)).append("</div>");
        }
        sb.append("</div>");
    }

    /**
     * 轻量渲染 Markdown 子集（标题、代码块、列表、引用、行内代码）。
     *
     * @param source Markdown 源码
     * @return 渲染后的 HTML 片段
     */
    private String renderMarkdown(String source) {
        StringBuilder sb = new StringBuilder();
        List<String> lines = new ArrayList<>(List.of(source.split("\r?\n")));
        boolean inCode = false;
        StringBuilder inline = new StringBuilder();
        for (String rawLine : lines) {
            String line = rawLine;
            if (line.startsWith("```")) {
                if (inCode) {
                    sb.append("<pre><code>").append(escape(inline.toString().stripTrailing())).append("</code></pre>");
                    inline = new StringBuilder();
                } else {
                    sb.append("<pre><code>");
                }
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                inline.append(line).append('\n');
                continue;
            }
            if (line.startsWith("### ")) {
                sb.append("<h3>").append(renderInline(line.substring(4))).append("</h3>");
            } else if (line.startsWith("## ")) {
                sb.append("<h2>").append(renderInline(line.substring(3))).append("</h2>");
            } else if (line.startsWith("# ")) {
                sb.append("<h1>").append(renderInline(line.substring(2))).append("</h1>");
            } else if (line.trim().startsWith("- ") || line.trim().startsWith("* ")) {
                sb.append("<li>").append(renderInline(line.trim().substring(2))).append("</li>");
            } else if (line.trim().startsWith("> ")) {
                sb.append("<blockquote>").append(renderInline(line.trim().substring(2))).append("</blockquote>");
            } else if (!line.trim().isEmpty()) {
                sb.append("<p>").append(renderInline(line)).append("</p>");
            }
        }
        if (inCode) {
            sb.append(escape(inline.toString()));
        }
        return sb.toString();
    }

    /**
     * 渲染行内 Markdown 片段（粗体、斜体、行内代码）。
     *
     * @param text 行内文本
     * @return 渲染后的 HTML 片段
     */
    private String renderInline(String text) {
        String inner = escape(text);
        inner = inner.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
        inner = inner.replaceAll("`([^`]+)`", "<code>$1</code>");
        return inner;
    }

    /**
     * HTML 转义。
     *
     * @param text 原始文本
     * @return 转义后的文本
     */
    private String escape(String text) {
        return StringUtils.escapeHtml(text);
    }

    /**
     * 将字节数转为可读大小。
     *
     * @param bytes 字节数
     * @return 格式化后的大小文本
     */
    private String readableSize(long bytes) {
        return com.chua.common.support.utils.FileUtils.readableFileSize(bytes);
    }

    /**
     * 单元格信息。
     *
     * @param type           单元格类型（markdown / code）
     * @param source         单元格源码
     * @param outputs        代码输出内容列表
     * @param executionCount 执行计数（-1 表示未执行）
     */
    private record CellInfo(String type, String source, List<String> outputs, int executionCount) {
    }
}