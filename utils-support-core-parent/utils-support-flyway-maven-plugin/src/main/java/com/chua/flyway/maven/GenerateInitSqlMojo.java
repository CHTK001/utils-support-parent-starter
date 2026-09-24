package com.chua.flyway.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 递归扫描项目目录下的初始化脚本，合并生成完整初始化 SQL 并覆盖旧文件.
 *
 * <p>两种输出模式：</p>
 * <ul>
 *   <li>{@code mode=single}（默认）：全部脚本合并为一份 {@code init-all.sql}，
 *       适合人工建库/交付；</li>
 *   <li>{@code mode=initdata}：按运行期 Flyway 分类口径输出两份可直接被运行期扫描的脚本——
 *       {@code V{版本}__init_all.sql}（结构/补丁，同步执行）与
 *       {@code V{版本}__initdata_all.sql}（初始化数据，异步执行），版本取被合并脚本中的最高版本。</li>
 * </ul>
 *
 * <p>合并语义为纯文本拼接，默认按「版本号数值升序 + 相对路径」排序（与运行期扫描顺序一致），
 * 不做方言转换，保持脚本的通用性。</p>
 *
 * <p>幂等：每次执行覆盖输出的合并文件；生成文件本身会被排除在输入之外，避免重复合并。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Mojo(name = "generate-init-sql", defaultPhase = LifecyclePhase.GENERATE_RESOURCES, threadSafe = true)
public class GenerateInitSqlMojo extends AbstractMojo {

    /**
     * 输出模式：单份 init-all.sql.
     */
    private static final String MODE_SINGLE = "single";

    /**
     * 输出模式：init/initdata 两份运行期可执行脚本.
     */
    private static final String MODE_INIT_DATA = "initdata";

    /**
     * 默认包含模式：递归匹配任意层级的 db/init 目录下 .sql 文件.
     */
    private static final List<String> DEFAULT_INCLUDES = Arrays.asList("**/db/init/**/*.sql");

    /**
     * 默认排除模式：构建产物与依赖目录.
     */
    private static final List<String> DEFAULT_EXCLUDES = Arrays.asList(
            "**/target/**",
            "**/build/**",
            "**/.git/**",
            "**/node_modules/**");

    /**
     * 扫描根目录，默认当前模块基于目录.
     */
    @Parameter(defaultValue = "${project.basedir}", property = "flyway.sourceRoot", required = true)
    private File sourceRoot;

    /**
     * 输出文件路径，存在则覆盖.
     */
    @Parameter(defaultValue = "${project.build.directory}/db/init-all.sql", property = "flyway.outputFile", required = true)
    private File outputFile;

    /**
     * 包含通配模式（相对 sourceRoot 的 glob）.
     */
    @Parameter(property = "flyway.includes")
    private List<String> includes;

    /**
     * 排除通配模式（相对 sourceRoot 的 glob）.
     */
    @Parameter(property = "flyway.excludes")
    private List<String> excludes;

    /**
     * 脚本读取字符集.
     */
    @Parameter(defaultValue = "UTF-8", property = "flyway.encoding")
    private String encoding;

    /**
     * 是否输出总头部说明.
     */
    @Parameter(defaultValue = "true", property = "flyway.header")
    private boolean header;

    /**
     * 头部是否包含生成时间戳；关闭可获得确定性输出.
     */
    @Parameter(defaultValue = "false", property = "flyway.stampHeader")
    private boolean stampHeader;

    /**
     * 未扫描到脚本时是否失败.
     */
    @Parameter(defaultValue = "true", property = "flyway.failIfNoScripts")
    private boolean failIfNoScripts;

    /**
     * 跳过本插件.
     */
    @Parameter(defaultValue = "false", property = "flyway.skip")
    private boolean skip;

    /**
     * 输出模式：{@code single}=单份 init-all.sql（默认）；{@code initdata}=输出
     * {@code V{版本}__init_all.sql} 与 {@code V{版本}__initdata_all.sql} 两份运行期可执行脚本.
     *
     * <p>参数命名统一使用 {@code flyway.merge.*} 前缀，避免与 Spring Boot 依赖管理中的
     * {@code flyway.version} 等项目属性撞名。</p>
     */
    @Parameter(defaultValue = "single", property = "flyway.merge.mode")
    private String mode;

    /**
     * initdata 模式的输出目录，默认与 {@link #outputFile} 同目录（{@code target/db}）.
     */
    @Parameter(property = "flyway.merge.outputDir")
    private File outputDir;

    /**
     * 合并文件版本号；默认取被合并脚本中的最高版本，缺省回退 {@code 1.0.0}.
     */
    @Parameter(property = "flyway.merge.version")
    private String version;

    /**
     * init 合并文件的描述段（{@code V{版本}__{描述}.sql}），需以 {@code init_} 开头.
     */
    @Parameter(defaultValue = "init_all", property = "flyway.merge.initDescription")
    private String initDescription;

