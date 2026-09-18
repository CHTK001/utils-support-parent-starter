package com.chua.maven.support;

import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.MavenInvocationException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
* Maven 链式客户端，基于 Maven Invoker 实现 pom.xml 的编译与构建。
*
* <p>提供同步和异步编译能力，支持进度回调、生命周期回调、自定义编译目标与 Profile。</p>
*
* <h2>使用示例</h2>
* <pre>{@code
* // 链式创建并同步编译
* MavenCompileResult result = MavenClient.create()
*     .projectPath("G:/project/pom.xml")
*     .goal("clean", "compile")
*     .profile("dev")
*     .skipTests(true)
*     .onProgress((msg, pct) -> System.out.printf("[%d%%] %s%n", pct, msg))
*     .onCallback(new MavenCompilerCallback() {
*         @Override
*         public void onSuccess(MavenCompileResult r) {
*             System.out.println("编译成功, 耗时: " + r.getDurationMillis() + "ms");
*         }
*     })
*     .execute();
*
* // 异步编译
* MavenClient client = MavenClient.create().projectPath("pom.xml").goal("compile").build();
* Future&lt;MavenCompileResult&gt; future = client.executeAsync();
*
* // 取消编译
* future.cancel(true);
* }</pre>mpile结果&gt; 期货 = 客户端.执行异步();
*
* // 取消编译
* 期货.cancel(true);
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
public class MavenClient implements AutoCloseable {

