package com.chua.wechat.support.restore.nativebridge;

import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 微信数据库密钥捕获 FFM 桥接器。
 *
 * <p>基于 Java 25 FFM 直接绑定 {@code wx_key.dll}，替代 Wechat-Export 方案中的
 * {@code scripts/get_key.js}（Node.js + koffi）。wx_key.dll 内部完成对微信进程的
 * Hook 注入（拦截 SetDBKey 调用），本类只负责加载 DLL、编排注入时序与轮询密钥。</p>
 *
 * <h3>捕获时序（与 get_key.js 完全一致）</h3>
 * <ol>
 *   <li>等待微信进程退出（SetDBKey 仅在进程启动登录阶段调用，必须在微信启动前注入）</li>
 *   <li>等待微信进程重新启动并取得 PID</li>
 *   <li>调用 {@code InitializeHook(pid)} 注入 Hook</li>
 *   <li>以 200ms 间隔轮询 {@code PollKeyData}，最长等待 120 秒（用户需完成登录）</li>
 *   <li>校验 64 位十六进制密钥，落盘 key.txt，并读取图片密钥 image_key.json</li>
 * </ol>
 *
 * <p><b>权限要求：</b>Hook 注入需要管理员权限；执行 JVM 时应以管理员身份运行。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class WxKeyNativeBridge implements AutoCloseable {

    /**
    * 密钥捕获动态库
    */
    private static final String DLL_WX_KEY = "wx_key.dll";

    /**
    * 密钥缓冲区大小（字节）
    */
    private static final int KEY_BUFFER_SIZE = 128;

    /**
    * 状态消息缓冲区大小（字节）
    */
    private static final int STATUS_BUFFER_SIZE = 512;

    /**
    * 图片密钥缓冲区大小（字节）
    */
    private static final int IMAGE_KEY_BUFFER_SIZE = 8192;

    /**
    * 合法密钥长度（64 位十六进制字符）
    */
    private static final int KEY_LENGTH = 64;

    /**
    * 等待微信退出的最大次数（40 × 500ms = 20 秒）
    */
    private static final int WAIT_EXIT_ROUNDS = 40;

    /**
    * 等待微信启动的最大次数（120 × 500ms = 60 秒）
    */
    private static final int WAIT_START_ROUNDS = 120;

    /**
    * 轮询密钥的最大次数（600 × 200ms = 120 秒）
    */
    private static final int POLL_KEY_ROUNDS = 600;

    /**
    * 轮询间隔（毫秒）
    */
    private static final long POLL_INTERVAL_MILLIS = 200L;

    /**
    * 进程探测命令超时（秒）
    */
    private static final int PID_CMD_TIMEOUT_SECONDS = 5;

    /**
    * 64 位十六进制密钥校验正则
    */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");

    /**
    * 微信 4.x 进程名
    */
    private static final String[] WECHAT_PROCESS_NAMES = {"Weixin.exe", "WeChat.exe"};

    /**
    * 原生下行调用链接器
    */
    private static final Linker LINKER = Linker.nativeLinker();

    /**
    * 桥接器持有的共享内存会话
    */
    private final Arena arena;

    /**
    * InitializeHook 函数句柄
    */
    private final MethodHandle initializeHookHandle;

    /**
    * PollKeyData 函数句柄
    */
    private final MethodHandle pollKeyDataHandle;

    /**
    * GetStatusMessage 函数句柄
    */
    private final MethodHandle getStatusMessageHandle;

    /**
    * CleanupHook 函数句柄
    */
    private final MethodHandle cleanupHookHandle;

    /**
    * GetLastErrorMsg 函数句柄
    */
    private final MethodHandle getLastErrorMsgHandle;

    /**
    * GetImageKey 函数句柄
    */
    private final MethodHandle getImageKeyHandle;

    /**
    * 判断当前平台是否支持密钥捕获（仅 Windows）。
    *
    * @return 支持返回 true
    */
    public static boolean isSupported() {
        String osName = System.getProperty("os.name").toLowerCase();
        return osName.contains("win");
    }

    /**
    * 加载 wx_key.dll 并执行完整密钥捕获流程。
    *
    * <p>该方法会引导用户重启微信：若检测到微信正在运行，将等待其退出（最多 20 秒），
    * 随后等待微信重新启动（最多 60 秒），注入 Hook 后在用户登录过程中捕获密钥。</p>
    *
    * @param runtimeDir 原生库目录（包含 wx_key.dll）
    * @param outputDir  密钥与状态文件输出目录
    * @return 64 位十六进制密钥
    * @throws IllegalStateException 库缺失、注入失败或捕获超时时抛出
    */
    public static String captureKey(File runtimeDir, File outputDir) {
        if (!isSupported()) {
            throw new IllegalStateException("密钥捕获仅支持 Windows 平台");
        }
        File dllFile = new File(runtimeDir, DLL_WX_KEY);
        if (!dllFile.isFile()) {
            throw new IllegalStateException("缺少密钥捕获动态库: " + dllFile.getAbsolutePath());
        }
        if (outputDir != null && !outputDir.exists() && !outputDir.mkdirs()) {
            throw new IllegalStateException("创建输出目录失败: " + outputDir.getAbsolutePath());
        }

        try (WxKeyNativeBridge bridge = new WxKeyNativeBridge(dllFile)) {
            return bridge.doCapture(outputDir);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("微信密钥捕获异常: " + t.getMessage(), t);
        }
    }

    /**
    * 私有构造器，加载动态库并绑定函数句柄。
    *
    * @param dllFile wx_key.dll 文件
    */
    private WxKeyNativeBridge(File dllFile) {
        this.arena = Arena.ofShared();
        SymbolLookup lookup = SymbolLookup.libraryLookup(dllFile.toPath(), arena);
        this.initializeHookHandle = bind(lookup, "InitializeHook",
                ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_INT);
        this.pollKeyDataHandle = bind(lookup, "PollKeyData",
                ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
        this.getStatusMessageHandle = bind(lookup, "GetStatusMessage",
                ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
        this.cleanupHookHandle = bind(lookup, "CleanupHook", ValueLayout.JAVA_BOOLEAN);
        this.getLastErrorMsgHandle = bind(lookup, "GetLastErrorMsg", ValueLayout.ADDRESS);
        this.getImageKeyHandle = bind(lookup, "GetImageKey",
                ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
    }

    /**
    * 执行完整捕获流程。
    *
    * @param outputDir 密钥输出目录
    * @return 64 位十六进制密钥
    * @throws Throwable 原生调用异常
    */
    private String doCapture(File outputDir) throws Throwable {
        // 阶段 1：等待微信退出
        Integer existingPid = findWeChatPid();
        if (existingPid != null) {
            log.info("[微信密钥] 检测到微信正在运行，请手动关闭微信（最多等待 20 秒）...");
            if (!waitWeChatExit()) {
                cleanupQuietly();
                throw new IllegalStateException("等待微信关闭超时，请手动关闭微信后重试");
            }
            log.info("[微信密钥] 微信已关闭");
        }

        // 阶段 2：等待微信启动
        log.info("[微信密钥] 请现在打开微信并登录（最多等待 60 秒）...");
        Integer pid = waitWeChatStart();
        if (pid == null) {
            cleanupQuietly();
            throw new IllegalStateException("等待微信启动超时，请启动微信后重试");
        }
        log.info("[微信密钥] 检测到微信进程 PID: {}", pid);

        // 阶段 3：立即注入 Hook（须赶在 SetDBKey 调用之前）
        boolean injected = (boolean) initializeHookHandle.invokeExact(pid.intValue());
        if (!injected) {
            String error = readLastError();
            cleanupQuietly();
            throw new IllegalStateException("Hook 注入失败" + (error == null ? "" : ": " + error)
                    + "（请以管理员身份运行）");
        }
        log.info("[微信密钥] Hook 注入成功，等待登录过程中捕获密钥...");

        // 阶段 4：轮询密钥
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment keyBuffer = confined.allocate(KEY_BUFFER_SIZE);
            MemorySegment statusBuffer = confined.allocate(STATUS_BUFFER_SIZE);
            // 注意：Arena.allocate(layout, count) 的第二个参数是「元素个数」而非初始值。
            // 写成 allocate(ValueLayout.JAVA_INT, 0) 会分配 0 个 int（0 字节），
            // 随后 levelOut.get(...) 直接抛 IndexOutOfBoundsException（byteSize: 0）。
            MemorySegment levelOut = confined.allocate(ValueLayout.JAVA_INT);

            for (int round = 0; round < POLL_KEY_ROUNDS; round++) {
                drainStatusMessages(statusBuffer, levelOut);

                boolean hasKey = (boolean) pollKeyDataHandle.invokeExact(keyBuffer, KEY_BUFFER_SIZE);
                if (hasKey) {
                    String rawKey = keyBuffer.reinterpret(KEY_BUFFER_SIZE)
                            .getString(0L, StandardCharsets.US_ASCII);
                    String key = rawKey.length() > KEY_LENGTH ? rawKey.substring(0, KEY_LENGTH) : rawKey;
                    if (KEY_PATTERN.matcher(key).matches()) {
                        persistKey(outputDir, key, confined);
                        cleanupQuietly();
                        log.info("[微信密钥] 密钥捕获成功: {}...", key.substring(0, 16));
                        return key;
                    }
                }
                sleepQuietly(POLL_INTERVAL_MILLIS);
            }
        }

        cleanupQuietly();
        throw new IllegalStateException("等待微信密钥超时（120 秒），请确认已完成登录");
    }

    /**
    * 持久化密钥文件与图片密钥文件。
    *
    * @param outputDir 输出目录
    * @param key       数据库密钥
    * @param confined  受限内存会话
    * @throws Throwable 原生调用异常
    */
    private void persistKey(File outputDir, String key, Arena confined) throws Throwable {
        if (outputDir == null) {
            return;
        }
        File keyFile = new File(outputDir, "key.txt");
        Files.writeString(keyFile.toPath(), key, StandardCharsets.UTF_8);

        // 图片密钥为可选能力，失败不影响主流程
        try {
            MemorySegment imageBuffer = confined.allocate(IMAGE_KEY_BUFFER_SIZE);
            boolean hasImageKey = (boolean) getImageKeyHandle.invokeExact(imageBuffer, IMAGE_KEY_BUFFER_SIZE);
            if (hasImageKey) {
                String imageKeyJson = imageBuffer.reinterpret(IMAGE_KEY_BUFFER_SIZE)
                        .getString(0L, StandardCharsets.UTF_8);
                String trimmed = trimCString(imageKeyJson);
                if (!trimmed.isBlank() && trimmed.startsWith("{")) {
                    File imageKeyFile = new File(outputDir, "image_key.json");
                    Files.writeString(imageKeyFile.toPath(), trimmed, StandardCharsets.UTF_8);
                    log.info("[微信密钥] 图片密钥已保存: {}", imageKeyFile.getName());
                }
            }
        } catch (Throwable t) {
            log.warn("[微信密钥] 读取图片密钥失败（不影响数据库导出）: {}", t.getMessage());
        }
    }

    /**
    * 排空原生层缓冲的状态消息并输出到日志。
    *
    * @param statusBuffer 状态消息缓冲区
    * @param levelOut     消息级别出参
    * @throws Throwable 原生调用异常
    */
    private void drainStatusMessages(MemorySegment statusBuffer, MemorySegment levelOut) throws Throwable {
        // 与 get_key.js 一致：循环读取直到原生层返回 false
        for (int i = 0; i < 16; i++) {
            boolean hasMessage = (boolean) getStatusMessageHandle.invokeExact(
                    statusBuffer, STATUS_BUFFER_SIZE, levelOut);
            if (!hasMessage) {
                break;
            }
            int level = levelOut.get(ValueLayout.JAVA_INT, 0L);
            String message = trimCString(statusBuffer.reinterpret(STATUS_BUFFER_SIZE)
                    .getString(0L, StandardCharsets.UTF_8));
            if (!message.isBlank()) {
                log.info("[微信密钥原生层:{}] {}", level, message);
            }
        }
    }

    /**
    * 读取原生层最后一条错误信息。
    *
    * @return 错误文本，读取失败返回 null
    */
    private String readLastError() {
        try {
            MemorySegment pointer = (MemorySegment) getLastErrorMsgHandle.invokeExact();
            if (pointer.address() == 0L) {
                return null;
            }
            return trimCString(pointer.reinterpret(Long.MAX_VALUE).getString(0L, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
    * 静默执行 Hook 清理。
    */
    private void cleanupQuietly() {
        try {
            cleanupHookHandle.invokeExact();
        } catch (Throwable t) {
            log.warn("[微信密钥] CleanupHook 调用异常: {}", t.getMessage());
        }
    }

    /**
    * 等待微信进程退出。
    *
    * @return 已退出返回 true，超时返回 false
    */
    private boolean waitWeChatExit() {
        for (int i = 0; i < WAIT_EXIT_ROUNDS; i++) {
            if (findWeChatPid() == null) {
                return true;
            }
            sleepQuietly(POLL_INTERVAL_MILLIS * 2);
        }
        return false;
    }

    /**
    * 等待微信进程启动。
    *
    * @return 微信进程 PID，超时返回 null
    */
    private Integer waitWeChatStart() {
        for (int i = 0; i < WAIT_START_ROUNDS; i++) {
            Integer pid = findWeChatPid();
            if (pid != null) {
                return pid;
            }
            sleepQuietly(POLL_INTERVAL_MILLIS * 2);
        }
        return null;
    }

    /**
    * 通过 tasklist 查找微信进程 PID。
    *
    * @return PID，未找到返回 null
    */
    private static Integer findWeChatPid() {
        for (String processName : WECHAT_PROCESS_NAMES) {
            try {
                String[] command = {"tasklist", "/FI", "IMAGENAME eq " + processName, "/FO", "CSV", "/NH"};
                CmdResult result = CmdExecutors.execute(command, PID_CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!result.isSuccess() || result.getStdout() == null) {
                    continue;
                }
                Integer pid = parseTasklistPid(result.getStdout(), processName);
                if (pid != null) {
                    return pid;
                }
            } catch (Exception e) {
                log.debug("探测微信进程失败: {}", processName);
            }
        }
        return null;
    }

    /**
    * 解析 tasklist CSV 输出中的 PID。
    *
    * @param output      tasklist 输出文本
    * @param processName 进程名（匹配用）
    * @return PID，未匹配返回 null
    */
    private static Integer parseTasklistPid(String output, String processName) {
        for (String line : output.split("\n")) {
            String lowerLine = line.toLowerCase();
            if (!lowerLine.contains(processName.replace(".exe", "").toLowerCase())) {
                continue;
            }
            String[] fields = line.split("\",\"");
            if (fields.length >= 2) {
                try {
                    String pidText = fields[1].replace("\"", "").trim();
                    return Integer.parseInt(pidText);
                } catch (NumberFormatException e) {
                    // 该行不是有效进程记录，继续尝试
                }
            }
        }
        return null;
    }

    /**
    * 线程休眠（中断时恢复中断标志）。
    *
    * @param millis 休眠毫秒数
    */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
    * 截取 C 字符串（首个 NUL 之前的内容）并去除首尾空白。
    *
    * @param raw 原始文本
    * @return 截取后的文本
    */
    private static String trimCString(String raw) {
        int nulIndex = raw.indexOf('\0');
        String text = nulIndex >= 0 ? raw.substring(0, nulIndex) : raw;
        return text.trim();
    }

    @Override
    public void close() {
        arena.close();
    }

    /**
    * 绑定原生函数句柄。
    *
    * @param lookup       符号查找表
    * @param name         函数符号名
    * @param returnLayout 返回值布局
    * @param argLayouts   参数布局
    * @return 下行调用句柄
    */
    private static MethodHandle bind(SymbolLookup lookup, String name,
                                     ValueLayout returnLayout, ValueLayout... argLayouts) {
        MemorySegment symbol = lookup.find(name)
                .orElseThrow(() -> new IllegalStateException(DLL_WX_KEY + " 缺少导出符号: " + name));
        return LINKER.downcallHandle(symbol, FunctionDescriptor.of(returnLayout, argLayouts));
    }
}