    /**
     * initdata 合并文件的描述段，需以 {@code initdata_} 开头.
     */
    @Parameter(defaultValue = "initdata_all", property = "flyway.merge.initDataDescription")
    private String initDataDescription;

    /**
     * 是否按「版本号数值升序 + 相对路径」排序（与运行期扫描顺序一致），默认开启.
     */
    @Parameter(defaultValue = "true", property = "flyway.merge.versionSort")
    private boolean versionSort;

    /**
     * 执行脚本扫描与合并.
     *
     * @throws MojoExecutionException 读写文件失败时抛出
     * @throws MojoFailureException   无脚本且配置为失败时抛出
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("flyway:generate-init-sql 已跳过");
            return;
        }
        if (sourceRoot == null || !sourceRoot.isDirectory()) {
            throw new MojoFailureException("扫描根目录不存在或不是目录: " + sourceRoot);
        }
        final Path root = sourceRoot.toPath();
        final Path output = outputFile.toPath();
        final Charset charset = resolveCharset(encoding);
        final List<String> effectiveIncludes = includes == null || includes.isEmpty() ? DEFAULT_INCLUDES : includes;
        final List<String> effectiveExcludes = excludes == null || excludes.isEmpty() ? DEFAULT_EXCLUDES : excludes;
        if (MODE_INIT_DATA.equalsIgnoreCase(mode)) {
            generateInitDataFiles(root, output, effectiveIncludes, effectiveExcludes, charset);
            return;
        }
        try {
            final List<Path> scripts = collectScripts(root, List.of(output), effectiveIncludes, effectiveExcludes);
            if (scripts.isEmpty()) {
                handleEmpty();
                return;
            }
            if (versionSort) {
                InitSqlMerger.sortByVersionThenPath(scripts, root);
            }
            writeMerged(root, output, scripts, charset);
        } catch (IOException e) {
            throw new MojoExecutionException("生成合并 init SQL 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 扫描并过滤掉输出文件本身，返回待合并脚本列表.
     *
     * @param root              扫描根目录
     * @param excludedOutputs   需要从输入中排除的输出文件路径
     * @param effectiveIncludes 生效的包含模式
     * @param effectiveExcludes 生效的排除模式
     * @return 待合并脚本列表
     * @throws IOException 目录遍历失败时抛出
     */
    private List<Path> collectScripts(final Path root, final List<Path> excludedOutputs,
                                      final List<String> effectiveIncludes, final List<String> effectiveExcludes) throws IOException {
        final List<Path> excluded = new ArrayList<>(excludedOutputs.size());
        for (final Path output : excludedOutputs) {
            excluded.add(output.toAbsolutePath().normalize());
        }
        final List<Path> scanned = InitSqlMerger.scan(root, effectiveIncludes, effectiveExcludes);
        final List<Path> filtered = new ArrayList<>(scanned.size());
        for (final Path script : scanned) {
            if (!excluded.contains(script.toAbsolutePath().normalize())) {
                filtered.add(script);
            }
        }
        return filtered;
    }

    /**
     * 处理未扫描到脚本的情况.
     *
     * @throws MojoFailureException 配置为失败时抛出
     */
    private void handleEmpty() throws MojoFailureException {
        final String message = "未扫描到任何初始化脚本: root=" + sourceRoot + " includes=" + includes;
        if (failIfNoScripts) {
            throw new MojoFailureException(message);
        }
        getLog().warn(message);
    }

    /**
     * 合并脚本并写入输出文件（覆盖），打印统计.
     *
     * @param root    扫描根目录
     * @param output  输出文件路径
     * @param scripts 待合并脚本
     * @param charset 字符集
     * @throws IOException 读写失败时抛出
     */
    private void writeMerged(final Path root, final Path output, final List<Path> scripts, final Charset charset) throws IOException {
        writeMergedFile(root, output, scripts, charset, null);
    }

    /**
     * 合并脚本并写入输出文件（可自定义头部标题）.
     *
     * @param root    扫描根目录
     * @param output  输出文件路径
     * @param scripts 待合并脚本
     * @param charset 字符集
     * @param title   头部标题，为空时用默认标题
     * @throws IOException 读写失败时抛出
     */
    private void writeMergedFile(final Path root, final Path output, final List<Path> scripts,
                                 final Charset charset, final String title) throws IOException {
        final String merged = title == null
                ? InitSqlMerger.merge(scripts, root, charset, header, stampHeader)
                : InitSqlMerger.merge(scripts, root, charset, header, stampHeader, title);
        final Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(output, merged.getBytes(charset));
        getLog().info("已生成合并 SQL: " + output.toAbsolutePath()
                + "，合并脚本 " + scripts.size() + " 个，共 " + merged.length() + " 字符");
    }

