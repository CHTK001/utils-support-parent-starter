package com.chua.common.support.lang.document;

import com.chua.common.support.spi.annotations.Spi;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenAPI 文档 → 单页 HTML 导出器（泛微 E10 OpenAPI 风格）。
 *
 * <p>与具体协议栈（SpringDoc / native OpenAPI / Knife4j）解耦——
 * 入参为通用的 {@link OpenApiDocumentData}，出参为单文件 HTML,
 * 含侧边 tree 导航 + API section (请求参数 / 响应参数 / 请求示例 / 响应示例)。</p>
 *
 * <h2>样式来源</h2>
 * <p>CSS / JS / 表格结构参照泛微 E10_Open_API接口文档.html (单页 + 侧边 tree + api-table);
 * 实现上由本类在内存中拼接字符串, 无外部模板, 便于嵌入任意 starter。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("html")
public class OpenApiHtmlProvider implements OpenApiDocumentProvider {

    private static final AtomicLong ID_GEN = new AtomicLong(System.nanoTime());

    @Override
    public String getType() {
        return "html";
    }

    @Override
    public String[] getExtensions() {
        return new String[]{".html", ".htm"};
    }

    @Override
    public void export(OpenApiDocumentData data, File outputFile) {
        if (data == null) {
            throw new IllegalArgumentException("OpenApiDocumentData 不能为空");
        }
        String html = render(data);
        Path output = outputFile.toPath().toAbsolutePath();
        try {
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            try (OutputStream os = Files.newOutputStream(output)) {
                os.write(html.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new RuntimeException("OpenAPI HTML 导出失败: " + output, e);
        }
    }

    /**
     * 仅渲染 HTML，便于测试或预览。
     */
    public String render(OpenApiDocumentData data) {
        String title = data.getTitle() != null ? data.getTitle() : "Open API 接口文档";
        String version = data.getVersion() != null ? data.getVersion() : "1.0.0";

        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        sb.append("<title>").append(escape(title)).append(' ').append(escape(version)).append("</title>");
        sb.append("<style>").append(STYLE).append("</style></head><body>");

        sb.append("<div class=\"topbar\"><h1>").append(escape(title)).append("</h1>");
        sb.append("<span class=\"ver\">版本 ").append(escape(version)).append("</span>");
        sb.append("<div class=\"search\"><input id=\"navSearch\" type=\"text\" placeholder=\"搜索 API 或接口…\"></div></div>");

        sb.append("<div class=\"layout\"><nav class=\"sidebar\"><ul class=\"tree\" id=\"apiTree\">");

        // 1) 顶部 sections（快速入门 / 接入指南 ...）
        if (data.getSections() != null && !data.getSections().isEmpty()) {
            String sectionNodeId = "node-" + ID_GEN.incrementAndGet();
            sb.append("<li class=\"tree-folder\"><div class=\"folder-row\"><span class=\"caret\">▾</span>");
            sb.append("<a href=\"#").append(sectionNodeId).append("\" class=\"folder-link\">快速入门</a></div><ul class=\"children\">");
            for (OpenApiSection sec : data.getSections()) {
                String docId = "doc-" + ID_GEN.incrementAndGet();
                sb.append("<li class=\"tree-file\"><a href=\"#").append(docId);
                sb.append("\" class=\"tree-link\" data-search=\"");
                sb.append(escape(sec.getTitle() != null ? sec.getTitle() : "").toLowerCase());
                sb.append("\">").append(escape(sec.getTitle())).append("</a></li>");
                sec.setContent(sec.getContent() != null ? sec.getContent() : "");
            }
            sb.append("</ul></li>");
        }

        // 2) 按 tag 分组
        LinkedHashMap<String, List<OpenApiEndpoint>> grouped = new LinkedHashMap<>();
        for (OpenApiTag t : data.getTags()) {
            grouped.put(t.getName(), new ArrayList<>());
        }
        for (OpenApiEndpoint ep : data.getEndpoints()) {
            String tag = ep.getTag() != null && !ep.getTag().isBlank() ? ep.getTag() : "default";
            grouped.computeIfAbsent(tag, k -> new ArrayList<>()).add(ep);
        }
        if (grouped.isEmpty()) {
            grouped.put("default", new ArrayList<>());
        }

        for (java.util.Map.Entry<String, List<OpenApiEndpoint>> entry : grouped.entrySet()) {
            String tagName = entry.getKey();
            List<OpenApiEndpoint> endpoints = entry.getValue();
            if (endpoints.isEmpty()) continue;
            String nodeId = "node-" + ID_GEN.incrementAndGet();
            sb.append("<li class=\"tree-folder\"><div class=\"folder-row\"><span class=\"caret\">▾</span>");
            sb.append("<a href=\"#").append(nodeId).append("\" class=\"folder-link\">");
            sb.append(escape(tagName)).append("</a></div><ul class=\"children\">");
            for (OpenApiEndpoint ep : endpoints) {
                String docId = "doc-" + ID_GEN.incrementAndGet();
                ep.setTag(tagName);
                sb.append("<li class=\"tree-file\"><a href=\"#").append(docId);
                sb.append("\" class=\"tree-link\" data-search=\"");
                sb.append(escape((tagName == null ? "" : tagName) + " " + ep.getMethod() + " " + ep.getPath() + " " + safeStr(ep.getSummary())).toLowerCase());
                sb.append("\">").append(escape(safeStr(ep.getSummary()).isEmpty() ? ep.getPath() : ep.getSummary())).append("</a></li>");
            }
            sb.append("</ul></li>");
        }
        sb.append("</ul></nav><div class=\"content\">");

        // 渲染 sections
        if (data.getSections() != null && !data.getSections().isEmpty()) {
            String sectionNodeId = "node-" + (grouped.size() + 1);
            sb.append("<h1 class=\"cat-heading\" id=\"sec-intro\">快速入门</h1>");
            for (OpenApiSection sec : data.getSections()) {
                String docId = "doc-" + ID_GEN.incrementAndGet();
                sb.append("<section class=\"doc-section\" id=\"sec-").append(docId).append("\">");
                sb.append("<h2 id=\"").append(docId).append("\">").append(escape(sec.getTitle())).append("</h2>");
                sb.append(sec.getContent());
                sb.append("<hr></section>");
            }
        }

        // 渲染每个 tag 分组
        for (java.util.Map.Entry<String, List<OpenApiEndpoint>> entry : grouped.entrySet()) {
            String tagName = entry.getKey();
            List<OpenApiEndpoint> endpoints = new ArrayList<>(entry.getValue());
            if (endpoints.isEmpty()) continue;
            endpoints.sort(Comparator.comparing(OpenApiEndpoint::getPath, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(OpenApiEndpoint::getMethod, Comparator.nullsLast(Comparator.naturalOrder())));
            String nodeId = "node-" + ID_GEN.incrementAndGet();
            sb.append("<h1 class=\"cat-heading\" id=\"").append(nodeId).append("\">");
            sb.append(escape(tagName)).append("</h1>");
            for (OpenApiEndpoint ep : endpoints) {
                renderEndpoint(sb, ep);
            }
        }

        sb.append("</div></div><div class=\"back-top\" id=\"backTop\" title=\"返回顶部\">↑</div>");
        sb.append("<script>").append(SCRIPT).append("</script>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private void renderEndpoint(StringBuilder sb, OpenApiEndpoint ep) {
        String docId = "doc-" + ID_GEN.incrementAndGet();
        sb.append("<section class=\"doc-section\" id=\"sec-").append(docId).append("\">");
        sb.append("<h2 id=\"").append(docId).append("\">").append(escape(safeStr(ep.getSummary()).isEmpty() ? ep.getPath() : ep.getSummary())).append("</h2>");

        sb.append("<p><strong>接口地址:</strong> <code>").append(escape(safeStr(ep.getPath()))).append("</code></p>");
        sb.append("<p><strong>请求方式:</strong> <code>").append(escape(safeStr(ep.getMethod()))).append("</code>");
        if (ep.isDeprecated()) {
            sb.append(" <span class=\"deprecated\">已废弃</span>");
        }
        sb.append("</p>");

        if (ep.getDescription() != null && !ep.getDescription().isBlank()) {
            sb.append("<p>").append(escape(ep.getDescription())).append("</p>");
        }

        // 请求参数
        boolean hasParams = ep.getParameters() != null && !ep.getParameters().isEmpty();
        boolean hasBody = ep.getRequestBody() != null;
        if (hasParams || hasBody) {
            sb.append("<h3>请求参数</h3>");
            sb.append("<table class=\"api-table\"><thead><tr>");
            sb.append("<th>参数名</th><th>类型</th><th>位置</th><th>必填</th><th>说明</th></tr></thead><tbody>");
            if (hasParams) {
                for (OpenApiParam p : ep.getParameters()) {
                    sb.append("<tr><td>").append(escape(safeStr(p.getName()))).append("</td>");
                    sb.append("<td>").append(escape(safeStr(p.getType()))).append("</td>");
                    sb.append("<td>").append(escape(safeStr(p.getIn()))).append("</td>");
                    sb.append("<td>").append(p.isRequired() ? "是" : "否").append("</td>");
                    sb.append("<td>").append(escape(safeStr(p.getDescription()))).append("</td></tr>");
                }
            }
            if (hasBody) {
                OpenApiRequestBody body = ep.getRequestBody();
                sb.append("<tr><td>body</td><td>").append(escape(safeStr(body.getType()))).append("</td>");
                sb.append("<td>body</td><td>").append(body.isRequired() ? "是" : "否").append("</td>");
                sb.append("<td>").append(escape(safeStr(body.getDescription()))).append("</td></tr>");
            }
            sb.append("</tbody></table>");
        }

        // 响应
        if (ep.getResponses() != null && !ep.getResponses().isEmpty()) {
            sb.append("<h3>响应参数</h3>");
            for (OpenApiResponse resp : ep.getResponses()) {
                sb.append("<p><strong>HTTP ").append(escape(safeStr(resp.getCode()))).append("</strong>");
                if (resp.getDescription() != null) {
                    sb.append(" — ").append(escape(resp.getDescription()));
                }
                sb.append("</p>");
                if (resp.getFields() != null && !resp.getFields().isEmpty()) {
                    sb.append("<table class=\"api-table\"><thead><tr>");
                    sb.append("<th>参数名</th><th>类型</th><th>说明</th></tr></thead><tbody>");
                    for (OpenApiParam f : resp.getFields()) {
                        sb.append("<tr><td>").append(escape(safeStr(f.getName()))).append("</td>");
                        sb.append("<td>").append(escape(safeStr(f.getType()))).append("</td>");
                        sb.append("<td>").append(escape(safeStr(f.getDescription()))).append("</td></tr>");
                    }
                    sb.append("</tbody></table>");
                }
            }
        }

        // 示例
        if (ep.getRequestSample() != null) {
            sb.append("<h4>请求示例</h4>");
            sb.append("<pre class=\"code-block\" data-lang=\"");
            sb.append(ep.getRequestBody() != null ? ep.getRequestBody().getContentType() : "json");
            sb.append("\"><code>").append(escape(ep.getRequestSample())).append("</code></pre>");
        }
        if (ep.getResponseSample() != null) {
            sb.append("<h4>响应示例</h4>");
            sb.append("<pre class=\"code-block\" data-lang=\"json\"><code>").append(escape(ep.getResponseSample())).append("</code></pre>");
        }

        sb.append("<hr></section>");
    }

    private static String safeStr(String s) {
        return s == null ? "" : s;
    }

    private String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 样式 (从泛微E10_Open_API接口文档.html 提取并简化)。
     */
    private static final String STYLE = """
            * { box-sizing: border-box; }
            html { overflow-x:hidden; scroll-behavior:smooth; }
            body { margin:0; padding:0; font-family: -apple-system, "Segoe UI", "Microsoft YaHei", Roboto, Helvetica, Arial, sans-serif; color:#1f2329; background:#fff; overflow-x:hidden; }
            a { color:#1a6cff; text-decoration:none; }
            a:hover { text-decoration:underline; }
            .topbar { position:fixed; top:0; left:0; right:0; z-index:50; display:flex; align-items:center; gap:16px; padding:10px 20px; background:#0b5394; color:#fff; box-shadow:0 1px 4px rgba(0,0,0,.15); }
            .topbar h1 { font-size:18px; margin:0; font-weight:600; }
            .topbar .ver { background:rgba(255,255,255,.2); padding:2px 10px; border-radius:12px; font-size:12px; }
            .topbar .search { margin-left:auto; }
            .topbar .search input { padding:6px 12px; border:none; border-radius:16px; width:240px; outline:none; font-size:13px; }
            .layout { display:flex; align-items:flex-start; width:100%; max-width:100%; margin-top:48px; }
            .sidebar { position:fixed; top:48px; left:0; bottom:0; width:300px; min-width:300px; overflow:auto; border-right:1px solid #e5e6eb; background:#fafbfc; padding:12px 8px 40px; font-size:13px; scrollbar-width:none; -ms-overflow-style:none; }
            .sidebar::-webkit-scrollbar { display:none; }
            .tree, .tree ul { list-style:none; margin:0; padding:0; }
            .tree ul.children { margin-left:14px; border-left:1px dashed #dfe1e6; padding-left:6px; }
            .tree li { margin:1px 0; }
            .folder-row { display:flex; align-items:center; gap:4px; padding:2px 4px; border-radius:4px; }
            .folder-row:hover, .tree-file:hover { background:#eef3fb; }
            .caret { cursor:pointer; width:14px; text-align:center; color:#8a8f99; user-select:none; font-size:11px; }
            .tree-folder.collapsed > ul.children { display:none; }
            .tree-folder.collapsed > .folder-row .caret { transform:rotate(-90deg); }
            .folder-link { font-weight:600; color:#1f2329; }
            .tree-file a { display:block; padding:2px 4px 2px 20px; border-radius:4px; color:#3a3f47; }
            .tree-link.active { background:#1a6cff; color:#fff !important; }
            .content { flex:1; min-width:0; padding:24px 40px 80px; max-width:1000px; overflow-x:hidden; margin-left:300px; }
            .doc-section { scroll-margin-top:60px; }
            .cat-heading { margin-top:36px; padding-bottom:8px; border-bottom:2px solid #0b5394; color:#0b5394; }
            h1 { font-size:26px; margin:24px 0 12px; }
            h2 { font-size:21px; margin:22px 0 10px; padding-bottom:6px; border-bottom:1px solid #e5e6eb; scroll-margin-top:60px; }
            h3 { font-size:18px; margin:18px 0 8px; scroll-margin-top:60px; }
            h4 { font-size:16px; margin:16px 0 6px; }
            code { background:#f2f3f5; padding:1px 5px; border-radius:3px; font-family:"SFMono-Regular",Consolas,Menlo,monospace; font-size:13px; color:#c7254e; }
            pre.code-block { background:#f5f7fa; color:#24292e; padding:14px 16px; border-radius:8px; overflow-x:auto; margin:12px 0; position:relative; border:1px solid #e1e4e8; max-width:100%; }
            pre.code-block code { background:none; color:inherit; padding:0; font-size:13px; line-height:1.6; }
            pre.code-block::before { content:attr(data-lang); position:absolute; top:6px; right:10px; font-size:11px; color:#959da5; text-transform:uppercase; font-weight:600; }
            table.api-table { border-collapse:collapse; width:100%; max-width:100%; margin:12px 0; font-size:13px; overflow-x:auto; display:block; }
            .content > table, .content section table { display:table; }
            table.api-table th, table.api-table td { border:1px solid #d9dce0; padding:7px 10px; text-align:left; vertical-align:top; }
            table.api-table th { background:#f0f5ff; font-weight:600; }
            table.api-table tr:nth-child(even) td { background:#fafbfc; }
            .deprecated { background:#e74c3c; color:#fff; padding:1px 6px; border-radius:3px; font-size:12px; margin-left:6px; }
            blockquote { border-left:4px solid #0b5394; margin:12px 0; padding:6px 14px; background:#f7faff; color:#445; }
            hr { border:none; border-top:1px solid #e5e6eb; margin:20px 0; }
            .empty-note { color:#999; font-style:italic; }
            .back-top { position:fixed; right:24px; bottom:24px; background:#0b5394; color:#fff; width:42px; height:42px; border-radius:50%; display:flex; align-items:center; justify-content:center; cursor:pointer; box-shadow:0 2px 8px rgba(0,0,0,.25); font-size:18px; display:none; }
            .back-top.visible { display:flex; }
            """;

    private static final String SCRIPT = """
            (function() {
              const tree = document.getElementById('apiTree');
              if (!tree) return;
              tree.addEventListener('click', function(e) {
                const caret = e.target.closest && e.target.closest('.caret');
                if (caret) {
                  const folder = caret.closest('.tree-folder');
                  if (folder) folder.classList.toggle('collapsed');
                  return;
                }
                const link = e.target.closest && e.target.closest('.tree-link');
                if (link) {
                  document.querySelectorAll('.tree-link.active').forEach(n => n.classList.remove('active'));
                  link.classList.add('active');
                }
              });
              const input = document.getElementById('navSearch');
              if (input) {
                input.addEventListener('input', function() {
                  const q = input.value.trim().toLowerCase();
                  tree.querySelectorAll('.tree-file').forEach(li => {
                    const a = li.querySelector('.tree-link');
                    const hit = !q || (a && (a.getAttribute('data-search') || a.textContent).indexOf(q) >= 0);
                    li.style.display = hit ? '' : 'none';
                  });
                  tree.querySelectorAll('.tree-folder').forEach(f => {
                    const anyVisible = Array.from(f.querySelectorAll('.tree-file')).some(x => x.style.display !== 'none');
                    if (q && !anyVisible) f.style.display = 'none';
                    else { f.style.display = ''; if (q) f.classList.remove('collapsed'); }
                  });
                });
              }
              const back = document.getElementById('backTop');
              if (back) {
                window.addEventListener('scroll', function() { back.classList.toggle('visible', window.scrollY > 200); });
                back.addEventListener('click', function() { window.scrollTo({ top: 0, behavior: 'smooth' }); });
              }
            })();
            """;
}
