package com.chua.maven.support;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Maven 编译结果。
 * <p>封装一次 Maven 编译的完整结果信息，包括产物路径。</p>
 *
 * <h2>字段说明</h2>
 * <table border="1">
 *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
 *   <tr><td>success</td><td>boolean</td><td>是否编译成功</td></tr>
 *   <tr><td>exitCode</td><td>int</td><td>进程退出码</td></tr>
 *   <tr><td>output</td><td>String</td><td>标准输出</td></tr>
 *   <tr><td>errors</td><td>List&lt;String&gt;</td><td>错误信息列表</td></tr>
 *   <tr><td>durationMillis</td><td>long</td><td>编译耗时（毫秒）</td></tr>
 *   <tr><td>projectPath</td><td>String</td><td>项目路径(pom.xml)</td></tr>
 *   <tr><td>goals</td><td>List&lt;String&gt;</td><td>执行的 Maven 目标</td></tr>
 *   <tr><td>profiles</td><td>List&lt;String&gt;</td><td>激活的 Profile</td></tr>
 *   <tr><td>artifacts</td><td>List&lt;String&gt;</td><td>构建产物路径(jar/war等)</td></tr>
 *   <tr><td>projectDir</td><td>String</td><td>项目根目录</td></tr>
 * </table>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MavenCompileResult {

    /**
     * 是否编译成功
     */
    private boolean success;

    /**
     * 进程退出码
     */
    private int exitCode;

    /**
     * 标准输出内容
     */
    private String output;

    /**
     * 错误信息列表
     */
    private List<String> errors;

    /**
     * 编译耗时（毫秒）
     */
    private long durationMillis;

    /**
     * 项目 pom.xml 路径
     */
    private String projectPath;

    /**
     * 项目根目录
     */
    private String projectDir;

    /**
     * 执行的 Maven 目标
     */
    private List<String> goals;

    /**
     * 激活的 Profile
     */
    private List<String> profiles;

    /**
     * 构建产物文件绝对路径列表（jar/war 等）
     */
    private List<String> artifacts;

    public MavenCompileResult() {
    }

    private MavenCompileResult(boolean success, int exitCode, String output, List<String> errors,
                               long durationMillis, String projectPath, String projectDir,
                               List<String> goals, List<String> profiles, List<String> artifacts) {
        this.success = success;
        this.exitCode = exitCode;
        this.output = output;
        this.errors = errors;
        this.durationMillis = durationMillis;
        this.projectPath = projectPath;
        this.projectDir = projectDir;
        this.goals = goals;
        this.profiles = profiles;
        this.artifacts = artifacts;
    }

    // ==================== Getter ====================

    public boolean isSuccess() {
        return success;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getOutput() {
        return output;
    }

    public List<String> getErrors() {
        return errors;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public String getProjectPath() {
        return projectPath;
    }

    public String getProjectDir() {
        return projectDir;
    }

    public List<String> getGoals() {
        return goals;
    }

    public List<String> getProfiles() {
        return profiles;
    }

    /**
     * 获取构建产物路径列表
     *
     * @return 产物文件绝对路径列表
     */
    public List<String> getArtifacts() {
        return artifacts;
    }

    /**
     * 获取主产物路径（第一个 jar 或 war 文件）
     *
     * @return 主产物路径，无产物时返回 null
     */
    public String getMainArtifact() {
        if (artifacts != null && !artifacts.isEmpty()) {
            return artifacts.get(0);
        }
        return null;
    }

    /**
     * 获取主产物文件名
     *
     * @return 文件名，无产物时返回 null
     */
    public String getMainArtifactName() {
        String main = getMainArtifact();
        if (main != null) {
            return new File(main).getName();
        }
        return null;
    }

    /**
     * 是否有产物
     *
     * @return true 有至少一个产物
     */
    public boolean hasArtifacts() {
        return artifacts != null && !artifacts.isEmpty();
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建成功结果并自动扫描产物
     *
     * @param projectPath 项目路径
     * @param output      输出信息
     * @param goals       目标列表
     * @param profiles    Profile 列表
     * @param duration    耗时（毫秒）
     * @return 编译成功结果
     */
    public static MavenCompileResult success(String projectPath, String output,
                                             List<String> goals, List<String> profiles, long duration) {
        String projectDir = resolveProjectDir(projectPath);
        List<String> artifacts = scanArtifacts(projectDir, goals);
        return new MavenCompileResult(true, 0, output, List.of(), duration,
                projectPath, projectDir, goals, profiles, artifacts);
    }

    /**
     * 创建失败结果
     *
     * @param projectPath 项目路径
     * @param exitCode    退出码
     * @param output      输出
     * @param errors      错误列表
     * @param goals       目标列表
     * @param profiles    Profile 列表
     * @param duration    耗时（毫秒）
     * @return 编译失败结果
     */
    public static MavenCompileResult failure(String projectPath, int exitCode, String output,
                                             List<String> errors, List<String> goals, List<String> profiles, long duration) {
        String projectDir = resolveProjectDir(projectPath);
        return new MavenCompileResult(false, exitCode, output, errors, duration,
                projectPath, projectDir, goals, profiles, List.of());
    }

    /**
     * 解析项目根目录
     *
     * @param projectPath pom.xml 路径
     * @return 项目根目录
     */
    private static String resolveProjectDir(String projectPath) {
        if (projectPath == null) {
            return null;
        }
        File pomFile = new File(projectPath);
        File parent = pomFile.getParentFile();
        if (parent == null) {
            return new File(".").getAbsolutePath();
        }
        return parent.getAbsolutePath();
    }

    /**
     * 扫描 target 目录下的构建产物（jar/war）
     * <p>
     * 当执行了 package 或 install 等目标时，自动查找目标目录下的构建产物。
     * 对于多模块项目，会递归查找各子模块的 target 目录。
     * </p>
     *
     * @param projectDir 项目根目录
     * @param goals      执行的目标
     * @return 产物文件列表
     */
    private static List<String> scanArtifacts(String projectDir, List<String> goals) {
        if (projectDir == null || goals == null) {
            return List.of();
        }
        // 只有涉及打包的目标才扫描产物
        boolean packagingGoal = goals.stream().anyMatch(
                g -> g.contains("package") || g.contains("install") || g.contains("deploy") || g.contains("jar")
        );
        if (!packagingGoal) {
            return List.of();
        }

        List<String> result = new ArrayList<>();

        // 先扫描项目根目录 target
        String rootTarget = projectDir + File.separator + "target";
        scanTargetDir(new File(rootTarget), result);
        if (result.isEmpty()) {
            // 多模块项目：扫描子目录
            File[] children = new File(projectDir).listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.isDirectory()) {
                        File childTarget = new File(child, "target");
                        if (childTarget.exists()) {
                            scanTargetDir(childTarget, result);
                        }
                    }
                }
            }
        }

        return result;
    }

    /**
     * 递归扫描指定目录下构建产物
     *
     * @param dir   target 目录
     * @param result 结果列表
     */
    private static void scanTargetDir(File dir, List<String> result) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        // 只取第一层的 jar/war 文件（不包括子目录如 lib）
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && isArtifact(name)) {
                result.add(file.getAbsolutePath());
            }
        }
    }

    /**
     * 判断文件名是否是构建产版本
     *
     * @param fileName 文件名
     * @return 是否产物
     */
    private static boolean isArtifact(String fileName) {
        String lower = fileName.toLowerCase();
        // 常见构建产物：jar
        return lower.endsWith(".jar")
                || lower.endsWith(".war")
                || lower.endsWith(".ear");
    }

    @Override
    public String toString() {
        return "MavenCompileResult{" +
                "success=" + success +
                ", exitCode=" + exitCode +
                ", durationMillis=" + durationMillis +
                ", projectDir='" + projectDir + '\'' +
                ", goals=" + goals +
                ", profiles=" + profiles +
                ", errorsCount=" + (errors != null ? errors.size() : 0) +
                ", artifactsCount=" + (artifacts != null ? artifacts.size() : 0) +
                '}';
    }
}