    /**
     * 生成运行期可直接执行的 init / initdata 两份合并脚本.
     *
     * <p>命名遵循 {@code V{版本}__{描述}.sql}，与运行期分类口径一致：init 组包含全部非 initdata
     * 脚本（结构、补丁、普通脚本，同步执行），initdata 组仅含 {@code V*__initdata_*}
     * （表结构之后异步执行）；两份共享被合并脚本中的最高版本号（去发布后缀，保证 STABLE 过滤通过）。</p>
     *
     * @param root     扫描根目录
     * @param single   单文件模式的输出路径（一并从输入中排除）
     * @param includes 生效的包含模式
     * @param excludes 生效的排除模式
     * @param charset  字符集
     * @throws MojoExecutionException 读写失败时抛出
     * @throws MojoFailureException   无脚本且配置为失败时抛出
     */
    private void generateInitDataFiles(final Path root, final Path single,
                                       final List<String> includes, final List<String> excludes,
                                       final Charset charset) throws MojoExecutionException, MojoFailureException {
        final Path directory = resolveOutputDirectory(single);
        try {
            final List<Path> scripts = collectScripts(root, List.of(single), includes, excludes);
            removePreviousMerged(directory, scripts);
            if (scripts.isEmpty()) {
                handleEmpty();
                return;
            }
            if (versionSort) {
                InitSqlMerger.sortByVersionThenPath(scripts, root);
            }
            final List<Path> initScripts = new ArrayList<>();
            final List<Path> initDataScripts = new ArrayList<>();
            for (final Path script : scripts) {
                if (InitSqlMerger.isInitData(script)) {
                    initDataScripts.add(script);
                } else {
                    initScripts.add(script);
                }
            }
            final String resolvedVersion = resolveVersion(scripts);
            final Path initFile = directory.resolve("V" + resolvedVersion + "__" + initDescription + ".sql");
            final Path dataFile = directory.resolve("V" + resolvedVersion + "__" + initDataDescription + ".sql");
            if (initScripts.isEmpty()) {
                getLog().warn("未扫描到 init 脚本，跳过生成 " + initFile.getFileName());
            } else {
                writeMergedFile(root, initFile, initScripts, charset,
                        "Flyway 合并生成：init（表结构/补丁，运行期同步执行）");
            }
            if (initDataScripts.isEmpty()) {
                getLog().warn("未扫描到 initdata 脚本，跳过生成 " + dataFile.getFileName());
            } else {
                writeMergedFile(root, dataFile, initDataScripts, charset,
                        "Flyway 合并生成：initdata（初始化数据，运行期异步执行）");
            }
        } catch (IOException e) {
            throw new MojoExecutionException("生成合并 init/initdata SQL 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 排除输出目录下上一轮生成的合并文件，避免其被再次当作输入.
     *
     * @param directory 输出目录
     * @param scripts   已扫描脚本列表（原地过滤）
     */
    private void removePreviousMerged(final Path directory, final List<Path> scripts) {
        final Path directoryNormalized = directory.toAbsolutePath().normalize();
        final String initSuffix = "__" + initDescription + ".sql";
        final String dataSuffix = "__" + initDataDescription + ".sql";
        scripts.removeIf(script -> {
            final Path absolute = script.toAbsolutePath().normalize();
            final Path parent = absolute.getParent();
            if (parent == null || !parent.equals(directoryNormalized)) {
                return false;
            }
            final String name = absolute.getFileName().toString();
            return name.startsWith("V") && (name.endsWith(initSuffix) || name.endsWith(dataSuffix));
        });
    }

    /**
     * 解析 initdata 模式输出目录：优先显式配置，其次与 {@link #outputFile} 同目录.
     *
     * @param singleOutput 单文件输出路径
     * @return 输出目录
     */
    private Path resolveOutputDirectory(final Path singleOutput) {
        if (outputDir != null) {
            return outputDir.toPath();
        }
        final Path parent = singleOutput.toAbsolutePath().getParent();
        return parent != null ? parent : singleOutput.toAbsolutePath();
    }

    /**
     * 解析合并文件版本号：优先显式配置，其次取脚本最高版本，最后回退 {@code 1.0.0}.
     *
     * @param scripts 待合并脚本
     * @return 版本号（已去发布后缀）
     */
    private String resolveVersion(final List<Path> scripts) {
        String resolved = InitSqlMerger.cleanVersion(version);
        if (resolved == null) {
            resolved = InitSqlMerger.maxVersion(scripts);
        }
        return resolved == null ? "1.0.0" : resolved;
    }

    /**
     * 解析字符集名称，非法时回退 UTF-8.
     *
     * @param name 字符集名称
     * @return 字符集
     */
    private Charset resolveCharset(final String name) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            getLog().warn("无效字符集 " + name + "，回退 UTF-8");
            return StandardCharsets.UTF_8;
        }
    }
}
