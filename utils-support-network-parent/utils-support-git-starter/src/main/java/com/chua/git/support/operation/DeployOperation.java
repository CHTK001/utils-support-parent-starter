package com.chua.git.support.operation;

import com.chua.git.support.GitClient;
import com.chua.git.support.listener.GitFileListener;
import com.chua.git.support.listener.GitProgressListener;
import com.chua.git.support.model.DeployConfig;
import com.chua.git.support.model.DeployResult;
import lombok.extern.slf4j.Slf4j;

import com.chua.common.support.spi.ServiceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 部署操作流水线：自动 pull → compile → deploy。
 *
 * <p>执行顺序：</p>
 * <ol>
 *   <li>打开仓库（如未打开则自动 open）</li>
 *   <li>执行 git pull（拉取最新代码）</li>
 *   <li>遍历 SPI 注册的 {@link Deployer} 实现，匹配 {@link DeployConfig}</li>
 *   <li>调用 deployer.deploy() 完成编译与部署</li>
 * </ol>
 *
 * <pre>用法示例：
 * {@code
 * // 1. 基础用法（SPI 自动发现 Deployer）
 * DeployResult r = client.deploy()
 *         .projectPath("pom.xml")
 *         .goals("clean", "package")
 *         .execute();
 *
 * // 2. 异步执行
 * CompletableFuture<DeployResult> f = client.deploy()
 *         .async()
 *         .skipTests(true)
 *         .execute();
 *
 * // 3. 进度回调
 * client.deploy()
 *         .gitProgressListener(progress)
 *         .goals("deploy", "clean", "package")
 *         .deployTarget("/opt/app/")
 *         .execute();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DeployOperation {

    /**
     * 所属 GitClient。
     */
    private final GitClient client;

    /**
     * 项目 pom.xml 路径（相对仓库根目录）。
     */
    private String projectPath = "pom.xml";

    /**
     * Maven 目标列表。
     */
    private List<String> goals = List.of("clean", "compile", "package");

    /**
     * Maven Profile 列表。
     */
    private List<String> profiles = List.of();

    /**
     * 是否跳过测试。
     */
    private boolean skipTests = true;

    /**
     * JDK 版本。
     */
    private String jdkVersion;

    /**
     * 部署目标路径。
     */
    private String deployTargetPath;

    /**
     * 文件变更监听器。
     */
    private GitFileListener fileListener;

    /**
     * Git 操作进度监听器。
     */
    private GitProgressListener gitProgressListener;

    /**
     * 自定义部署器（刷新 SPI 自动发现）。
     */
    private Deployer customDeployer;

    /**
     * 异步标志。
     */
    private boolean asyncMode;

    /**
     * 构建操作实例（仅框架内部调用）。
     *
     * @param client 所属 GitClient
     */
    public DeployOperation(GitClient client) {
        this.client = client;
    }

    // ==================== 链式配置方法 ====================

    /**
     * 设置项目 pom.xml 相对路径。
     *
     * @param projectPath 相对仓库根目录的 pom.xml 路径（默认 "pom.xml"）
     * @return 当前操作实例
     */
    public DeployOperation projectPath(String projectPath) {
        this.projectPath = projectPath;
        return this;
    }

    /**
     * 设置 Maven 编译目标列表。
     *
     * @param goals 目标（如 "clean"、"compile"、"package"）
     * @return 当前操作实例
     */
    public DeployOperation goals(String... goals) {
        this.goals = List.of(goals);
        return this;
    }

    /**
     * 设置 Maven Profile。
     *
     * @param profiles Profile 列表
     * @return 当前操作实例
     */
    public DeployOperation profiles(String... profiles) {
        this.profiles = List.of(profiles);
        return this;
    }

    /**
     * 设置是否跳过测试。
     *
     * @param skipTests true 跳过测试
     * @return 当前操作实例
     */
    public DeployOperation skipTests(boolean skipTests) {
        this.skipTests = skipTests;
        return this;
    }

    /**
     * 设置 JDK 版本。
     *
     * @param jdkVersion 版本字符串（如 "25"）
     * @return 当前操作实例
     */
    public DeployOperation jdkVersion(String jdkVersion) {
        this.jdkVersion = jdkVersion;
        return this;
    }

    /**
     * 设置部署目标路径（本地或远程 Directory）。
     *
     * @param targetPath 目标路径
     * @return 当前操作实例
     */
    public DeployOperation deployTarget(String targetPath) {
        this.deployTargetPath = targetPath;
        return this;
    }

    /**
     * 设置文件变更监听器。
     *
     * @param listener 监听器
     * @return 当前操作实例
     */
    public DeployOperation fileListener(GitFileListener listener) {
        this.fileListener = listener;
        return this;
    }

    /**
     * 设置 Git 进度监听器（pull 阶段使用）。
     *
     * @param listener 进度监听器
     * @return 当前操作实例
     */
    public DeployOperation gitProgressListener(GitProgressListener listener) {
        this.gitProgressListener = listener;
        return this;
    }

    /**
     * 指定自定义部署器（覆盖 SPI 自动发现）。
     *
     * @param deployer 部署器实现
     * @return 当前操作实例
     */
    public DeployOperation deployer(Deployer deployer) {
        this.customDeployer = deployer;
        return this;
    }

    /**
     * 设为异步模式。
     *
     * @return 当前操作实例
     */
    public DeployOperation async() {
        this.asyncMode = true;
        return this;
    }

    // ==================== 执行方法 ====================

    /**
     * 执行完整流水线：pull → find deployer → deploy。
     *
     * @return 同步模式返回 {@link DeployResult}，异步模式返回 {@link CompletableFuture}{@code <DeployResult>}
     */
    @SuppressWarnings("unchecked")
    public Object execute() {
        if (asyncMode) {
            return CompletableFuture.supplyAsync(this::doDeploy);
        }
        return doDeploy();
    }

    /**
     * 执行实际部署流水线。
     *
     * <ol>
     *   <li>打开仓库，执行 git pull</li>
     *   <li>查找可匹配的 Deployer：优先使用 {@link #customDeployer}，否则通过 SPI 发现</li>
     *   <li>调用 Deployer.deploy() 完成 <li>
     * </ol>
     *
     * @return 部署结果
     */
    private DeployResult doDeploy() {
        long start = System.currentTimeMillis();

        // 确认仓库打开并拉取最新
        client.open();

        // 构建 DeployConfig
        DeployConfig config = buildConfig();

        // 查找 Deployer 实现
        Deployer deployer = findDeployer(config);
        if (deployer == null) {
            return DeployResult.failure("未找到能处理此部署配置的 Deployer 实现。请确保 maven-starter 在 classpath 中，或手动调用 deployer()。");
        }

        try {
            // 执行 git pull
            log.info("Deploy 流水线开始: pull -> {}", client.getLocalPath());
            client.pull(fileListener, gitProgressListener);

            // 调用部署器
            DeployResult result = deployer.deploy(client, config);
            long elapsed = System.currentTimeMillis() - start;
            log.info("Deploy 流水线完成: success={}, 耗时={}ms", result.success(), elapsed);
            return result;
        } catch (Exception e) {
            log.error("Deploy 执行异常", e);
            return DeployResult.failure("部署失败: " + e.getMessage());
        }
    }

    /**
     * 根据链式配置构造 {@link DeployConfig}。
     */
    private DeployConfig buildConfig() {
        return new DeployConfig(projectPath, goals, profiles, skipTests, jdkVersion, deployTargetPath);
    }

    /**
     * 查找可用的 Deployer 实现。
     *
     * <p>优先使用自定义部署器，否则通过 {@link ServiceProvider} 加载
     * 并匹配 supports。通过 {@code META-INF/extensions} 文件注册实现。</p>
     */
    private Deployer findDeployer(DeployConfig config) {
        if (customDeployer != null) {
            return customDeployer;
        }
        Map<String, Deployer> deployerMap = ServiceProvider.of(Deployer.class).list();
        for (Deployer deployer : deployerMap.values()) {
            if (deployer.supports(config)) {
                log.info("发现 Deployer: {}", deployer.name());
                return deployer;
            }
        }
        return null;
    }
}