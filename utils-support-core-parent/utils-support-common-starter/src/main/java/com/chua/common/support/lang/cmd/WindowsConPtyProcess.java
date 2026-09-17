package com.chua.common.support.lang.cmd;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
* Windows ConPTY 进程封装，基于 JDK Panama FFI 调用 kernel32 实现伪控制台子进程。
* <p>
* 仅 Windows 10 1809 及以上可用，使用前通过 {@link #isAvailable()} 判断当前环境是否支持。
* 提供伪终端输入管道、UTF-16LE 环境块与 ConPTY 进程属性列表的封装，对外暴露 {@link InputStream} 与 {@link #waitFor()}。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public final class WindowsConPtyProcess implements Closeable {

    /**
    * 当前进程 ConPTY 可用标志，{@code true} 表示当前为 Windows 10 1809+ 且 kernel32 符号解析成功
    */
    private static final boolean AVAILABLE;

    // region Panama FFI 绑定

    /**
    * 系统 Linker，用于生成 native downcall
    */
    private static final Linker LINKER = Linker.nativeLinker();

    /**
    * kernel32 符号查找器
    */
    private static final SymbolLookup K32;

    /**
    * kernel32!CreatePipe 方法句柄
    */
    private static final MethodHandle CreatePipe;

    /**
    * kernel32!CloseHandle 方法句柄
    */
    private static final MethodHandle CloseHandle;

    /**
    * kernel32!GetLastError 方法句柄
    */
    private static final MethodHandle GetLastError;

    /**
    * kernel32!WaitForSingleObject 方法句柄
    */
    private static final MethodHandle WaitForSingleObject;

    /**
    * kernel32!GetExitCodeProcess 方法句柄
    */
    private static final MethodHandle GetExitCodeProcess;

    /**
    * kernel32!TerminateProcess 方法句柄
    */
    private static final MethodHandle TerminateProcess;

    /**
    * kernel32!ReadFile 方法句柄
    */
    private static final MethodHandle ReadFile;

    /**
    * kernel32!CreatePseudoConsole 方法句柄
    */
    private static final MethodHandle CreatePseudoConsole;

    /**
    * kernel32!ClosePseudoConsole 方法句柄
    */
    private static final MethodHandle ClosePseudoConsole;

    /**
    * kernel32!InitializeProcThreadAttributeList 方法句柄
    */
    private static final MethodHandle InitializeProcThreadAttributeList;

    /**
    * kernel32!UpdateProcThreadAttribute 方法句柄
    */
    private static final MethodHandle UpdateProcThreadAttribute;

    /**
    * kernel32!DeleteProcThreadAttributeList 方法句柄
    */
    private static final MethodHandle DeleteProcThreadAttributeList;

    /**
    * kernel32!CreateProcessW 方法句柄
    */
    private static final MethodHandle CreateProcessW;

    /**
    * 在 kernel32 中按名字查找符号，未找到抛出 {@link UnsatisfiedLinkError}。
    *
    * @param name 符号名（含或不含 {@code kernel32!} 前缀均可）
    * @return 对应的 MemorySegment
    */
    private static MemorySegment findOrThrow(String name) {
        return K32.find(name).orElseThrow(
                () -> new UnsatisfiedLinkError("kernel32!" + name));
    }

    /**
    * 通过 Linker 生成 native downcall 方法句柄。
    *
    * @param name kernel32 符号名
    * @param desc 函数签名描述
    * @return 对应的 downcall MethodHandle
    */
    private static MethodHandle mh(String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(findOrThrow(name), desc);
    }

    // endregion

    // ------------------------------------------------------------------
    // 结构布局
    // ------------------------------------------------------------------

    /**
    * CONSOLE_COORD 结构布局（控制台坐标 X/Y）
    */
    private static final MemoryLayout COORD_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_SHORT.withName("X"),
            ValueLayout.JAVA_SHORT.withName("Y")
    );

    /**
    * SECURITY_ATTRIBUTES 结构布局（句柄安全属性，CreatePipe 需传入）
    */
    private static final MemoryLayout SECURITY_ATTRIBUTES_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("nLength"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("lpSecurityDescriptor"),
            ValueLayout.JAVA_INT.withName("bInheritHandle")
    );

    /**
    * PROCESS_INFORMATION 结构布局（CreateProcessW 输出进程/线程句柄与 ID）
    */
    private static final MemoryLayout PROCESS_INFORMATION_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("hProcess"),
            ValueLayout.ADDRESS.withName("hThread"),
            ValueLayout.JAVA_INT.withName("dwProcessId"),
            ValueLayout.JAVA_INT.withName("dwThreadId")
    );

    /**
    * STARTUPINFO 结构布局（Win64 上为 104 字节）
    */
    private static final MemoryLayout STARTUPINFO_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("cb"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("lpReserved"),
            ValueLayout.ADDRESS.withName("lpDesktop"),
            ValueLayout.ADDRESS.withName("lpTitle"),
            ValueLayout.JAVA_INT.withName("dwX"),
            ValueLayout.JAVA_INT.withName("dwY"),
            ValueLayout.JAVA_INT.withName("dwXSize"),
            ValueLayout.JAVA_INT.withName("dwYSize"),
            ValueLayout.JAVA_INT.withName("dwXCountChars"),
            ValueLayout.JAVA_INT.withName("dwYCountChars"),
            ValueLayout.JAVA_INT.withName("dwFillAttribute"),
            ValueLayout.JAVA_INT.withName("dwFlags"),
            ValueLayout.JAVA_SHORT.withName("wShowWindow"),
            ValueLayout.JAVA_SHORT.withName("cbReserved2"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("lpReserved2"),
            ValueLayout.ADDRESS.withName("hStdInput"),
            ValueLayout.ADDRESS.withName("hStdOutput"),
            ValueLayout.ADDRESS.withName("hStdError")
    );

    /**
    * STARTUPINFO 结构大小（常量 104 字节）
    */
    private static final long STARTUPINFO_SIZE = STARTUPINFO_LAYOUT.byteSize();

    /**
    * STARTUPINFOEX 结构大小 = STARTUPINFO + lpAttributeList
    */
    private static final long STARTUPINFOEX_SIZE;

    // endregion

    /**
    * WaitForSingleObject 使用的无限等待常量（-1）
    */
    private static final int INFINITE = -1;

    /**
    * PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE 属性值
    */
    private static final long PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE = 0x00020016L;

    /**
    * EXTENDED_STARTUPINFO_PRESENT 标志位
    */
    private static final int EXTENDED_STARTUPINFO_PRESENT = 0x00080000;

    /**
    * CREATE_UNICODE_ENVIRONMENT 标志位
    */
    private static final int CREATE_UNICODE_ENVIRONMENT = 0x00000400;

    /**
    * 伪控制台默认列宽
    */
    private static final short CONSOLE_WIDTH = 80;

    /**
    * 伪控制台默认行高
    */
    private static final short CONSOLE_HEIGHT = 300;

    /**
    * 句柄继承标志（TRUE）
    */
    private static final int INHERIT_HANDLES = 1;

    /**
    * 属性列表数量
    */
    private static final int ATTRIBUTE_COUNT = 1;

    /**
    * 管道缓冲区大小（0 表示使用默认）
    */
    private static final int PIPE_BUFFER_SIZE = 0;

    static {
        long siExSize;
        boolean ok = false;
        try {
            String osName = System.getProperty("os.name", "").toLowerCase();
            if (osName.contains("win")) {
                String osVersion = System.getProperty("os.version", "0.0.0");
                String[] parts = osVersion.split("\\.");
                if (parts.length >= 2) {
                    int major = Integer.parseInt(parts[0]);
                    int build = Integer.parseInt(parts[1]);
                    if (major > 10 || (major == 10 && build >= 17763)) {
                        K32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
                        K32.find("CreatePseudoConsole");
                        ok = true;
                    } else {
                        K32 = null;
                    }
                } else {
                    K32 = null;
                }
            } else {
                K32 = null;
            }
        } catch (Throwable ignored) {
            throw new RuntimeException("Failed to initialize ConPTY", ignored);
        }

        if (ok) {
            CreatePipe = mh("CreatePipe", FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            CloseHandle = mh("CloseHandle", FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            GetLastError = mh("GetLastError", FunctionDescriptor.of(
                    ValueLayout.JAVA_INT));
            WaitForSingleObject = mh("WaitForSingleObject", FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            GetExitCodeProcess = mh("GetExitCodeProcess", FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            TerminateProcess = mh("TerminateProcess", FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            ReadFile = mh("ReadFile", FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS));
            CreatePseudoConsole = mh("CreatePseudoConsole", FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    COORD_LAYOUT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            ClosePseudoConsole = mh("ClosePseudoConsole", FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS));
            InitializeProcThreadAttributeList = mh("InitializeProcThreadAttributeList",
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            UpdateProcThreadAttribute = mh("UpdateProcThreadAttribute",
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));
            DeleteProcThreadAttributeList = mh("DeleteProcThreadAttributeList",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            CreateProcessW = mh("CreateProcessW", FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            siExSize = STARTUPINFO_SIZE + ValueLayout.ADDRESS.byteSize();
        } else {
            CreatePipe = null;
            CloseHandle = null;
            GetLastError = null;
            WaitForSingleObject = null;
            GetExitCodeProcess = null;
            TerminateProcess = null;
            ReadFile = null;
            CreatePseudoConsole = null;
            ClosePseudoConsole = null;
            InitializeProcThreadAttributeList = null;
            UpdateProcThreadAttribute = null;
            DeleteProcThreadAttributeList = null;
            CreateProcessW = null;
            siExSize = 0;
        }

        AVAILABLE = ok;
        STARTUPINFOEX_SIZE = siExSize;
    }

    /** 是否Available */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** 是否Windows */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    // ==================== 实例 ====================

    /**
    * ConPTY 伪控制台句柄
    */
    private MemorySegment hPC;

    /**
    * 子进程 stdin 写端句柄
    */
    private MemorySegment hInputWrite;

    /**
    * 子进程 stdout 读端句柄
    */
    private MemorySegment hOutputRead;

    /**
    * 子进程句柄
    */
    private MemorySegment hProcess;

    /**
    * 子进程主线程句柄
    */
    private MemorySegment hThread;

    /**
    * 输出字节流封装
    */
    private ConPtyInputStream inputStream;

    /**
    * 创建 WindowsConPtyProcess 实例
    * @param hPC hPC
    * @param hInputWrite hInputWrite
    * @param hOutputRead hOutputRead
    * @param hProcess hProcess
    * @param hThread hThread
    * @param inputStream inputStream
    */
    private WindowsConPtyProcess(MemorySegment hPC, MemorySegment hInputWrite,
                                  MemorySegment hOutputRead, MemorySegment hProcess,
                                  MemorySegment hThread, ConPtyInputStream inputStream) {
        this.hPC = hPC;
        this.hInputWrite = hInputWrite;
        this.hOutputRead = hOutputRead;
        this.hProcess = hProcess;
        this.hThread = hThread;
        this.inputStream = inputStream;
    }

    /**
    * 将 Java 字符串转换为 UTF-16LE 编码、以双 NUL 结尾的 Windows 宽字符串。
    *
    * @param arena 用于分配内存的 Arena
    * @param s     源字符串
    * @return 指向宽字符串内存的 MemorySegment
    */
    private static MemorySegment toWideString(Arena arena, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_16LE);
        MemorySegment seg = arena.allocate(bytes.length + 2);
        for (int i = 0; i < bytes.length; i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, bytes[i]);
        }
        seg.set(ValueLayout.JAVA_BYTE, bytes.length, (byte) 0);
        seg.set(ValueLayout.JAVA_BYTE, bytes.length + 1, (byte) 0);
        return seg;
    }

    /**
    * 调用 kernel32!GetLastError 获取最近一次调用的错误码。调用失败统一返回 {@code -1}。
    *
    * @return Win32 错误码
    */
    private static int getLastError() {
        try {
            return (int) GetLastError.invokeExact();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
    * 关闭 Windows 句柄，若句柄为 null 或调用失败则返回 false。
    *
    * @param handle 待关闭的句柄
    * @return true 表示关闭成功
    */
    private static boolean closeHandle(MemorySegment handle) {
        if (handle == null) {
            return false;
        }
        try {
            return (int) CloseHandle.invokeExact(handle) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 开始 */
    public static WindowsConPtyProcess start(String[] cmdArray, String workDir) throws IOException {
        if (!AVAILABLE) {
            throw new UnsupportedOperationException("ConPTY is not available on this system");
        }

        try (var arena = Arena.ofConfined()) {
            // 构造 SECURITY_ATTRIBUTES，句柄设为可继承
            MemorySegment sa = arena.allocate(SECURITY_ATTRIBUTES_LAYOUT);
            sa.set(ValueLayout.JAVA_INT, 0, (int) SECURITY_ATTRIBUTES_LAYOUT.byteSize());
            sa.set(ValueLayout.ADDRESS, 4, MemorySegment.NULL);
            sa.set(ValueLayout.JAVA_INT, 12, INHERIT_HANDLES);

            // 创建子进程输出管道
            MemorySegment outReadRef = arena.allocate(ValueLayout.ADDRESS.byteSize());
            MemorySegment outWriteRef = arena.allocate(ValueLayout.ADDRESS.byteSize());
            int ret;
            try {
                ret = (int) CreatePipe.invokeExact(
                        outReadRef, outWriteRef, sa, PIPE_BUFFER_SIZE);
            } catch (Throwable t) {
                throw new IOException("CreatePipe (output) failed", t);
            }
            if (ret == 0) {
                throw new IOException("CreatePipe (output) failed, error=" + getLastError());
            }
            MemorySegment outRead = outReadRef.get(ValueLayout.ADDRESS, 0);
            MemorySegment outWrite = outWriteRef.get(ValueLayout.ADDRESS, 0);

            // 创建子进程输入管道
            MemorySegment inReadRef = arena.allocate(ValueLayout.ADDRESS.byteSize());
            MemorySegment inWriteRef = arena.allocate(ValueLayout.ADDRESS.byteSize());
            try {
                ret = (int) CreatePipe.invokeExact(
                        inReadRef, inWriteRef, sa, PIPE_BUFFER_SIZE);
            } catch (Throwable t) {
                closeHandle(outRead);
                closeHandle(outWrite);
                throw new IOException("CreatePipe (input) failed", t);
            }
            if (ret == 0) {
                closeHandle(outRead);
                closeHandle(outWrite);
                throw new IOException("CreatePipe (input) failed, error=" + getLastError());
            }
            MemorySegment inRead = inReadRef.get(ValueLayout.ADDRESS, 0);
            MemorySegment inWrite = inWriteRef.get(ValueLayout.ADDRESS, 0);

            try {
                // 创建伪控制台 (ConPTY)
                MemorySegment coord = arena.allocate(COORD_LAYOUT);
                coord.set(ValueLayout.JAVA_SHORT, 0, CONSOLE_WIDTH);
                coord.set(ValueLayout.JAVA_SHORT, 2, CONSOLE_HEIGHT);

                MemorySegment hpcSeg = arena.allocate(ValueLayout.ADDRESS.byteSize());
                int hr;
                try {
                    hr = (int) CreatePseudoConsole.invokeExact(
                            coord, inRead, outWrite, 0, hpcSeg);
                } catch (Throwable t) {
                    throw new IOException("CreatePseudoConsole failed", t);
                }
                if (hr < 0) {
                    throw new IOException("CreatePseudoConsole failed, HRESULT=" + hr);
                }
                MemorySegment hPC = hpcSeg.get(ValueLayout.ADDRESS, 0);

                // 关闭 ConPTY 已复制走的两端句柄
                closeHandle(inRead);
                closeHandle(outWrite);

                // 查询属性列表所需大小
                MemorySegment attrSizeSeg = arena.allocate(ValueLayout.JAVA_LONG.byteSize());
                try {
                    InitializeProcThreadAttributeList.invokeExact(
                            MemorySegment.NULL, ATTRIBUTE_COUNT, 0, attrSizeSeg);
                } catch (Throwable ignored) {
                }
                long attrListSize = attrSizeSeg.get(ValueLayout.JAVA_LONG, 0);

                // 分配属性列表内存
                MemorySegment attrListMem = arena.allocate(attrListSize);

                // 正式初始化属性列表
                try {
                    ret = (int) InitializeProcThreadAttributeList.invokeExact(
                            attrListMem, ATTRIBUTE_COUNT, 0, attrSizeSeg);
                } catch (Throwable t) {
                    throw new IOException("InitializeProcThreadAttributeList failed", t);
                }
                if (ret == 0) {
                    throw new IOException("InitializeProcThreadAttributeList failed, error=" + getLastError());
                }

                // 把 ConPTY 句柄写入属性列表
                try {
                    ret = (int) UpdateProcThreadAttribute.invokeExact(
                            attrListMem, 0,
                            PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE,
                            hpcSeg, ValueLayout.ADDRESS.byteSize(),
                            MemorySegment.NULL, MemorySegment.NULL);
                } catch (Throwable t) {
                    try { DeleteProcThreadAttributeList.invokeExact(attrListMem); } catch (Throwable ignored) {}
                    throw new IOException("UpdateProcThreadAttribute failed", t);
                }
                if (ret == 0) {
                    try { DeleteProcThreadAttributeList.invokeExact(attrListMem); } catch (Throwable ignored) {}
                    throw new IOException("UpdateProcThreadAttribute failed, error=" + getLastError());
                }

                // 拼接命令行，按需为含空格的参数加引号
                // 注意：此处保留 StringBuilder 显式拼接，避免 StringUtils.join 改变引号转义行为
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < cmdArray.length; i++) {
                    if (i > 0) {
                        sb.append(' ');
                    }
                    String part = cmdArray[i];
                    if (part.contains(" ") || part.contains("\t")) {
                        sb.append('"').append(part.replace("\"", "\\\"")).append('"');
                    } else {
                        sb.append(part);
                    }
                }
                MemorySegment cmdLineMem = toWideString(arena, sb.toString());

                // 构造 STARTUPINFOEX (STARTUPINFO + lpAttributeList)
                MemorySegment siEx = arena.allocate(STARTUPINFOEX_SIZE);
                siEx.set(ValueLayout.JAVA_INT, 0, (int) STARTUPINFOEX_SIZE);
                siEx.set(ValueLayout.ADDRESS, STARTUPINFO_SIZE, attrListMem);

                // 工作目录（UTF-16LE 宽字符串）
                MemorySegment workDirSeg = workDir != null
                        ? toWideString(arena, workDir)
                        : MemorySegment.NULL;

                // 进程信息结构
                MemorySegment pi = arena.allocate(PROCESS_INFORMATION_LAYOUT);

                // 构造环境块并执行 CreateProcessW
                MemorySegment envBlock = buildEnvBlock(arena);
                try {
                    ret = (int) CreateProcessW.invokeExact(
                            MemorySegment.NULL,
                            cmdLineMem,
                            MemorySegment.NULL,
                            MemorySegment.NULL,
                            INHERIT_HANDLES,
                            EXTENDED_STARTUPINFO_PRESENT | CREATE_UNICODE_ENVIRONMENT,
                            envBlock,
                            workDirSeg,
                            siEx,
                            pi
                    );
                } catch (Throwable t) {
                    try { DeleteProcThreadAttributeList.invokeExact(attrListMem); } catch (Throwable ignored) {}
                    closeHandle(hPC);
                    throw new IOException("CreateProcessW failed", t);
                }
                if (ret == 0) {
                    try { DeleteProcThreadAttributeList.invokeExact(attrListMem); } catch (Throwable ignored) {}
                    closeHandle(hPC);
                    throw new IOException("CreateProcessW failed, error=" + getLastError());
                }

                MemorySegment hProc = pi.get(ValueLayout.ADDRESS, 0);
                MemorySegment hThr = pi.get(ValueLayout.ADDRESS, 8);

                // 清理属性列表资源
                try { DeleteProcThreadAttributeList.invokeExact(attrListMem); } catch (Throwable ignored) {}

                // 关闭输入管道写端
                closeHandle(inWrite);
                inWrite = null;

                // 包装 ReadFile 为标准 InputStream
                ConPtyInputStream is = new ConPtyInputStream(outRead);

                return new WindowsConPtyProcess(hPC, null, outRead, hProc, hThr, is);

            } catch (Exception e) {
                if (inRead != null && inRead != MemorySegment.NULL) {
                    closeHandle(inRead);
                }
                if (inWrite != null && inWrite != MemorySegment.NULL) {
                    closeHandle(inWrite);
                }
                if (outRead != null && outRead != MemorySegment.NULL) {
                    closeHandle(outRead);
                }
                if (outWrite != null && outWrite != MemorySegment.NULL) {
                    closeHandle(outWrite);
                }
                throw e instanceof IOException ? (IOException) e : new IOException(e);
            }
        }
    }

    /** 获取InputStream */
    public InputStream getInputStream() {
        return inputStream;
    }

    /** WaitFor */
    public int waitFor() throws InterruptedException {
        if (hProcess == null) {
            return -1;
        }
        try {
            int waitRet = (int) WaitForSingleObject.invokeExact(hProcess, INFINITE);
            if (waitRet != 0) {
                return -1;
            }
            MemorySegment exitCodeSeg = Arena.ofAuto().allocate(ValueLayout.JAVA_INT.byteSize());
            boolean ok = (int) GetExitCodeProcess.invokeExact(hProcess, exitCodeSeg) != 0;
            if (!ok) {
                return -1;
            }
            return exitCodeSeg.get(ValueLayout.JAVA_INT, 0);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** WaitFor */
    public boolean waitFor(long timeout) throws InterruptedException {
        if (hProcess == null) {
            return true;
        }
        try {
            return (int) WaitForSingleObject.invokeExact(hProcess, (int) timeout) == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        if (inputStream != null) {
            try { inputStream.close(); } catch (Exception ignored) {
            }
            inputStream = null;
        }
        if (hThread != null) {
            closeHandle(hThread);
            hThread = null;
        }
        if (hProcess != null) {
            try { TerminateProcess.invokeExact(hProcess, 1); } catch (Throwable ignored) {
            }
            closeHandle(hProcess);
            hProcess = null;
        }
        if (hOutputRead != null) {
            closeHandle(hOutputRead);
            hOutputRead = null;
        }
        if (hInputWrite != null) {
            closeHandle(hInputWrite);
            hInputWrite = null;
        }
        if (hPC != null) {
            try { ClosePseudoConsole.invokeExact(hPC); } catch (Throwable ignored) {
            }
            hPC = null;
        }
    }

    // ==================== 环境块 ====================

    /**
    * 构造子进程的环境块，按 UTF-16LE 拼接并以双 NUL 结尾。
    * <p>
    * 自动读取 Windows 注册表中的系统代理并写入 {@code HTTP_PROXY}/{@code HTTPS_PROXY}，
    * 避免子进程无法访问外网。
    * </p>
    *
    * @param arena 用于分配环境块内存的 Arena
    * @return 指向环境块 UTF-16LE 字符串的 MemorySegment
    */
    static MemorySegment buildEnvBlock(Arena arena) {
        Map<String, String> env = new java.util.LinkedHashMap<>(System.getenv());
        String proxy = env.get("HTTP_PROXY");
        if (proxy == null || proxy.isEmpty()) {
            try {
                java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userRoot()
                        .node("Software/Microsoft/Windows/CurrentVersion/Internet Settings");
                int enabled = prefs.getInt("ProxyEnable", 0);
                String server = prefs.get("ProxyServer", "");
                if (enabled == 1 && server != null && !server.isEmpty()) {
                    proxy = server.startsWith("http://") ? server : "http://" + server;
                    env.put("HTTP_PROXY", proxy);
                    env.put("HTTPS_PROXY", proxy);
                }
            } catch (Exception ignored) {
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : env.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\0');
        }
        sb.append('\0');
        // CREATE_UNICODE_ENVIRONMENT 标志要求使用 UTF-16LE 宽字符环境块
        return toWideString(arena, sb.toString());
    }

    // ==================== 基于 ReadFile 的 InputStream ====================

    /**
    * 基于 kernel32!ReadFile 的标准 InputStream 包装，从子进程 stdout 读端读取数据。
    */
    private static class ConPtyInputStream extends InputStream {

        /**
        * 子进程 stdout 管道读端句柄
        */
        private final MemorySegment handle;

        ConPtyInputStream(MemorySegment handle) {
            this.handle = handle;
        }

        @Override
        /** 读取 */
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n == -1 ? -1 : b[0] & 0xFF;
        }

        @Override
        /** 读取 */
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (handle == null) {
                return -1;
            }
            try (var arena = Arena.ofConfined()) {
                MemorySegment bufSeg = arena.allocate(len);
                MemorySegment bytesReadSeg = arena.allocate(ValueLayout.JAVA_INT.byteSize());
                int ret;
                try {
                    ret = (int) ReadFile.invokeExact(
                            handle, bufSeg, len, bytesReadSeg, MemorySegment.NULL);
                } catch (Throwable t) {
                    throw new IOException("ReadFile failed", t);
                }
                if (ret == 0) {
                    int err = getLastError();
                    if (err == 0x6D || err == 0xE8) {
                        return -1;
                    }
                    return -1;
                }
                int cb = bytesReadSeg.get(ValueLayout.JAVA_INT, 0);
                if (cb == 0) {
                    return -1;
                }
                for (int i = 0; i < cb; i++) {
                    b[off + i] = bufSeg.get(ValueLayout.JAVA_BYTE, i);
                }
                return cb;
            }
        }

        @Override
        /** 关闭 */
        public void close() {
        }
    }
}
