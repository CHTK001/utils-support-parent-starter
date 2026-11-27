package com.chua.codedoc.support;

import com.chua.common.support.ai.chat.ChatClient;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 源码到离线工具文档站的统一入口。
 *
 * <p>仅依赖 utils common 的 ChatClient、JSON 和路径匹配契约；不启动服务器、
 * 不执行源码或示例、不关闭调用方提供的客户端。模型调用按文件顺序串行执行。
 *
 * <pre>{@code
 * CodeDocResult result = CodeDocs.builder(chatClient)
 *     .directory(Path.of("my-project"))
 *     .outputDirectory(Path.of("my-docs"))
 *     .includeTypes("java", "kt", "ts", "vue", "py")
 *     .exclude("target", "generated")
 *     .title("工具使用手册")
 *     .generate();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.51
 */
public final class CodeDocs {
    private CodeDocs() {
    }

    /**
     * 创建构建器。客户端应专用于本次文档任务，且不携带其他对话历史。
     *
     * @param chatClient 已完成模型和超时配置的真实客户端
     * @return 文档构建器
     */
    public static Builder builder(ChatClient chatClient) {
        return new Builder(chatClient);
    }

    /**
     * 文件级进度，不包含源码、提示词或客户端凭据。
     *
     * @param path 当前源码相对路径
     * @param completed 已处理文件数，包含失败文件
     * @param total 匹配的文件总数
     * @param status GENERATED、CACHED 或 FAILED
     */
    public record Progress(String path, int completed, int total, String status) {
    }

    /** 非线程安全构建器；generate 每次使用独立配置快照。 */
    public static final class Builder {
        private final ChatClient chatClient;
        private Path directory;
        private Path outputDirectory;
        private final Set<String> includeTypes = new LinkedHashSet<>(List.of(
                "java", "kt", "kts", "scala", "groovy", "js", "jsx", "ts", "tsx", "vue", "svelte",
                "py", "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "rb", "swift", "sql"));
        private final Set<String> excludeTypes = new LinkedHashSet<>();
        private final List<String> includes = new ArrayList<>();
        private final List<String> excludes = new ArrayList<>(List.of(
                "**/.git/**", "**/.svn/**", "**/.idea/**", "**/node_modules/**", "**/target/**",
                "**/build/**", "**/dist/**", "**/.next/**", "**/.venv/**", "**/venv/**",
                "**/__pycache__/**", "**/coverage/**", "**/.codedoc-cache/**",
                "**/.env", "**/.env.*", "**/*.pem", "**/*.key", "**/secrets.*"));
        private Charset charset = StandardCharsets.UTF_8;
        private String title = "源码使用手册";
        private String prompt = CodeDocPrompts.HUTOOL_GUIDE;
        private String instructions = "使用简体中文编写文档。";
        private String cacheNamespace = "default";
        private boolean incremental = true;
        private boolean failFast;
        private int maxDepth = Integer.MAX_VALUE;
        private int maxFiles = 10_000;
        private long maxFileBytes = 1_048_576;
        private int chunkChars = 16_000;
        private int retries = 1;
        private Consumer<Progress> progress = event -> { };

        private Builder(ChatClient chatClient) {
            this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
        }

        /** @param value 必填源码目录 @return 当前构建器 */
        public Builder directory(Path value) {
            directory = Objects.requireNonNull(value, "directory");
            return this;
        }

        /** @param value 输出目录，默认是源码目录下的 codedoc-site @return 当前构建器 */
        public Builder outputDirectory(Path value) {
            outputDirectory = Objects.requireNonNull(value, "outputDirectory");
            return this;
        }

        /** @param values 覆盖包含的扩展名，不区分大小写；空数组表示全部文本类型 @return 当前构建器 */
        public Builder includeTypes(String... values) {
            includeTypes.clear();
            includeTypes.addAll(types(values));
            return this;
        }

        /** @param values 追加排除的扩展名；优先于 includeTypes @return 当前构建器 */
        public Builder excludeTypes(String... values) {
            excludeTypes.addAll(types(values));
            return this;
        }

        /** @param patterns 追加相对目录的 Ant 路径规则，例如 **&#47;*Utils.java @return 当前构建器 */
        public Builder include(String... patterns) {
            includes.addAll(patterns(patterns));
            return this;
        }

        /** @param patterns 追加排除规则；目录在递归进入之前剪枝 @return 当前构建器 */
        public Builder exclude(String... patterns) {
            excludes.addAll(patterns(patterns));
            return this;
        }

        /** @param value 站点标题 @return 当前构建器 */
        public Builder title(String value) {
            title = required(value, "title");
            return this;
        }

        /**
         * 替换文档写作提示词；默认是 {@link CodeDocPrompts#HUTOOL_GUIDE}。
         * JSON 输出结构与源码范围限制仍由生成器附加，改变提示词会使旧缓存失效。
         *
         * @param value 非空写作要求，可从外部 UTF-8 文件读取
         * @return 当前构建器
         */
        public Builder prompt(String value) {
            required(value, "prompt");
            // 保留实际提示词及尾部换行，使显式传入内置值与默认配置共用缓存键。
            prompt = value;
            return this;
        }

        /** @param value 在 prompt 之后追加的语言、读者等补充要求 @return 当前构建器 */
        public Builder instructions(String value) {
            instructions = required(value, "instructions");
            return this;
        }

        /** @param value 输入文本编码，默认严格 UTF-8，解码错误报告失败 @return 当前构建器 */
        public Builder charset(Charset value) {
            charset = Objects.requireNonNull(value, "charset");
            return this;
        }

        /** @param value 是否复用未改变的文件分析结果，默认 true @return 当前构建器 */
        public Builder incremental(boolean value) {
            incremental = value;
            return this;
        }

        /** @param value 额外缓存版本；修改隐藏系统提示词或路由配置时应改变它 @return 当前构建器 */
        public Builder cacheNamespace(String value) {
            cacheNamespace = required(value, "cacheNamespace");
            return this;
        }

        /** @param value 文件处理失败时是否立即抛出异常，默认 false @return 当前构建器 */
        public Builder failFast(boolean value) {
            failFast = value;
            return this;
        }

        /** @param value 最大子目录深度，0 仅处理根目录文件，默认不限 @return 当前构建器 */
        public Builder maxDepth(int value) {
            if (value < 0) {
                throw new IllegalArgumentException("maxDepth 必须 >= 0");
            }
            maxDepth = value;
            return this;
        }

        /** @param value 最大匹配文件数，超限整体中止，默认 10000 @return 当前构建器 */
        public Builder maxFiles(int value) {
            maxFiles = positive(value, "maxFiles");
            return this;
        }

        /** @param value 单文件最大字节数，超限报告问题，范围 1..67108864 @return 当前构建器 */
        public Builder maxFileBytes(long value) {
            if (value < 1 || value > 67_108_864) {
                throw new IllegalArgumentException("maxFileBytes 必须在 1..67108864 之间");
            }
            maxFileBytes = value;
            return this;
        }

        /** @param value 每块源码字符上限，范围 1024..262144，不等于模型 token 上限 @return 当前构建器 */
        public Builder chunkChars(int value) {
            if (value < 1024 || value > 262_144) {
                throw new IllegalArgumentException("chunkChars 必须在 1024..262144 之间");
            }
            chunkChars = value;
            return this;
        }

        /** @param value 每块分析失败后的额外重试次数，范围 0..5，默认 1 @return 当前构建器 */
        public Builder retries(int value) {
            if (value < 0 || value > 5) {
                throw new IllegalArgumentException("retries 必须在 0..5 之间");
            }
            retries = value;
            return this;
        }

        /** @param value 同步进度回调；回调抛出异常会中止任务 @return 当前构建器 */
        public Builder onProgress(Consumer<Progress> value) {
            progress = Objects.requireNonNull(value, "progress");
            return this;
        }

        /**
         * 扫描源码并生成站点。源码可能发送到客户端配置的模型服务，站点与缓存也保存源码快照。
         *
         * @return 入口路径及真实处理统计
         * @throws IOException 扫描或站点写入失败，或 failFast 模式下的文件失败
         */
        public CodeDocResult generate() throws IOException {
            return generateSite(options());
        }

        CodeDocOptions options() throws IOException {
            Path root = Objects.requireNonNull(directory, "请指定 directory").toRealPath();
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("directory 必须是目录");
            }
            Path output = (outputDirectory == null ? root.resolve("codedoc-site") : outputDirectory)
                    .toAbsolutePath().normalize();
            output = canonicalOutput(output);
            if (root.startsWith(output)) {
                throw new IllegalArgumentException("输出目录不能等于源码目录或是其上级目录");
            }
            return new CodeDocOptions(chatClient, root, output, includeTypes, excludeTypes, includes, excludes,
                    charset, title, prompt, instructions, cacheNamespace, incremental, failFast, maxDepth, maxFiles,
                    maxFileBytes, chunkChars, retries, progress);
        }

        private static Set<String> types(String[] values) {
            Set<String> result = new LinkedHashSet<>();
            for (String value : Objects.requireNonNull(values, "types")) {
                String normalized = required(value, "type").toLowerCase(Locale.ROOT);
                if (normalized.startsWith("*.")) {
                    normalized = normalized.substring(2);
                } else if (normalized.startsWith(".")) {
                    normalized = normalized.substring(1);
                }
                if (!normalized.matches("[a-z0-9_+-]+")) {
                    throw new IllegalArgumentException("扩展名不能包含路径或通配符，请通过 include/exclude 指定路径");
                }
                result.add(normalized);
            }
            return result;
        }

        private static List<String> patterns(String[] values) {
            return Arrays.stream(Objects.requireNonNull(values, "patterns"))
                    .map(value -> required(value, "pattern")).toList();
        }

        private static String required(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " 不能为空");
            }
            return value.strip();
        }

        private static int positive(int value, String name) {
            if (value < 1) {
                throw new IllegalArgumentException(name + " 必须 > 0");
            }
            return value;
        }
    }

    private static Path canonicalOutput(Path output) throws IOException {
        // 对尚不存在的目录也解析现有祖先，避免经父目录链接写回扫描树之外的别名路径。
        Path existing = output;
        while (!Files.exists(existing)) {
            existing = existing.getParent();
        }
        return existing.toRealPath().resolve(existing.relativize(output)).normalize();
    }

    private static CodeDocResult generateSite(CodeDocOptions options) throws IOException {
        List<CodeDocResult.Issue> issues = new ArrayList<>();
        List<Path> files = SourceScanner.scan(options, issues);
        List<CodeDocPage> pages = new ArrayList<>();
        CodeDocAnalyzer analyzer = new CodeDocAnalyzer(options);
        int cached = 0;
        int completed = 0;
        for (Path file : files) {
            String relative = SourceScanner.relative(options.directory(), file);
            String stage = "READ";
            String status;
            try {
                String source = SourceScanner.read(file, options);
                stage = "ANALYZE";
                CodeDocAnalyzer.Analysis analysis = analyzer.analyze(relative, SourceScanner.extension(file), source);
                pages.add(analysis.page());
                if (analysis.cached()) {
                    cached++;
                }
                if (analysis.cacheWriteFailed()) {
                    issues.add(new CodeDocResult.Issue(relative, "CACHE", "分析成功，但增量缓存未能写入"));
                }
                status = analysis.cached() ? "CACHED" : "GENERATED";
            } catch (IOException | RuntimeException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("文档生成已中断", exception);
                }
                if (options.failFast()) {
                    throw new IOException("文件处理失败: " + relative + " (" + stage + ")", exception);
                }
                String message = "READ".equals(stage)
                        ? "文件过大、不是合法文本或不可读取，请检查文件及编码配置"
                        : "模型分析或结构校验失败；未生成该文件的文档";
                issues.add(new CodeDocResult.Issue(relative, stage, message));
                status = "FAILED";
            }
            options.progress().accept(new Progress(relative, ++completed, files.size(), status));
        }
        CodeDocResult result = new CodeDocResult(options.outputDirectory().resolve("index.html"),
                files.size(), pages.size(), cached, analyzer.modelCalls(), issues);
        CodeDocSite.write(options, pages, result);
        return result;
    }
}
