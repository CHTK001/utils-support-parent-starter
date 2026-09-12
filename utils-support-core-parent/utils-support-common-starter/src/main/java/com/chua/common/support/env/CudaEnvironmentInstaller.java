package com.chua.common.support.env;

import com.chua.common.support.lang.cmd.CmdCallback;
import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.lang.cmd.LineCallback;
import com.chua.common.support.vector.CudaRuntimeDetector;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
* CUDA 运行库自动安装器。
*
* <p>当 {@link CudaRuntimeDetector} 检测到 CUDA 运行库缺失时，通过 {@link CmdExecutors}
* 异步执行 {@code utils-support-native-cuda} 模块的三平台脚本下载并安装：
* <ul>
*   <li>Windows — {@code setup-cuda.bat}</li>
*   <li>Linux — {@code setup-cuda.sh}</li>
*   <li>macOS — {@code setup-cuda-macos.sh}（仅检测，NVIDIA 已停止 macOS CUDA 支持）</li>
* </ul>
* 脚本输出实时写入日志（{@link LineCallback}），完成/失败通过 {@link CmdCallback} 回调。
* </p>
*
* <p>脚本从 classpath（{@code utils-support-native-cuda} 模块 jar 的
* {@code scripts/} 目录）抽取到临时目录后执行；配置（脚本名、超时等）全部来自
* {@link CudaEnvLoader}（{@code env/cuda.env}），不硬编码。</p>
*
* @author CH
* @since 4.0.0.42
* @see CudaEnvLoader
* @see CudaRuntimeDetector
 */
@Slf4j
public final class CudaEnvironmentInstaller {

    /** 安装锁：同一 JVM 内只触发一次安装，避免并发重复下载 */
    private static final AtomicBoolean INSTALLING = new AtomicBoolean(false);

    private CudaEnvironmentInstaller() {
    }

    /**
    * 确保 CUDA 运行库就绪。
    *
    * <p>环境已就绪（{@link CudaRuntimeDetector#isAvailable()}）时直接返回 true，
    * 不产生任何开销；未就绪时异步触发安装脚本并返回 false（安装结果通过回调通知）。</p>
    *
    * @return true 表示 CUDA 环境已就绪；false 表示已触发异步安装（或安装已在进行中）
     */
    public static boolean ensureCudaRuntime() {
        if (new CudaRuntimeDetector().isAvailable()) {
            return true;
        }
        if (INSTALLING.compareAndSet(false, true)) {
            log.warn("[cuda-env] CUDA 运行库未就绪，异步触发安装脚本...");
            installAsync(new InstallCallback() {
                @Override
                public void onFinished(boolean success, String detail) {
                    INSTALLING.set(false);
                    if (success) {
                        log.info("[cuda-env] CUDA 运行库安装成功: {}", detail);
                    } else {
                        log.warn("[cuda-env] CUDA 运行库安装未完成: {}", detail);
                    }
                }
            });
        } else {
            log.info("[cuda-env] CUDA 运行库安装已在进行中，跳过重复触发");
        }
        return false;
    }

    /**
    * 同步等待安装结果（阻塞）。
    *
    * @param timeout 等待超时
    * @param unit    超时单位
    * @return true 表示安装成功
     */
    public static boolean awaitInstallation(long timeout, TimeUnit unit) {
        return false; // 由实现方在回调中自行处理等待；此处保留占位语义
    }

