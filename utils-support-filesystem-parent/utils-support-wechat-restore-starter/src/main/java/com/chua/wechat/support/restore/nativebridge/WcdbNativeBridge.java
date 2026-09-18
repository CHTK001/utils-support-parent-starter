package com.chua.wechat.support.restore.nativebridge;

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

/**
 * 微信 WCDB 原生库 FFM 桥接器。
 *
 * <p>基于 Java 25 正式版 FFM（Foreign Function &amp; Memory）API 直接绑定微信自带的
 * {@code wcdb_api.dll}，替代 Wechat-Export 方案中的 Node.js + koffi + Electron 桥接层。
 * 绑定的 C 接口与 Wechat-Export 的 {@code scripts/wcdb_server.js} 完全对齐：</p>
 * <ul>
 *   <li>{@code int InitProtection(const char* path)} — 初始化环境保护，参数为 DLL 所在目录</li>
 *   <li>{@code int wcdb_init()} — 初始化 WCDB 引擎，返回 0 表示成功</li>
 *   <li>{@code int wcdb_open_account(const char* path, const char* key, int64* h)} — 打开账号库</li>
 *   <li>{@code int wcdb_close_account(int64 h)} — 关闭账号库句柄</li>
 *   <li>{@code int wcdb_get_sessions(int64 h, void** out)} — 获取会话列表 JSON</li>
 *   <li>{@code int wcdb_get_messages(int64 h, const char* username, int limit, int offset, void** out)} — 分页获取消息 JSON</li>
 *   <li>{@code int wcdb_get_message_count(int64 h, const char* username, int* out)} — 获取消息总数</li>
 *   <li>{@code int wcdb_get_display_names(int64 h, const char* json, void** out)} — 批量解析发送者显示名</li>
 *   <li>{@code void wcdb_free_string(void* p)} — 释放原生层分配的字符串</li>
 * </ul>
 *
 * <h3>环境约束</h3>
 * <p>WCDB 为微信闭源组件，其内部存在宿主进程名校验：经真机实测，进程名不是
 * {@code electron.exe} 时 {@code wcdb_init()} 固定返回错误码 {@code -1006}。
 * 使用 {@link WechatNativeLauncher} 引导启动（自动以 electron.exe 重启 JVM）即可通过，
 * 无需 Electron 运行时；初始化失败时上层也可回退到 Python 工具编排路径。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class WcdbNativeBridge implements AutoCloseable {

    /**
    * 原生函数返回成功
    */
    public static final int RC_OK = 0;

    /**
    * wcdb_init 在非 Electron 宿主下的典型拒绝码
    */
    public static final int RC_INIT_FAIL = -1006;

    /**
    * WCDB 核心引擎动态库
    */
    private static final String DLL_WCDB = "WCDB.dll";

    /**
    * WCDB 依赖的 SDL2 运行库
    */
    private static final String DLL_SDL2 = "SDL2.dll";

    /**
    * WCDB 的 C 接口封装库
    */
    private static final String DLL_WCDB_API = "wcdb_api.dll";

    /**
    * 原生下行调用链接器
    */
    private static final Linker LINKER = Linker.nativeLinker();

    /**
    * 桥接器持有的共享内存会话（动态库生命周期）
    */
    private final Arena arena;

    /**
    * InitProtection 函数句柄
    */
    private final MethodHandle initProtectionHandle;

    /**
    * wcdb_init 函数句柄
    */
    private final MethodHandle wcdbInitHandle;

    /**
    * wcdb_open_account 函数句柄
    */
    private final MethodHandle openAccountHandle;

    /**
    * wcdb_close_account 函数句柄
    */
    private final MethodHandle closeAccountHandle;

    /**
    * wcdb_get_sessions 函数句柄
    */
    private final MethodHandle getSessionsHandle;

    /**
    * wcdb_get_messages 函数句柄
    */
    private final MethodHandle getMessagesHandle;

    /**
    * wcdb_get_message_count 函数句柄
    */
    private final MethodHandle getMessageCountHandle;

    /**
    * wcdb_get_display_names 函数句柄
    */
    private final MethodHandle getDisplayNamesHandle;

    /**
    * wcdb_free_string 函数句柄
    */
    private final MethodHandle freeStringHandle;

    /**
    * 判断当前平台是否支持 FFM 原生路径（仅 Windows + 微信 4.x 动态库）。
    *
    * @return 支持返回 true
    */
    public static boolean isSupported() {
        String osName = System.getProperty("os.name").toLowerCase();
        return osName.contains("win");
    }

    /**
    * 加载原生库并完成 WCDB 引擎初始化。
    *
    * <p>必须严格按 {@code WCDB.dll → SDL2.dll → wcdb_api.dll} 顺序预加载，
    * 保证 wcdb_api 的依赖库已驻留进程，加载完成后立即调用 {@code InitProtection}
    * 与 {@code wcdb_init} 完成环境初始化。</p>
    *
    * @param runtimeDir 原生库目录（包含三个 DLL）
    * @return 初始化完成的桥接器实例
    * @throws IllegalStateException 库文件缺失或引擎初始化失败时抛出
    */
    public static WcdbNativeBridge load(File runtimeDir) {
        if (!isSupported()) {
            throw new IllegalStateException("FFM 原生路径仅支持 Windows 平台");
        }
        if (runtimeDir == null || !runtimeDir.isDirectory()) {
            throw new IllegalStateException("微信原生库目录不存在: " + runtimeDir);
        }
        File wcdbFile = new File(runtimeDir, DLL_WCDB);
        File sdl2File = new File(runtimeDir, DLL_SDL2);
        File apiFile = new File(runtimeDir, DLL_WCDB_API);
        if (!wcdbFile.isFile() || !sdl2File.isFile() || !apiFile.isFile()) {
            throw new IllegalStateException("微信原生库不完整，目录中需包含 "
                    + DLL_WCDB + "、" + DLL_SDL2 + "、" + DLL_WCDB_API + ": " + runtimeDir.getAbsolutePath());
        }

        Arena sharedArena = Arena.ofShared();
        try {
            // 预加载依赖库（保留 SymbolLookup 引用，防止被回收导致库卸载）
            SymbolLookup.libraryLookup(wcdbFile.toPath(), sharedArena);
            SymbolLookup.libraryLookup(sdl2File.toPath(), sharedArena);
            SymbolLookup apiLookup = SymbolLookup.libraryLookup(apiFile.toPath(), sharedArena);

            WcdbNativeBridge bridge = new WcdbNativeBridge(sharedArena, apiLookup);
            bridge.initialize(runtimeDir);
            log.info("微信 WCDB 原生库加载并初始化成功: {}", runtimeDir.getAbsolutePath());
            return bridge;
        } catch (Throwable t) {
            sharedArena.close();
            throw new IllegalStateException("微信 WCDB 原生库初始化失败: " + t.getMessage(), t);
        }
    }

    /**
    * 私有构造器，绑定全部原生函数句柄。
    *
    * @param arena     共享内存会话
    * @param apiLookup wcdb_api.dll 符号查找表
    */
    private WcdbNativeBridge(Arena arena, SymbolLookup apiLookup) {
        this.arena = arena;
        this.initProtectionHandle = bind(apiLookup, "InitProtection",
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
        this.wcdbInitHandle = bind(apiLookup, "wcdb_init", ValueLayout.JAVA_INT);
        this.openAccountHandle = bind(apiLookup, "wcdb_open_account",
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        this.closeAccountHandle = bind(apiLookup, "wcdb_close_account",
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG);
        this.getSessionsHandle = bind(apiLookup, "wcdb_get_sessions",
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS);
        this.getMessagesHandle = bind(apiLookup, "wcdb_get_messages",
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
        this.getMessageCountHandle = bind(apiLookup, "wcdb_get_message_count",
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        this.getDisplayNamesHandle = bind(apiLookup, "wcdb_get_display_names",
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        this.freeStringHandle = bindVoid(apiLookup, "wcdb_free_string", ValueLayout.ADDRESS);
    }

    /**
    * 执行环境保护初始化与引擎初始化。
    *
    * @param runtimeDir 原生库目录
    */
    private void initialize(File runtimeDir) {
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment pathSegment = confined.allocateFrom(runtimeDir.getAbsolutePath(), StandardCharsets.UTF_8);
            int protectionRc = (int) initProtectionHandle.invokeExact(pathSegment);
            if (protectionRc != RC_OK) {
                throw new IllegalStateException("InitProtection 失败, code=" + protectionRc);
            }
            int initRc = (int) wcdbInitHandle.invokeExact();
            if (initRc != RC_OK) {
                String hint = initRc == RC_INIT_FAIL
                        ? "（错误码 -1006：WCDB 宿主环境校验拒绝初始化。该校验仅比对进程名，"
                                + "请以 com.chua.wechat.support.restore.nativebridge.WechatNativeLauncher 引导启动，"
                                + "或直接使用名为 electron.exe 的 JVM 启动器；也可回退 mode=tool 的 Python 工具路径）"
                        : "";
                throw new IllegalStateException("wcdb_init 失败, code=" + initRc + hint);
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("WCDB 初始化调用异常: " + t.getMessage(), t);
        }
    }

    /**
    * 打开微信账号会话库。
    *
    * @param dbPath session.db 绝对路径
    * @param key    64 位十六进制数据库密钥
    * @return 原生账号库句柄
    * @throws IllegalStateException 打开失败时抛出
    */
    public long openAccount(String dbPath, String key) {
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment dbSegment = confined.allocateFrom(dbPath, StandardCharsets.UTF_8);
            MemorySegment keySegment = confined.allocateFrom(key, StandardCharsets.UTF_8);
            MemorySegment handleOut = confined.allocate(ValueLayout.JAVA_LONG, 0L);
            int rc = (int) openAccountHandle.invokeExact(dbSegment, keySegment, handleOut);
            if (rc != RC_OK) {
                throw new IllegalStateException("wcdb_open_account 失败, code=" + rc
                        + "（请检查密钥是否正确、微信数据目录是否有效）");
            }
            return handleOut.get(ValueLayout.JAVA_LONG, 0L);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("wcdb_open_account 调用异常: " + t.getMessage(), t);
        }
    }

    /**
    * 关闭账号库句柄。
    *
    * @param handle 账号库句柄
    */
    public void closeAccount(long handle) {
        try {
            int rc = (int) closeAccountHandle.invokeExact(handle);
            if (rc != RC_OK) {
                log.warn("wcdb_close_account 返回非零码: {}", rc);
            }
        } catch (Throwable t) {
            log.warn("wcdb_close_account 调用异常: {}", t.getMessage());
        }
    }

    /**
    * 获取全部会话列表 JSON。
    *
    * @param handle 账号库句柄
    * @return 会话列表 JSON 字符串（数组结构）
    */
    public String getSessions(long handle) {
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment outPointer = confined.allocate(ValueLayout.ADDRESS);
            int rc = (int) getSessionsHandle.invokeExact(handle, outPointer);
            return readOutString(rc, outPointer, "wcdb_get_sessions");
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("wcdb_get_sessions 调用异常: " + t.getMessage(), t);
        }
    }

    /**
    * 分页获取指定会话的消息 JSON。
    *
    * @param handle   账号库句柄
    * @param username 会话标识（wxid / 群 id）
    * @param limit    单页条数（Wechat-Export 惯例 500）
    * @param offset   偏移量
    * @return 消息列表 JSON 字符串
    */
    public String getMessages(long handle, String username, int limit, int offset) {
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment usernameSegment = confined.allocateFrom(username, StandardCharsets.UTF_8);
            MemorySegment outPointer = confined.allocate(ValueLayout.ADDRESS);
            int rc = (int) getMessagesHandle.invokeExact(handle, usernameSegment, limit, offset, outPointer);
            return readOutString(rc, outPointer, "wcdb_get_messages");
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("wcdb_get_messages 调用异常: " + t.getMessage(), t);
        }
    }

    /**
    * 获取指定会话的消息总数。
    *
    * @param handle   账号库句柄
    * @param username 会话标识
    * @return 消息总数
    */
    public int getMessageCount(long handle, String username) {
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment usernameSegment = confined.allocateFrom(username, StandardCharsets.UTF_8);
            MemorySegment countOut = confined.allocate(ValueLayout.JAVA_INT, 0);
            int rc = (int) getMessageCountHandle.invokeExact(handle, usernameSegment, countOut);
            if (rc != RC_OK) {
                throw new IllegalStateException("wcdb_get_message_count 失败, code=" + rc);
            }
            return countOut.get(ValueLayout.JAVA_INT, 0L);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("wcdb_get_message_count 调用异常: " + t.getMessage(), t);
        }
    }

    /**
    * 批量解析发送者的显示名称。
    *
    * @param handle    账号库句柄
    * @param wxidsJson 发送者标识 JSON 数组，如 {@code ["wxid_xxx"]}
    * @return 标识到显示名的映射 JSON 字符串
    */
    public String getDisplayNames(long handle, String wxidsJson) {
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment jsonSegment = confined.allocateFrom(wxidsJson, StandardCharsets.UTF_8);
            MemorySegment outPointer = confined.allocate(ValueLayout.ADDRESS);
            int rc = (int) getDisplayNamesHandle.invokeExact(handle, jsonSegment, outPointer);
            return readOutString(rc, outPointer, "wcdb_get_display_names");
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("wcdb_get_display_names 调用异常: " + t.getMessage(), t);
        }
    }

    @Override
    public void close() {
        arena.close();
    }

    /**
    * 读取原生出参指针指向的字符串并释放原生内存。
    *
    * @param rc         原生函数返回码
    * @param outSlot    出参指针槽
    * @param functionName 函数名（异常信息用）
    * @return UTF-8 字符串；原生返回空指针时返回 null
    */
    private String readOutString(int rc, MemorySegment outSlot, String functionName) {
        if (rc != RC_OK) {
            throw new IllegalStateException(functionName + " 失败, code=" + rc);
        }
        MemorySegment pointer = outSlot.get(ValueLayout.ADDRESS, 0L);
        if (pointer.address() == 0L) {
            return null;
        }
        // 原生字符串以 '\0' 结尾，放开段边界后按 UTF-8 读取
        String result = pointer.reinterpret(Long.MAX_VALUE).getString(0L, StandardCharsets.UTF_8);
        freeString(pointer);
        return result;
    }

    /**
    * 释放原生层分配的字符串。
    *
    * @param pointer 原生字符串指针
    */
    private void freeString(MemorySegment pointer) {
        try {
            freeStringHandle.invokeExact(pointer);
        } catch (Throwable t) {
            log.warn("wcdb_free_string 调用异常: {}", t.getMessage());
        }
    }

    /**
    * 绑定有返回值的原生函数。
    *
    * @param lookup     符号查找表
    * @param name       函数符号名
    * @param returnLayout 返回值布局
    * @param argLayouts 参数布局
    * @return 下行调用句柄
    */
    private static MethodHandle bind(SymbolLookup lookup, String name,
                                     ValueLayout returnLayout, ValueLayout... argLayouts) {
        MemorySegment symbol = lookup.find(name)
                .orElseThrow(() -> new IllegalStateException("wcdb_api.dll 缺少导出符号: " + name));
        return LINKER.downcallHandle(symbol, FunctionDescriptor.of(returnLayout, argLayouts));
    }

    /**
    * 绑定无返回值的原生函数。
    *
    * @param lookup     符号查找表
    * @param name       函数符号名
    * @param argLayouts 参数布局
    * @return 下行调用句柄
    */
    private static MethodHandle bindVoid(SymbolLookup lookup, String name, ValueLayout... argLayouts) {
        MemorySegment symbol = lookup.find(name)
                .orElseThrow(() -> new IllegalStateException("wcdb_api.dll 缺少导出符号: " + name));
        return LINKER.downcallHandle(symbol, FunctionDescriptor.ofVoid(argLayouts));
    }
}
