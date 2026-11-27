package com.chua.codedoc.support;

import com.chua.common.support.lang.json.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 无网络、无模型替身的源码文档基础链路回归测试。
 *
 * @author CH
 * @since 4.0.0.51
 */
class CodeDocsTest {
    @TempDir(factory = WorkspaceTempFactory.class)
    Path temporary;
    Path source;
    Path output;

    @BeforeEach
    void directories() throws IOException {
        source = Files.createDirectory(temporary.resolve("source"));
        output = temporary.resolve("site");
    }

    @Test
    void scansRecursivelyAndCombinesFilters() throws IOException {
        file("Root.java", "class Root {}\n");
        file("api/Use.JAVA", "class Use {}\n");
        file("api/Ignore.kt", "class Ignore\n");
        file("generated/Generated.java", "class Generated {}\n");
        file("target/Build.java", "class Build {}\n");
        file("api/nested/Deep.java", "class Deep {}\n");
        var issues = new ArrayList<CodeDocResult.Issue>();
        var files = SourceScanner.scan(options(Set.of("java", "kt"), Set.of("kt"),
                List.of("**/api/**"), List.of("**/target/**", "generated"), 10, 20, 1024), issues);
        assertEquals(List.of("api/Use.JAVA", "api/nested/Deep.java"), relatives(files));
        assertTrue(issues.isEmpty());
    }

    @Test
    void excludesOutputWithinSource() throws IOException {
        output = source.resolve("docs");
        file("Root.java", "class Root {}\n");
        file("docs/generated.java", "class Generated {}\n");
        var files = SourceScanner.scan(options(), new ArrayList<>());
        assertEquals(List.of("Root.java"), relatives(files));
    }

    @Test
    void rootDepthSkipsSubdirectoriesAndReportsIt() throws IOException {
        file("Root.java", "class Root {}\n");
        file("nested/Child.java", "class Child {}\n");
        var issues = new ArrayList<CodeDocResult.Issue>();
        var files = SourceScanner.scan(options(Set.of("java"), Set.of(), List.of(), List.of(), 0, 20, 1024), issues);
        assertEquals(List.of("Root.java"), relatives(files));
        assertEquals(1, issues.size());
        assertEquals("SCAN", issues.getFirst().stage());
    }

    @Test
    void fileCountLimitStopsInsteadOfSilentlyTruncating() throws IOException {
        file("A.java", "class A {}\n");
        file("B.java", "class B {}\n");
        var configuration = options(Set.of("java"), Set.of(), List.of(), List.of(), 10, 1, 1024);
        assertThrows(IOException.class, () -> SourceScanner.scan(configuration, new ArrayList<>()));
    }

    @Test
    void normalizesBomAndNewlinesWithoutChangingText() throws IOException {
        Path path = file("Text.java", "\uFEFF中文\r\nsecond\rthird\n");
        assertEquals("中文\nsecond\nthird\n", SourceScanner.read(path, options()));
    }

    @Test
    void rejectsInvalidUtf8NulAndOversizedText() throws IOException {
        Path invalid = source.resolve("Invalid.java");
        Files.write(invalid, new byte[]{(byte) 0xc3, 0x28});
        Path binary = file("Binary.java", "text\u0000tail");
        Path large = file("Large.java", "x".repeat(1025));
        assertThrows(IOException.class, () -> SourceScanner.read(invalid, options()));
        assertThrows(IOException.class, () -> SourceScanner.read(binary, options()));
        assertThrows(IOException.class, () -> SourceScanner.read(large, options()));
    }

    @Test
    void supportsAntDirectoryPruningAtAnyDepth() {
        assertTrue(SourceScanner.matches(List.of("**/target/**"), "target", true));
        assertTrue(SourceScanner.matches(List.of("**/target/**"), "a/target", true));
        assertTrue(SourceScanner.matches(List.of("generated"), "a/generated", true));
        assertFalse(SourceScanner.matches(List.of("generated"), "a/generated.java", false));
        assertTrue(SourceScanner.matches(List.of("**/*.java"), "Root.java", false));
    }

    @Test
    void chunksKeepOriginalTextAndGlobalLineNumbers() {
        String text = "0123456789 abcdef\n".repeat(500);
        int lineLength = text.indexOf('\n') + 1;
        var chunks = CodeDocAnalyzer.chunks(text, 1024);
        assertTrue(chunks.size() > 1);
        int previousStart = -1;
        int previousEnd = 0;
        for (var chunk : chunks) {
            int offset = (chunk.startLine() - 1) * lineLength;
            assertTrue(offset > previousStart);
            assertTrue(offset <= previousEnd);
            assertEquals(text.substring(offset, offset + chunk.text().length()), chunk.text());
            assertTrue(chunk.text().length() <= 1024);
            assertEquals(chunk.startLine() + chunk.text().lines().count() - 1, chunk.endLine());
            previousStart = offset;
            previousEnd = offset + chunk.text().length();
        }
        assertEquals(text.length(), previousEnd);
    }