    /** 日志 */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MavenClient.class);

    /**
    * 项目 pom.xml 路径
    */
    private final String projectPath;

    /**
    * Maven 目标列表
    */
    private final List<String> goals;

    /**
    * 激活的 配置文件 列表
    */
    private final List<String> profiles;

    /**
    * 是否跳过测试
    */
    private final boolean skipTests;

    /**
    * 是否静默模式
    */
    private final boolean quiet;

    /**
    * 是否调试模式
    */
    private final boolean debug;

    /**
    * 是否离线模式
    */
    private final boolean offline;

    /**
    * JDK 版本
    */
    private final String jdkVersion;

    /**
    * 编译进度回调
    */
    private final MavenCompilerProgress progressCallback;

    /**
    * 编译生命周期回调
    */
    private final MavenCompilerCallback compilerCallback;

    /**
    * 自定义属性
    */
    private final Properties properties;

    /**
    * 编译器退出常量
    */
    private static final int EXIT_SUCCESS = 0;

    /**
    * 默认 Maven 目标
    */
    private static final String DEFAULT_GOAL = "compile";

    /**
    * 编译阶段进度阈值
    */
    private static final int PROGRESS_VALIDATION = 5;
    /** 进步_resolve */
    private static final int PROGRESS_RESOLVE = 15;
    /** 进步_compile */
    private static final int PROGRESS_COMPILE = 50;
    /** 进步_测试 */
    private static final int PROGRESS_TEST = 75;
    /** 进步_包 */
    private static final int PROGRESS_PACKAGE = 90;
    /** 进步_完成 */
    private static final int PROGRESS_COMPLETE = 100;

    /**
    * Maven 输出解析正则
    */
    private static final Pattern BUILD_SUCCESS_PATTERN = Pattern.compile("BUILD\\s+SUCCESS");
    /** 构建_失败_模式 */
    private static final Pattern BUILD_FAILURE_PATTERN = Pattern.compile("BUILD\\s+FAILURE");
    /** 错误_线_模式 */
    private static final Pattern ERROR_LINE_PATTERN = Pattern.compile("\\[ERROR\\]\\s*(.+)");
    /** Compiling_模式 */
    private static final Pattern COMPILING_PATTERN = Pattern.compile("Compiling\\s+(\\d+)\\s+source\\s+files");
    /** 测试_模式 */
    private static final Pattern TESTING_PATTERN = Pattern.compile("Tests run:\\s+(\\d+)");

    MavenClient(MavenClientBuilder builder) {
        this.projectPath = builder.getProjectPath();
        this.goals = builder.getEffectiveGoals();
        this.profiles = new ArrayList<>(builder.getProfiles());
        this.skipTests = builder.isSkipTests();
        this.quiet = builder.isQuiet();
        this.debug = builder.isDebug();
        this.offline = builder.isOffline();
        this.jdkVersion = builder.getJdkVersion();
        this.progressCallback = builder.getProgressCallback();
        this.compilerCallback = builder.getCompilerCallback();
        this.properties = new Properties();
        this.properties.putAll(builder.getProperties());
    }

    // ==================== 工厂方法 ====================

    /**
    * 创建 Maven客户端构建器 构建器
    *
    * @return Builder 构建器
    */
    public static MavenClientBuilder create() {
        return new MavenClientBuilder();
    }

    // ==================== 编译操作 ====================

    /**
    * 执行编译（同步阻塞）
    *
    * @return 编译结果
    */
    public MavenCompileResult execute() {
        return doExecute(new ArrayList<>(goals));
    }

    /**
    * 执行编译（异步）
    *
    * @return Future 编译结果的 期货
    */
    public Future<MavenCompileResult> executeAsync() {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor(
                Thread.ofVirtual().factory()
        );
        return executor.submit((java.util.concurrent.Callable<MavenCompileResult>) this::execute);
    }

    /**
    * 执行指定目标编译（同步）
    *
    * @param goal 目标
    * @return 编译结果
    */
    public MavenCompileResult execute(String goal) {
        return doExecute(List.of(goal));
    }

    /**
    * 执行指定目标列表编译（同步）
    *
    * @param goals 目标列表
    * @return 编译结果
    */
    public MavenCompileResult execute(String... goals) {
        return doExecute(List.of(goals));
    }

    @Override
    /** 关闭 */
    public void close() {
        // 无需清理特殊资源
    }

    // ==================== 包级调用接口 ====================

    /**
    * 从 构建器 执行编译
    *
    * @param builder 构建器
    * @return 编译结果
    */
    static MavenCompileResult execute(MavenClientBuilder builder) {
        MavenClient client = new MavenClient(builder);
        return client.doExecute(client.goals);
    }

    // ==================== 内部实现 ====================

    /**
    * 执行内部编译逻辑
    *
    * @param effectiveGoals 有效目标
    * @return 编译结果
    */
    private MavenCompileResult doExecute(List<String> effectiveGoals) {
        Instant start = Instant.now();

        notifyStart(projectPath);
        notifyProgress("开始编译 " + projectPath + " [goals=" + effectiveGoals + "]", 0);

 // 1. 解析 Maven Home
        String mavenHome = resolveMavenHome();
        if (mavenHome == null) {
            long duration = Duration.between(start, Instant.now()).toMillis();
            List<String> errors = List.of("未找到 Maven 安装路径，请设置 MAVEN_HOME 环境变量或 maven.home 系统属性");
            MavenCompileResult result = MavenCompileResult.failure(
                    projectPath, -1, "", errors, effectiveGoals, profiles, duration);
            notifyFailure(result);
            return result;
        }

        // 2. 准备输出收集
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(outputStream, true);
        List<String> errorLines = new ArrayList<>();
        int[] progressHolder = {0};

 // 3. 创建 invocation请求
        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(new File(projectPath));
        request.setGoals(effectiveGoals);

        if (!profiles.isEmpty()) {
            request.setProfiles(profiles);
        }

        Properties props = new Properties();
        if (skipTests) {
            props.setProperty("skipTests", "true");
            props.setProperty("maven.test.skip", "true");
        }
        if (debug) {
            props.setProperty("debug", "true");
        }
        if (jdkVersion != null && !jdkVersion.isBlank()) {
            props.setProperty("java.version", jdkVersion);
            props.setProperty("maven.compiler.source", jdkVersion);
            props.setProperty("maven.compiler.target", jdkVersion);
        }
        props.putAll(properties);
        request.setProperties(props);

        // 4. 设置输出处理器
        request.setOutputHandler(line -> {
            printStream.println(line);
            // 提取错误信息
            Matcher errorMatcher = ERROR_LINE_PATTERN.matcher(line);
            if (errorMatcher.find()) {
                errorLines.add(errorMatcher.group(1).trim());
            }
            // 更新进度
            progressHolder[0] = parseProgress(line, progressHolder[0]);
            notifyProgress(line, progressHolder[0]);
        });

        // 5. 执行编译
        try {
            DefaultInvoker invoker = new DefaultInvoker();
            invoker.setMavenHome(new File(mavenHome).getAbsoluteFile());

            InvocationResult invocationResult = invoker.execute(request);

            long duration = Duration.between(start, Instant.now()).toMillis();
            String output = outputStream.toString();
            int exitCode = invocationResult.getExitCode();

            boolean success = (exitCode == EXIT_SUCCESS);
            List<String> finalErrors = buildErrorList(
                    output, exitCode, invocationResult.getExecutionException());

            logResult(success, output, duration, exitCode);

            MavenCompileResult result;
            if (success) {
                result = MavenCompileResult.success(
                        projectPath, output, effectiveGoals, profiles, duration);
            } else {
                result = MavenCompileResult.failure(
                        projectPath, exitCode, output, finalErrors, effectiveGoals, profiles, duration);
            }

            notifyComplete(result);
            notifyProgress("编译结束 " + (success ? "SUCCESS" : "FAILURE"), PROGRESS_COMPLETE);
            if (success) {
                notifySuccess(result);
            } else {
                notifyFailure(result);
            }

            return result;
        } catch (MavenInvocationException e) {
            long duration = Duration.between(start, Instant.now()).toMillis();
            List<String> errors = new ArrayList<>(errorLines);
            errors.add("Maven 调用异常: " + e.getMessage());
            MavenCompileResult result = MavenCompileResult.failure(
                    projectPath, -1, outputStream.toString(), errors, effectiveGoals, profiles, duration);
            notifyFailure(result);
            throw new MavenClientException("Maven 编译执行异常: " + e.getMessage(), e);
        }
    }

    /**
    * 构建错误列表
    *
    * @param output             标准输出
    * @param exitCode           Maven 退出码
    * @param executionException 执行异常（可能为 空）
    * @return 错误列表
    */
    private List<String> buildErrorList(String output, int exitCode, Exception executionException) {
        List<String> errors = new ArrayList<>();
        if (exitCode != EXIT_SUCCESS) {
            if (executionException != null) {
                errors.add("Maven 退出码: " + exitCode + " - " + executionException.getMessage());
            } else {
                errors.add("Maven 退出码: " + exitCode);
            }
 // 提取 错误 行
            String[] lines = output.split("\n");
            for (String line : lines) {
                Matcher matcher = ERROR_LINE_PATTERN.matcher(line);
                if (matcher.find()) {
                    String errorMsg = matcher.group(1).trim();
                    if (!errors.contains(errorMsg)) {
                        errors.add(errorMsg);
                    }
                }
            }
        }
        return errors;
    }

    /**
    * 根据输出行解析进度百分比
    *
    * @param line             输出行
    * @param previousPercent 当前进度
    * @return 新的进度百分比
    */
    private int parseProgress(String line, int previousPercent) {
        if (line.contains("Scanning for projects")) {
            return PROGRESS_VALIDATION;
        }
        if (line.contains("Resolving dependencies") || line.contains("Downloading")) {
            return PROGRESS_RESOLVE;
        }
        Matcher compileMatcher = COMPILING_PATTERN.matcher(line);
        if (compileMatcher.find()) {
            return PROGRESS_COMPILE;
        }
        Matcher testMatcher = TESTING_PATTERN.matcher(line);
        if (testMatcher.find()) {
            return PROGRESS_TEST;
        }
        if (line.contains("Building jar") || line.contains("Building war")) {
            return PROGRESS_PACKAGE;
        }
        if (BUILD_SUCCESS_PATTERN.matcher(line).find()
                || BUILD_FAILURE_PATTERN.matcher(line).find()) {
            return PROGRESS_COMPLETE;
        }
        return previousPercent;
    }

    /**
    * 解析 Maven 安装路径
    *
    * @return Maven 安装目录路径，找不到返回 空
    */
    private String resolveMavenHome() {
        // 1. 检查系统属性
        String path = System.getProperty("maven.home");
        if (path != null && !path.isBlank() && new File(path).exists()) {
            return path;
        }

 // 2. 检查 Maven_Home 环境变量
        path = System.getenv("MAVEN_HOME");
        if (path != null && !path.isBlank() && new File(path).exists()) {
            return path;
        }

 // 3. 检查 M2_Home
        path = System.getenv("M2_HOME");
        if (path != null && !path.isBlank() && new File(path).exists()) {
            return path;
        }

 // 4. 尝试在 路径 中查找 mvn.CMD 或 mvn
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String[] pathDirs = pathEnv.split(File.pathSeparator);
            for (String dir : pathDirs) {
                File mvnCmd = new File(dir, "mvn.cmd");
                File mvnSh = new File(dir, "mvn");
                if (mvnCmd.exists() || mvnSh.exists()) {
                    File binDir = new File(dir);
                    File mavenDir = binDir.getParentFile();
                    if (mavenDir != null) {
                        File libDir = new File(mavenDir, "lib");
                        if (libDir.exists()) {
                            return mavenDir.getAbsolutePath();
                        }
                    }
                }
            }
        }

        log.warn("[maven] 未找到 Maven 安装路径，请设置 MAVEN_HOME 环境变量或 maven.home 系统属性");
        return null;
    }

    /**
    * 记录编译结果日志
    *
    * @param success  是否成功
    * @param output   输出
    * @param duration 耗时
    * @param exitCode 退出码
    */
    private void logResult(boolean success, String output, long duration, int exitCode) {
        if (success) {
            log.info("[maven] Maven 编译成功 [{}] 耗时: {}ms", projectPath, duration);
        } else {
            log.error("[maven] Maven 编译失败 [{}] exitCode: {} 耗时: {}ms", projectPath, exitCode, duration);
 // 输出 错误 行到日志
            String[] lines = output.split("\n");
            for (String line : lines) {
                if (line.contains("[ERROR]")) {
                    log.error("  {}", line.trim());
                }
            }
        }
    }

    // ==================== 进度与回调通知 ====================

    /**
    * 通知开始
    *
    * @param projectPath 项目路径
    */
    private void notifyStart(String projectPath) {
        if (compilerCallback != null) {
            try {
                compilerCallback.onStart(projectPath);
            } catch (Exception e) {
                log.warn("[maven] 开始回调异常: {}", e.getMessage());
            }
        }
    }

    /**
    * 通知进度
    *
    * @param message 消息
    * @param percent 百分比
    */
    private void notifyProgress(String message, int percent) {
        if (progressCallback != null) {
            try {
                progressCallback.onProgress(message, percent);
            } catch (Exception e) {
                log.warn("[maven] 进度回调异常: {}", e.getMessage());
            }
        }
    }

    /**
    * 通知完成
    *
    * @param result 编译结果
    */
    private void notifyComplete(MavenCompileResult result) {
        if (compilerCallback != null) {
            try {
                compilerCallback.onComplete(result);
            } catch (Exception e) {
                log.warn("[maven] 完成回调异常: {}", e.getMessage());
            }
        }
    }

    /**
    * 通知成功
    *
    * @param result 编译结果
    */
    private void notifySuccess(MavenCompileResult result) {
        if (compilerCallback != null) {
            try {
                compilerCallback.onSuccess(result);
            } catch (Exception e) {
                log.warn("[maven] 成功回调异常: {}", e.getMessage());
            }
        }
    }

    /**
    * 通知失败
    *
    * @param result 编译结果
    */
    private void notifyFailure(MavenCompileResult result) {
        if (compilerCallback != null) {
            try {
                compilerCallback.onFailure(result);
            } catch (Exception e) {
                log.warn("[maven] 失败回调异常: {}", e.getMessage());
            }
        }
    }

    // ==================== 异常类 ====================

    /**
    * Maven 客户端异常
    * @author CH
    * @since 4.0.0
    */
    public static class MavenClientException extends RuntimeException {
        /**
        * 构造异常
        *
        * @param message 消息
        * @param cause   原因
        */
        public MavenClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