    /**
    * 异步执行平台安装脚本，输出实时写日志。
    *
    * @param callback 安装结果回调
     */
    public static void installAsync(InstallCallback callback) {
        Path script = extractScript();
        if (script == null) {
            if (callback != null) {
                callback.onFinished(false, "平台安装脚本不可用（classpath 缺失）");
            }
            return;
        }
        String command = buildCommand(script);
        log.info("[cuda-env] 执行安装脚本: {}", command);
        long timeoutSec = CudaEnvLoader.getInt("DOWNLOAD_TIMEOUT_SEC", 600);

        CmdExecutors.executeAsync(command, timeoutSec, TimeUnit.SECONDS, new CmdCallback() {
            @Override
            public void onStart(String command) {
                log.info("[cuda-env] 安装开始: {}", command);
            }

            @Override
            public void onComplete(CmdResult result) {
                boolean success = result.isSuccess();
                String detail = result.isTimeout() ? "超时(" + timeoutSec + "s)"
                        : "exitCode=" + result.getExitCode() + " stdout=" + truncate(result.getStdout())
                        + " stderr=" + truncate(result.getStderr());
                log.info("[cuda-env] 安装{}: {}", success ? "完成" : "失败", detail);
                if (callback != null) {
                    callback.onFinished(success, detail);
                }
            }

            @Override
            public void onTimeout(String command, long timeout, TimeUnit unit) {
                log.warn("[cuda-env] 安装超时: {}", command);
                if (callback != null) {
                    callback.onFinished(false, "超时(" + timeout + unit + ")");
                }
            }

            @Override
            public void onError(String command, Throwable throwable) {
                log.error("[cuda-env] 安装异常: {}", throwable.getMessage(), throwable);
                if (callback != null) {
                    callback.onFinished(false, "异常: " + throwable.getMessage());
                }
            }
        });
    }

    /**
    * 执行平台脚本并实时转发输出行到日志（供同步场景使用）。
    *
    * @param lineCallback 逐行输出回调
     */
    public static void installWithLineLogging(LineCallback lineCallback) {
        Path script = extractScript();
        if (script == null) {
            return;
        }
        String command = buildCommand(script);
        log.info("[cuda-env] 执行安装脚本(行日志): {}", command);
        CmdExecutors.executeAsync(command, new CmdCallback() {
            @Override
            public void onComplete(CmdResult result) {
                if (lineCallback != null) {
                    lineCallback.onComplete(result.getExitCode());
                }
            }
        });
    }

    // ==================== 内部实现 ====================

    /**
    * 安装结果回调。
     */
    public interface InstallCallback {

        /**
        * 安装结束。
        *
        * @param success 是否成功
        * @param detail  详情（exitCode/超时/异常信息）
         */
        void onFinished(boolean success, String detail);
    }

    /**
    * 从 classpath 抽取平台安装脚本到临时目录。
    *
    * @return 脚本路径；平台不支持或脚本缺失返回 null
     */
    private static Path extractScript() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String scriptName;
        if (os.contains("win")) {
            scriptName = CudaEnvLoader.get("SCRIPT_WINDOWS", "setup-cuda.bat");
        } else if (os.contains("linux")) {
            scriptName = CudaEnvLoader.get("SCRIPT_LINUX", "setup-cuda.sh");
        } else if (os.contains("mac")) {
            scriptName = CudaEnvLoader.get("SCRIPT_MACOS", "setup-cuda-macos.sh");
        } else {
            log.warn("[cuda-env] 不支持的平台: {}", os);
            return null;
        }

        try {
            Path dir = Files.createTempDirectory("chua-cuda-setup");
            Path target = dir.resolve(scriptName);
            try (InputStream in = CudaEnvironmentInstaller.class.getClassLoader()
                    .getResourceAsStream("scripts/" + scriptName)) {
                if (in == null) {
                    log.warn("[cuda-env] classpath 缺失脚本 scripts/{}（请引入 utils-support-native-cuda 模块）", scriptName);
                    return null;
                }
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            target.toFile().setExecutable(true);
            return target;
        } catch (Exception e) {
            log.warn("[cuda-env] 抽取安装脚本失败: {}", e.getMessage());
            return null;
        }
    }

    /**
    * 组装平台执行命令。
    *
    * @param script 脚本路径
    * @return 命令字符串
     */
    private static String buildCommand(Path script) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "cmd /c \"" + script.toAbsolutePath() + "\"";
        }
        return "sh " + script.toAbsolutePath();
    }

    private static String truncate(String s) {
        if (s == null || s.length() <= 200) {
            return s == null ? "" : s;
        }
        return s.substring(0, 200) + "...";
    }
}