    @Test
    void chunksDoNotSplitSupplementaryUnicodeCharacters() {
        String text = "a".repeat(1023) + "\uD83D\uDE00" + "b".repeat(2048);
        for (var chunk : CodeDocAnalyzer.chunks(text, 1024)) {
            assertTrue(chunk.text().length() <= 1024);
            assertFalse(Character.isLowSurrogate(chunk.text().charAt(0)));
            assertFalse(Character.isHighSurrogate(chunk.text().charAt(chunk.text().length() - 1)));
        }
    }

    @Test
    void parsesStructuredDocumentationAndMarkdownFence() throws IOException {
        String json = fragment(4, 4);
        var chunk = new CodeDocAnalyzer.Chunk("public int size() { return 0; }", 4, 4);
        for (String response : List.of(json, "```json\n" + json + "\n```")) {
            var parsed = CodeDocAnalyzer.parse(response, chunk);
            assertEquals("size", parsed.apis().getFirst().name());
            assertEquals("int", parsed.apis().getFirst().returns());
            assertEquals("长度查询-Tools", parsed.title());
            assertEquals("集合工具", parsed.category());
            assertEquals("使用", parsed.sections().getFirst().title());
        }
    }

    @Test
    void rejectsResponseWithInvalidLineRange() {
        var chunk = new CodeDocAnalyzer.Chunk("line", 4, 4);
        assertThrows(IOException.class, () -> CodeDocAnalyzer.parse(fragment(3, 4), chunk));
        assertThrows(IOException.class, () -> CodeDocAnalyzer.parse(fragment(4, 5), chunk));
        assertThrows(IOException.class, () -> CodeDocAnalyzer.parse(fragment(4, 3), chunk));
    }

    @Test
    void rejectsEmptyOrIncompleteModelResponses() {
        var chunk = new CodeDocAnalyzer.Chunk("line", 1, 1);
        for (String response : List.of("", "[]", "{}", "```json\n{}", "{\"summary\":\"ok\"}")) {
            assertThrows(Exception.class, () -> CodeDocAnalyzer.parse(response, chunk));
        }
    }

    @Test
    void requiresGuideSectionsButAllowsCustomSectionNames() throws IOException {
        var chunk = new CodeDocAnalyzer.Chunk("line", 4, 4);
        String custom = fragment(4, 4).replace("\"使用\"", "\"排查步骤\"");
        assertEquals("排查步骤", CodeDocAnalyzer.parse(custom, chunk).sections().getFirst().title());
        for (String field : List.of("title", "category", "content")) {
            String invalid = custom.replaceFirst("\"" + field + "\":\"[^\"]+\"", "\"" + field + "\":\"\"");
            assertThrows(IOException.class, () -> CodeDocAnalyzer.parse(invalid, chunk), field);
        }
        var decoded = Json.fromJson(custom, java.util.Map.class);
        decoded.put("sections", List.of());
        assertThrows(IOException.class, () -> CodeDocAnalyzer.parse(Json.toJson(decoded), chunk));
    }

    @Test
    void recordCacheRoundTripPreservesNestedDocumentation() {
        var parameter = new CodeDocPage.Parameter("input", "String", "不可空");
        var example = new CodeDocPage.Example("调用", "Tools.size(input);", "未执行验证");
        var api = new CodeDocPage.Api("size", "size(String input)", "长度", List.of(parameter),
                "int", List.of(example), List.of("边界"), 1, 1);
        var section = new CodeDocPage.Section("完整示例", "按任务讲解。", List.of(example), List.of("边界"), 1, 1);
        var page = new CodeDocPage("id", "工具.java", "java", "概览", List.of(api), List.of(), "source\n",
                "长度查询-Tools", "集合工具", List.of(section));
        assertEquals(page, Json.fromJson(Json.toJson(page), CodeDocPage.class));
    }

    @Test
    void safeJsonEscapesScriptDelimitersAndUnicodeSeparators() {
        String input = "</script><img>&\u2028\u2029";
        String json = CodeDocSite.safeJson(List.of(input));
        assertFalse(json.contains("<"));
        assertFalse(json.contains(">"));
        assertFalse(json.contains("&"));
        assertFalse(json.contains("\u2028"));
        assertFalse(json.contains("\u2029"));
        assertEquals(List.of(input), Json.fromJson(json, List.class));
    }

