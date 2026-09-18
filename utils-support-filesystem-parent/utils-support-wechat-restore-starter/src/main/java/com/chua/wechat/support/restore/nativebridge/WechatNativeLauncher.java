package com.chua.wechat.support.restore.nativebridge;

import com.chua.common.support.reflection.ReflectUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 微信 WCDB 原生调用的 Electron 宿主自举启动器。
 *
 * <p>经真机实测确认：{@code wcdb_api.dll} 的 {@code wcdb_init()} 存在宿主进程名校验，
 * 进程镜像名不是 {@code electron.exe} 时固定返回 {@code -1006}（包括 java.exe、jshell.exe
 * 甚至改名 ffmpeg.exe 均被拒绝）。校验仅比对进程名，与进程内实际加载的模块无关，
 * 将 JDK 自带的 {@code java.exe} 复制改名为 {@code electron.exe} 后启动即可通过。</p>
 *
 * <h3>用法</h3>
 * <pre>
 * electron.exe -cp 应用类路径 com.chua.wechat.support.restore.nativebridge.WechatNativeLauncher 业务主类 [业务参数...]
 * </pre>
 * <p>若直接以普通 {@code java.exe} 启动本类，它会自动完成：</p>
 * <ol>
 *   <li>将当前 JDK 的 {@code java.exe} 复制为 {@code electron.exe}（默认目录
 *       {@code ${user.home}/.chua-wechat}，可用系统属性 {@code wechat.native.host.dir} 覆盖）；</li>
 *   <li>以 {@code electron.exe} 重新启动完全相同的 JVM 参数与类路径，并设置环境变量
 *       {@link #ENV_GUARD} 防止二次重启；</li>
 *   <li>子进程退出码透传，标准输入/输出/错误直接继承。</li>
 * </ol>
 *
 * <p>注意：本启动器面向类路径（classpath）应用，自动透传 {@code -cp} 与全部 JVM 输入参数；
 * 模块化（module-path）应用请直接以 {@code electron.exe} 启动，无需经过本启动器。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class WechatNativeLauncher {

    /**
    * 自举重启防护环境变量：存在时表示已运行在 electron.exe 宿主内，直接执行业务主类
    */
    public static final String ENV_GUARD = "WECHAT_NATIVE_RELAUNCHED";

    /**
    * 宿主启动器目录覆盖配置（系统属性）
    */
    public static final String PROP_HOST_DIR = "wechat.native.host.dir";

    /**
    * WCDB 宿主校验认可的进程名
    */
    private static final String ELECTRON_EXE = "electron.exe";

    /**
    * 默认宿主启动器存放目录名（位于用户主目录下）
    */
    private static final String DEFAULT_HOST_DIR = ".chua-wechat";

    private WechatNativeLauncher() {
    }

    /**
    * 启动器入口。
    *
    * @param args 第一个参数为业务主类全限定名，其余参数原样透传给该主类的 main 方法
    * @throws Exception 业务主类执行或自举重启过程中发生的异常
    */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("用法: WechatNativeLauncher <业务主类> [参数...]]");
        }

        if (isUnderElectronHost()) {
            invokeTarget(args[0], args);
            return;
        }

        if (!IS_WINDOWS) {
            log.warn("WCDB 原生库仅支持 Windows，当前系统 {} 下自举不生效，直接执行业务主类", OS_NAME);
            invokeTarget(args[0], args);
            return;
        }

        relaunchUnderElectron(args);
    }

    /**
    * 复制 JDK 启动器为 electron.exe 并重新拉起当前应用。
    *
    * @param args 原始启动参数
    * @throws Exception 复制或拉起子进程失败
    */
    private static void relaunchUnderElectron(String[] args) throws Exception {
        Path electronExe = materializeElectronExe();

        List<String> command = new ArrayList<>(32);
        command.add(electronExe.toAbsolutePath().toString());

        RuntimeMXBean runtimeBean = ManagementFactory.getPlatformMXBean(RuntimeMXBean.class);
        command.addAll(runtimeBean.getInputArguments());

        String classPath = System.getProperty("java.class.path");
        command.add("-cp");
        command.add(classPath);

        command.add(WechatNativeLauncher.class.getName());
        for (int i = 0; i < args.length; i++) {
            command.add(args[i]);
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.inheritIO();
        processBuilder.environment().put(ENV_GUARD, "1");
        log.info("WCDB 宿主自举：使用 {} 重新启动业务主类 {}", electronExe, args[0]);

        Process process = processBuilder.start();
        int exitCode = process.waitFor();
        System.exit(exitCode);
    }

    /**
    * 准备 electron.exe 宿主启动器，已存在且内容一致时复用。
    *
    * @return electron.exe 的路径
    * @throws Exception 复制失败
    */
    private static Path materializeElectronExe() throws Exception {
        Path source = resolveJavaLauncher();
        Path hostDir = resolveHostDir();
        Path target = hostDir.resolve(ELECTRON_EXE);

        boolean needCopy = !Files.exists(target)
                || Files.size(target) != Files.size(source);
        if (needCopy) {
            Files.createDirectories(hostDir);
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            log.info("已生成 WCDB 宿主启动器: {} <- {}", target, source);
        }
        return target;
    }

    /**
    * 定位当前 JVM 的 java.exe 启动器。
    *
    * @return 启动器路径
    */
    private static Path resolveJavaLauncher() {
        String currentCommand = ProcessHandle.current().info().command().orElse(null);
        if (currentCommand != null && !currentCommand.isBlank()) {
            return Paths.get(currentCommand);
        }
        return Paths.get(System.getProperty("java.home"), "bin", "java.exe");
    }

    /**
    * 解析 electron.exe 存放目录：系统属性指定目录优先，其次用户主目录下默认目录。
    *
    * @return 目录路径
    */
    private static Path resolveHostDir() {
        String configured = System.getProperty(PROP_HOST_DIR);
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        return Paths.get(System.getProperty("user.home"), DEFAULT_HOST_DIR);
    }

    /**
    * 判断当前是否已运行在 electron 宿主中（环境变量防护或进程名已是 electron.exe）。
    *
    * @return true 表示无需再次自举
    */
    private static boolean isUnderElectronHost() {
        if (System.getenv(ENV_GUARD) != null) {
            return true;
        }
        String command = ProcessHandle.current().info().command().orElse("");
        String name = new File(command).getName().toLowerCase(Locale.ROOT);
        return ELECTRON_EXE.equals(name);
    }

    /**
    * 反射调用业务主类的 main 方法。
    *
    * @param className 业务主类全限定名
    * @param allArgs   全部启动参数，第一个为主类名，其余透传
    * @throws Exception 主类加载或执行失败
    */
    private static void invokeTarget(String className, String[] allArgs) throws Exception {
        Class<?> targetClass = ReflectUtils.forName(className);
        if (targetClass == null) {
            throw new ClassNotFoundException(className);
        }
        String[] targetArgs = new String[allArgs.length - 1];
        System.arraycopy(allArgs, 1, targetArgs, 0, targetArgs.length);
        ReflectUtils.invokeStatic(targetClass, "main", void.class, new Class<?>[]{String[].class}, (Object) targetArgs);
    }

    /**
    * 当前操作系统名称（小写）
    */
    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    /**
    * 是否为 Windows 系统
    */
    private static final boolean IS_WINDOWS = OS_NAME.contains("win");
}
