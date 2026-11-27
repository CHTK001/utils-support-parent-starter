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

    static String safeJson(Object value) {
        return Json.toJson(value).replace("<", "\\u003c").replace(">", "\\u003e")
                .replace("&", "\\u0026").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
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