    @Test
    void writesOfflineSiteAndReplacesExistingOutput() throws IOException {
        var result = new CodeDocResult(output.resolve("index.html"), 0, 0, 0, 0, List.of());
        CodeDocSite.write(options(), List.of(), result);
        for (String name : List.of("index.html", "site.js", "site.css", "data.js", "manifest.json")) {
            assertTrue(Files.size(output.resolve(name)) > 0, name);
        }
        String html = Files.readString(output.resolve("index.html"));
        assertTrue(html.contains("src=\"data.js\""));
        assertFalse(html.contains("https://"));
        String data = Files.readString(output.resolve("data.js"));
        assertTrue(data.startsWith("window.CODEDOC_DATA = "));
        CodeDocSite.writeText(output.resolve("data.js"), "replacement");
        assertEquals("replacement", Files.readString(output.resolve("data.js")));
        try (var files = Files.list(output)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void resultCompletenessIncludesScanAndCacheProblems() {
        assertTrue(new CodeDocResult(output, 1, 1, 0, 1, List.of()).complete());
        assertFalse(new CodeDocResult(output, 1, 0, 0, 1, List.of()).complete());
        assertFalse(new CodeDocResult(output, 1, 1, 0, 1,
                List.of(new CodeDocResult.Issue("A.java", "CACHE", "failed"))).complete());
    }

    @Test
    void exportsTaskGuideBeforeApiReferenceAndPreservesCodeFences() throws IOException {
        var example = new CodeDocPage.Example("完整示例", "```literal\nclass Demo {}", "预期说明");
        var section = new CodeDocPage.Section("先学会使用", "功能说明", List.of(example), List.of("边界"), 1, 1);
        var page = new CodeDocPage("guide", "Tools.java", "java", "概览", List.of(), List.of(), "class Tools {}",
                "工具指南", "工具分类", List.of(section));
        CodeDocSite.write(options(), List.of(page), new CodeDocResult(output.resolve("index.html"), 1, 1, 0, 1, List.of()));
        String markdown = Files.readString(output.resolve("guides/guide.md"));
        assertTrue(markdown.startsWith("# 工具指南\n"));
        assertTrue(markdown.indexOf("## 先学会使用") < markdown.indexOf("## API 参数与返回值参考"));
        assertTrue(markdown.contains("````java\n```literal\nclass Demo {}\n````"));
        assertTrue(markdown.contains("Tools.java:1"));
    }

    private Path file(String relative, String text) throws IOException {
        Path path = source.resolve(relative);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private List<String> relatives(List<Path> paths) {
        return paths.stream().map(path -> SourceScanner.relative(source, path)).toList();
    }

    private CodeDocOptions options() {
        return options(Set.of("java"), Set.of(), List.of(), List.of(), 10, 20, 1024);
    }

    private CodeDocOptions options(Set<String> includeTypes, Set<String> excludeTypes,
                                   List<String> includes, List<String> excludes,
                                   int maxDepth, int maxFiles, long maxFileBytes) {
        return new CodeDocOptions(null, source, output, includeTypes, excludeTypes, includes, excludes,
                StandardCharsets.UTF_8, "工具文档", CodeDocPrompts.HUTOOL_GUIDE, "中文", "test", true, false,
                maxDepth, maxFiles, maxFileBytes, 1024, 0, 1, event -> { });
    }

    private static String fragment(int start, int end) {
        return """
                {"title":"长度查询-Tools","category":"集合工具","summary":"用途",
                "sections":[{"title":"使用","content":"通过 size 查询长度。","examples":[],"notes":[],
                "startLine":%d,"endLine":%d}],"apis":[{"name":"size","signature":"public int size()",
                "description":"长度","parameters":[],"returns":"int","examples":[],
                "notes":[],"startLine":%d,"endLine":%d}],"notes":[]}
                """.formatted(start, end, start, end);
    }

    /** 临时测试文件始终落工作区 temp，JUnit 负责用后清理。 */
    public static class WorkspaceTempFactory implements TempDirFactory {
        @Override
        public Path createTempDirectory(AnnotatedElementContext elementContext, ExtensionContext extensionContext)
                throws Exception {
            Path module = Path.of(CodeDocsTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .getParent().getParent();
            return Files.createTempDirectory(Files.createDirectories(
                    module.resolve("../../../temp/codedoc-tests").normalize()), "case-");
        }
    }
}
