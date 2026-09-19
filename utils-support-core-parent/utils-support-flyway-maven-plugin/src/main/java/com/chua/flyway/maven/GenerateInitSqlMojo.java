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
 * 递归扫描项目目录下的初始化脚本，合并生成一份完整的 init SQL 并覆盖旧文件.
 *
 * <p>用途：将按模块拆分的 {@code db/init/*.sql}（各模块全量建表 + 初始化数据）聚合为
 * 单一可直接执行的完整初始化脚本。合并语义为纯文本拼接，按相对路径稳定排序，
 * 不做方言转换，保持脚本的通用性。</p>
 *
 * <p>幂等：每次执行覆盖 {@code outputFile}；生成文件本身会被排除在输入之外，
 * 避免重复合并。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Mojo(name = "generate-init-sql", defaultPhase = LifecyclePhase.GENERATE_RESOURCES, threadSafe = true)
public class GenerateInitSqlMojo extends AbstractMojo {

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
        try {
            final List<Path> scripts = collectScripts(root, output, effectiveIncludes, effectiveExcludes);
            if (scripts.isEmpty()) {
                handleEmpty();
                return;
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
     * @param output            输出文件路径（需从输入中排除）
     * @param effectiveIncludes 生效的包含模式
     * @param effectiveExcludes 生效的排除模式
     * @return 待合并脚本列表
     * @throws IOException 目录遍历失败时抛出
     */
    private List<Path> collectScripts(final Path root, final Path output,
                                      final List<String> effectiveIncludes, final List<String> effectiveExcludes) throws IOException {
        final Path outputNormalized = output.toAbsolutePath().normalize();
        final List<Path> scanned = InitSqlMerger.scan(root, effectiveIncludes, effectiveExcludes);
        final List<Path> filtered = new ArrayList<>(scanned.size());
        for (final Path script : scanned) {
            if (!script.toAbsolutePath().normalize().equals(outputNormalized)) {
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
        final String merged = InitSqlMerger.merge(scripts, root, charset, header, stampHeader);
        final Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(output, merged.getBytes(charset));
        getLog().info("已生成合并 init SQL: " + output.toAbsolutePath()
                + "，合并脚本 " + scripts.size() + " 个，共 " + merged.length() + " 字符");
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
