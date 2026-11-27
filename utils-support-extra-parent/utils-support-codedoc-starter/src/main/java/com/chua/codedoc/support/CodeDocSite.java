package com.chua.codedoc.support;

import com.chua.common.support.lang.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 使用自带静态资源生成站点；无需 CDN、npm、后端或本地 HTTP 服务。
 *
 * @author CH
 * @since 4.0.0.51
 */
final class CodeDocSite {
    private CodeDocSite() {
    }

    static void write(CodeDocOptions options, List<CodeDocPage> pages, CodeDocResult result) throws IOException {
        Path output = options.outputDirectory();
        for (CodeDocPage page : pages) {
            writePage(options, page);
        }
        writeProgress(options, pages, result);
    }

    /**
     * 只刷新站点数据、榜单与静态资源，不重写各页 Markdown。
     * 用于生成过程中的增量刷新：菜单随已完成的分组逐步长出来。
     */
    static void writeProgress(CodeDocOptions options, List<CodeDocPage> pages, CodeDocResult result) throws IOException {
        Path output = options.outputDirectory();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", options.title());
        data.put("generatedAt", Instant.now().toString());
        data.put("scannedFiles", result.scannedFiles());
        data.put("documentedFiles", result.documentedFiles());
        data.put("cachedFiles", result.cachedFiles());
        data.put("modelCalls", result.modelCalls());
        data.put("issues", result.issues());
        data.put("pages", pages);
        // data.js 作为经典脚本加载，避免 file:// 环境下 fetch/module 的跨域限制。
        writeText(output.resolve("data.js"), "window.CODEDOC_DATA = " + safeJson(data) + ";\n");
        writeText(output.resolve("manifest.json"), Json.toPrettyJson(Map.of(
                "scannedFiles", result.scannedFiles(), "documentedFiles", result.documentedFiles(),
                "cachedFiles", result.cachedFiles(), "modelCalls", result.modelCalls(), "issues", result.issues())));
        for (String resource : List.of("site.css", "site.js", "index.html")) {
            try (var input = CodeDocSite.class.getResourceAsStream("/com/chua/codedoc/site/" + resource)) {
                if (input == null) {
                    throw new IOException("缺少文档站资源: " + resource);
                }
                writeText(output.resolve(resource), new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    /** 单页 Markdown 落盘；生成过程中即时写出，站点刷新不必重复渲染全量页面。 */
    static void writePage(CodeDocOptions options, CodeDocPage page) throws IOException {
        writeText(options.outputDirectory().resolve("guides").resolve(page.id() + ".md"), markdown(page));
    }

    static String safeJson(Object value) {
        return Json.toJson(value).replace("<", "\\u003c").replace(">", "\\u003e")
                .replace("&", "\\u0026").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }

    private static String markdown(CodeDocPage page) {
        StringBuilder text = new StringBuilder("# ").append(page.title()).append("\n\n")
                .append("分类：").append(page.category()).append("\n\n").append(page.summary())
                .append("\n\n> AI 根据源码生成，需人工复核；示例未经执行验证。\n\n");
        for (CodeDocPage.Section section : page.sections()) {
            text.append("## ").append(section.title()).append("\n\n").append(section.content()).append("\n\n");
            for (CodeDocPage.Example example : section.examples()) {
                text.append("### ").append(example.title()).append("\n\n");
                appendCode(text, example.code(), page.language());
                text.append(example.explanation()).append("\n\n");
            }
            for (String note : section.notes()) {
                text.append("- ").append(note).append('\n');
            }
            text.append("\n源码：").append(page.path()).append(":").append(section.startLine())
                    .append("–").append(section.endLine()).append("\n\n");
        }
        text.append("## API 参数与返回值参考\n\n");
        for (CodeDocPage.Api api : page.apis()) {
            text.append("### ").append(api.name()).append("\n\n").append(api.description()).append("\n\n");
            appendCode(text, api.signature(), page.language());
            for (CodeDocPage.Parameter parameter : api.parameters()) {
                text.append("- ").append(parameter.name()).append("（").append(parameter.type()).append("）：")
                        .append(parameter.description()).append('\n');
            }
            text.append("\n返回值：").append(api.returns()).append("\n\n");
            for (CodeDocPage.Example example : api.examples()) {
                text.append("#### ").append(example.title()).append("\n\n");
                appendCode(text, example.code(), page.language());
                text.append(example.explanation()).append("\n\n");
            }
            for (String note : api.notes()) {
                text.append("- ").append(note).append('\n');
            }
            text.append("\n源码：").append(page.path()).append(":").append(api.startLine())
                    .append("–").append(api.endLine()).append("\n\n");
        }
        if (!page.notes().isEmpty()) {
            text.append("## 注意事项\n\n");
            for (String note : page.notes()) {
                text.append("- ").append(note).append('\n');
            }
        }
        return text.toString();
    }

    private static void appendCode(StringBuilder text, String code, String language) {
        // 模型代码可能自带围栏，使用更长围栏防止提前闭合 Markdown 代码块。
        String fence = "`".repeat(Math.max(3, code.lines().mapToInt(line -> {
            int count = 0;
            while (count < line.length() && line.charAt(count) == '`') {
                count++;
            }
            return count + 1;
        }).max().orElse(3)));
        text.append(fence).append(language).append('\n').append(code).append('\n').append(fence).append("\n\n");
    }

    static void writeText(Path path, String text) throws IOException {
        Path parent = path.getParent();
        Files.createDirectories(parent);
        // 拒绝在用户放入的目录链接或文件链接上写入。
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("输出目标不能是符号链接: " + path.getFileName());
        }
        Path temporary = Files.createTempFile(parent, ".codedoc-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
