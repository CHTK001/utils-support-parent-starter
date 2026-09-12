package com.chua.maven.support;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * MavenClient 链式构建器。
 * <p>
 * 提供流式 API 配置 Maven 编译参数，支持同步和异步编译。
 * 基于 {@link MavenClient} 执行实际编译操作。
 * </p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 同步编译
 * MavenCompileResult result = MavenClient.create()
 *     .projectPath("/path/to/project/pom.xml")
 *     .goal("compile")
 *     .profile("dev")
 *     .skipTests(true)
 *     .onProgress((msg, pct) -> System.out.printf("[%d%%] %s%n", pct, msg))
 *     .execute();
 *
 * // 异步编译
 * MavenClient client = MavenClient.create().projectPath("pom.xml").goal("package").build();
 * Future&lt;MavenCompileResult&gt; future = client.executeAsync();
 *
 * // 链式执行多个目标
 * MavenCompileResult result = MavenClient.create()
 *     .projectPath("pom.xml")
 *     .goal("clean", "compile", "package")
 *     .execute();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MavenClientBuilder {

    /**
     * 项目 pom.xml 路径
     */
    private String projectPath;

    /**
     * Maven 目标列表
     */
    private List<String> goals;

    /**
     * 激活的 Profile 列表
     */
    private List<String> profiles;

    /**
     * 是否跳过测试
     */
    private boolean skipTests;

    /**
     * 是否静默模式
     */
    private boolean quiet;

    /**
     * 是否调试模式
     */
    private boolean debug;

    /**
     * 是否离线模式
     */
    private boolean offline;

    /**
     * JDK 版本
     */
    private String jdkVersion;

    /**
     * 编译进度回调
     */
    private MavenCompilerProgress progressCallback;

    /**
     * 编译生命周期回调
     */
    private MavenCompilerCallback compilerCallback;

    /**
     * 自定义属性
     */
    private Properties properties;

    /**
     * 线程池（用于异步编译）
     */
    private ExecutorService executor;

    /**
     * 默认 Maven 目标
     */
    private static final List<String> DEFAULT_GOALS = List.of("compile");

    MavenClientBuilder() {
        this.goals = new ArrayList<>();
        this.profiles = new ArrayList<>();
        this.properties = new Properties();
    }

    // ==================== Getter ====================

    /** 获取ProjectPath */
    public String getProjectPath() {
        return projectPath;
    }

    /** 获取Goals */
    public List<String> getGoals() {
        return goals;
    }

    /** 获取EffectiveGoals */
    public List<String> getEffectiveGoals() {
        return goals.isEmpty() ? DEFAULT_GOALS : goals;
    }

    /** 获取Profiles */
    public List<String> getProfiles() {
        return profiles;
    }

    /** 是否跳过Tests */
    public boolean isSkipTests() {
        return skipTests;
    }

    /** 是否Quiet */
    public boolean isQuiet() {
        return quiet;
    }

    /** 是否调试 */
    public boolean isDebug() {
        return debug;
    }

    /** 是否Offline */
    public boolean isOffline() {
        return offline;
    }

    /** 获取JdkVersion */
    public String getJdkVersion() {
        return jdkVersion;
    }

    /** 获取ProgressCallback */
    public MavenCompilerProgress getProgressCallback() {
        return progressCallback;
    }

    /** 获取CompilerCallback */
    public MavenCompilerCallback getCompilerCallback() {
        return compilerCallback;
    }

    /** 获取Properties */
    public Properties getProperties() {
        return properties;
    }

    /** 获取Executor */
    public ExecutorService getExecutor() {
        return executor;
    }

    // ==================== 构建 API ====================

    /**
     * 设置项目 pom.xml 路径
     *
     * @param path pom.xml 文件路径或项目目录路径
     * @return this
     */
    public MavenClientBuilder projectPath(String path) {
        this.projectPath = resolvePomPath(path);
        return this;
    }

    /**
     * 设置 Maven 目标（替换现有目标列表）
     *
     * @param goals 目标列表
     * @return this
     */
    public MavenClientBuilder goals(List<String> goals) {
        this.goals = new ArrayList<>(goals);
        return this;
    }

    /**
     * 添加 Maven 目标
     *
     * @param goals 目标列表
     * @return this
     */
    public MavenClientBuilder goal(String... goals) {
        for (String goal : goals) {
            this.goals.add(goal);
        }
        return this;
    }

    /**
     * 设置 Maven Profile
     *
     * @param profiles Profile 列表
     * @return this
     */
    public MavenClientBuilder profile(String... profiles) {
        for (String profile : profiles) {
            this.profiles.add(profile);
        }
        return this;
    }

    /**
     * 设置激活的 Profile 列表（清空并替换）
     *
     * @param profiles Profile ID 列表
     * @return this
     */
    public MavenClientBuilder profiles(List<String> profiles) {
        this.profiles = new ArrayList<>(profiles);
        return this;
    }

    /**
     * 设置为测试模式（等效于 {@code goal("test")}）
     *
     * @return this
     */
    public MavenClientBuilder test() {
        this.goals.clear();
        this.goals.add("test");
        return this;
    }

    /**
     * 设置为构建模式（等效于 {@code goal("clean", "compile", "package")}）
     *
     * @return this
     */
    public MavenClientBuilder packageTask() {
        this.goals.clear();
        this.goals.add("clean");
        this.goals.add("compile");
        this.goals.add("package");
        return this;
    }

    /**
     * 设置为部署模式（等效于 {@code goal("clean", "compile", "package", "deploy")}）
     *
     * @return this
     */
    public MavenClientBuilder deploy() {
        this.goals.clear();
        this.goals.add("clean");
        this.goals.add("compile");
        this.goals.add("package");
        this.goals.add("deploy");
        return this;
    }

    /**
     * 只编译
     *
     * @return this
     */
    public MavenClientBuilder compile() {
        this.goals.clear();
        this.goals.add("compile");
        return this;
    }

    /**
     * 只安装到本地仓库
     *
     * @return this
     */
    public MavenClientBuilder install() {
        this.goals.clear();
        this.goals.add("clean");
        this.goals.add("install");
        return this;
    }

    /**
     * 是否跳过测试
     *
     * @param skip 是否跳过
     * @return this
     */
    public MavenClientBuilder skipTests(boolean skip) {
        this.skipTests = skip;
        return this;
    }

    /**
     * 静默模式
     *
     * @param quiet 是否静默
     * @return this
     */
    public MavenClientBuilder quiet(boolean quiet) {
        this.quiet = quiet;
        return this;
    }

    /**
     * 调试模式
     *
     * @param debug 是否调试
     * @return this
     */
    public MavenClientBuilder debug(boolean debug) {
        this.debug = debug;
        return this;
    }

    /**
     * 离线模式
     *
     * @param offline 是否离线
     * @return this
     */
    public MavenClientBuilder offline(boolean offline) {
        this.offline = offline;
        return this;
    }

    /**
     * 设置 JDK 版本
     *
     * @param jdkVersion JDK 版本号，如 "17", "21"
     * @return this
     */
    public MavenClientBuilder jdkVersion(String jdkVersion) {
        this.jdkVersion = jdkVersion;
        return this;
    }

    /**
     * 设置编译进度回调
     *
     * @param callback 进度回调
     * @return this
     */
    public MavenClientBuilder onProgress(MavenCompilerProgress callback) {
        this.progressCallback = callback;
        return this;
    }

    /**
     * 设置编译生命周期回调
     *
     * @param callback 生命周期回调
     * @return this
     */
    public MavenClientBuilder onCallback(MavenCompilerCallback callback) {
        this.compilerCallback = callback;
        return this;
    }

    /**
     * 添加系统属性
     *
     * @param key   属性键
     * @param value 属性值
     * @return this
     */
    public MavenClientBuilder property(String key, String value) {
        this.properties.put(key, value);
        return this;
    }

    /**
     * 批量添加系统属性
     *
     * @param properties 属性 Map
     * @return this
     */
    public MavenClientBuilder properties(Map<String, String> properties) {
        this.properties.putAll(properties);
        return this;
    }

    /**
     * 设置自定义线程池
     *
     * @param executor 线程池
     * @return this
     */
    public MavenClientBuilder executor(ExecutorService executor) {
        this.executor = executor;
        return this;
    }

    /**
     * 执行编译（同步阻塞）
     *
     * @return 编译结果
     */
    public MavenCompileResult execute() {
        return MavenClient.execute(this);
    }

    /**
     * 执行编译（异步），返回 Future
     * <p>
     * 使用配置的线程池或默认线程池执行。通过返回的 Future 可取消编译。
     * </p>
     *
     * @return Future，可通过 get() 获取编译结果
     */
    public Future<MavenCompileResult> executeAsync() {
        ExecutorService pool = executor != null ? executor : Executors.newSingleThreadExecutor();
        return pool.submit(() -> MavenClient.execute(this));
    }

    /**
     * 构建 MavenClient 配置并返回客户端实例。
     *
     * @return MavenClient 实例
     */
    public MavenClient build() {
        return new MavenClient(this);
    }

    /**
     * 构建并编译，然后返回可用于部署的 DeployClient。
     * <p>
     * 这是一个便捷方法，等价于先 {@link #execute()} 再获取 {@link MavenDeployClient}。
     * 如果编译失败，部署客户端中的 result 将包含错误信息。
     * </p>
     *
     * <pre>{@code
     * MavenDeployClient deploy = MavenClient.create()
     *     .projectPath("pom.xml")
     *     .goal("clean", "package")
     *     .compileAndDeploy();
     *
     * MavenCompileResult result = deploy.getResult();
     * if (result.isSuccess()) {
     *     // 部署到指定目录
     *     deploy.deployTo("/opt/app/");
     *
     *     // 或部署到远程服务器
     *     deploy.deployToSsh("192.168.1.100", 22, "root", "password", "/opt/app/");
     * }
     * }</pre>
     *
     * @return MavenDeployClient 部署客户端
     */
    public MavenDeployClient compileAndDeploy() {
        MavenCompileResult result = execute();
        return new MavenDeployClient(result);
    }

    /**
     * 构建并异步编译，然后返回部署客户端。
     *
     * @return Future，异步完成后可通过 get() 获取 MavenDeployClient
     */
    public Future<MavenDeployClient> compileAndDeployAsync() {
        ExecutorService pool = executor != null ? executor : Executors.newSingleThreadExecutor();
        return pool.submit((java.util.concurrent.Callable<MavenDeployClient>) this::compileAndDeploy);
    }

    /**
     * 将目标列表转为空格分隔的字符串，没有配置目标时默认使用 "compile"。
     *
     * @return 目标字符串
     */
    public String getGoalString() {
        List<String> effectiveGoals = goals.isEmpty() ? DEFAULT_GOALS : goals;
        return String.join(" ", effectiveGoals);
    }

    /**
     * 解析 pom.xml 路径
     *
     * @param path 用户输入的路径
     * @return 解析后的 pom.xml 绝对路径
     */
    private String resolvePomPath(String path) {
        if (path == null) {
            return new File("pom.xml").getAbsolutePath();
        }
        File file = new File(path);
        if (file.isDirectory()) {
            return new File(file, "pom.xml").getAbsolutePath();
        }
        return file.getAbsolutePath();
    }
}