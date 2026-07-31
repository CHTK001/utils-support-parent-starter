package com.chua.common.support.lang.cmd;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class WindowsConPtyProcess implements Closeable {

    private static final boolean AVAILABLE;

    // region Panama FFI 绑定

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup K32;
    private static final MethodHandle CreatePipe;
    private static final MethodHandle CloseHandle;
    private static final MethodHandle GetLastError;
    private static final MethodHandle WaitForSingleObject;
    private static final MethodHandle GetExitCodeProcess;
    private static final MethodHandle TerminateProcess;
    private static final MethodHandle ReadFile;
    private static final MethodHandle CreatePseudoConsole;
    private static final MethodHandle ClosePseudoConsole;
    private static final MethodHandle InitializeProcThreadAttributeList;
    private static final MethodHandle UpdateProcThreadAttribute;
    private static final MethodHandle DeleteProcThreadAttributeList;
    private static final MethodHandle CreateProcessW;

    private static MemorySegment findOrThrow(String name) {
        return K32.find(name).orElseThrow(
                () -> new UnsatisfiedLinkError("kernel32!" + name));
    }

    private static MethodHandle mh(String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(findOrThrow(name), desc);
    }

    // endregion

    // region 结构布局

    private static final MemoryLayout COORD_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_SHORT.withName("X"),
            ValueLayout.JAVA_SHORT.withName("Y")
    );

    private static final MemoryLayout SECURITY_ATTRIBUTES_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("nLength"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("lpSecurityDescriptor"),
            ValueLayout.JAVA_INT.withName("bInheritHandle")
    );

    private static final MemoryLayout PROCESS_INFORMATION_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("hProcess"),
            ValueLayout.ADDRESS.withName("hThread"),
            ValueLayout.JAVA_INT.withName("dwProcessId"),
            ValueLayout.JAVA_INT.withName("dwThreadId")
    );

    // STARTUPINFO (104 bytes on Win64)
    private static final MemoryLayout STARTUPINFO_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("cb"),                    // 0
            MemoryLayout.paddingLayout(4),                          // 4
            ValueLayout.ADDRESS.withName("lpReserved"),             // 8
            ValueLayout.ADDRESS.withName("lpDesktop"),              // 16
            ValueLayout.ADDRESS.withName("lpTitle"),                // 24
            ValueLayout.JAVA_INT.withName("dwX"),                   // 32
            ValueLayout.JAVA_INT.withName("dwY"),                   // 36
            ValueLayout.JAVA_INT.withName("dwXSize"),               // 40
            ValueLayout.JAVA_INT.withName("dwYSize"),               // 44
            ValueLayout.JAVA_INT.withName("dwXCountChars"),         // 48
            ValueLayout.JAVA_INT.withName("dwYCountChars"),         // 52
            ValueLayout.JAVA_INT.withName("dwFillAttribute"),       // 56
            ValueLayout.JAVA_INT.withName("dwFlags"),               // 60
            ValueLayout.JAVA_SHORT.withName("wShowWindow"),         // 64
            ValueLayout.JAVA_SHORT.withName("cbReserved2"),         // 66
            MemoryLayout.paddingLayout(4),                          // 68
            ValueLayout.ADDRESS.withName("lpReserved2"),            // 72
            ValueLayout.ADDRESS.withName("hStdInput"),              // 80
            ValueLayout.ADDRESS.withName("hStdOutput"),             // 88
            ValueLayout.ADDRESS.withName("hStdError")               // 96
    );
    private static final long STARTUPINFO_SIZE = STARTUPINFO_LAYOUT.byteSize(); // 104

    // STARTUPINFOEX = STARTUPINFO + lpAttributeList
    private static final long STARTUPINFOEX_SIZE;

    // endregion

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

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    // ==================== 实例 ====================

    private MemorySegment hPC;
    private MemorySegment hInputWrite;
    private MemorySegment hOutputRead;
    private MemorySegment hProcess;
    private MemorySegment hThread;
    private ConPtyInputStream inputStream;

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

    private static int getLastError() {
        try {
            return (int) GetLastError.invokeExact();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean closeHandle(MemorySegment handle) {
        if (handle == null) return false;
        try {
            return (int) CloseHandle.invokeExact(handle) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final int INFINITE = -1;
    private static final long PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE = 0x00020016L;
    private static final int EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
    private static final int CREATE_UNICODE_ENVIRONMENT = 0x00000400;

    public static WindowsConPtyProcess start(String[] cmdArray, String workDir) throws IOException {
        if (!AVAILABLE) {
            throw new UnsupportedOperationException("ConPTY is not available on this system");
        }

        try (var arena = Arena.ofConfined()) {
            // 1. 安全属性（句柄可继承）
            MemorySegment sa = arena.allocate(SECURITY_ATTRIBUTES_LAYOUT);
            sa.set(ValueLayout.JAVA_INT, 0, (int) SECURITY_ATTRIBUTES_LAYOUT.byteSize());
            sa.set(ValueLayout.ADDRESS, 4, MemorySegment.NULL);
            sa.set(ValueLayout.JAVA_INT, 12, 1); // bInheritHandle = TRUE

            // 2. 输出管道
            MemorySegment outReadRef = arena.allocate(ValueLayout.ADDRESS.byteSize());
            MemorySegment outWriteRef = arena.allocate(ValueLayout.ADDRESS.byteSize());
            int ret;
            try {
                ret = (int) CreatePipe.invokeExact(
                        outReadRef, outWriteRef, sa, 0);
            } catch (Throwable t) {
                throw new IOException("CreatePipe (output) failed", t);
            }
            if (ret == 0) {
                throw new IOException("CreatePipe (output) failed, error=" + getLastError());
            }
            MemorySegment outRead = outReadRef.get(ValueLayout.ADDRESS, 0);
            MemorySegment outWrite = outWriteRef.get(ValueLayout.ADDRESS, 0);

            // 3. 输入管道
            MemorySegment inReadRef = arena.allocate(ValueLayout.ADDRESS.byteSize());
            MemorySegment inWriteRef = arena.allocate(ValueLayout.ADDRESS.byteSize());
            try {
                ret = (int) CreatePipe.invokeExact(
                        inReadRef, inWriteRef, sa, 0);
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
                // 4. 创建伪控制台
                MemorySegment coord = arena.allocate(COORD_LAYOUT);
                coord.set(ValueLayout.JAVA_SHORT, 0, (short) 80);
                coord.set(ValueLayout.JAVA_SHORT, 2, (short) 300);

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

                // 5. 关闭 ConPTY 已复制的管道端
                closeHandle(inRead);
                closeHandle(outWrite);

                // 6. 计算属性列表大小
                MemorySegment attrSizeSeg = arena.allocate(ValueLayout.JAVA_LONG.byteSize());
                try {
                    InitializeProcThreadAttributeList.invokeExact(
                            MemorySegment.NULL, 1, 0, attrSizeSeg);
                } catch (Throwable ignored) {
                }
                long attrListSize = attrSizeSeg.get(ValueLayout.JAVA_LONG, 0);

                // 7. 分配属性列表
                MemorySegment attrListMem = arena.allocate(attrListSize);

                // 8. 初始化属性列表
                try {
                    ret = (int) InitializeProcThreadAttributeList.invokeExact(
                            attrListMem, 1, 0, attrSizeSeg);
                } catch (Throwable t) {
                    throw new IOException("InitializeProcThreadAttributeList failed", t);
                }
                if (ret == 0) {
                    throw new IOException("InitializeProcThreadAttributeList failed, error=" + getLastError());
                }

                // 9. 设置伪控制台属性
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

                // 10. 构建命令行
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < cmdArray.length; i++) {
                    if (i > 0) sb.append(' ');
                    String part = cmdArray[i];
                    if (part.contains(" ") || part.contains("\t")) {
                        sb.append('"').append(part.replace("\"", "\\\"")).append('"');
                    } else {
                        sb.append(part);
                    }
                }
                MemorySegment cmdLineMem = toWideString(arena, sb.toString());

                // 11. 构建 STARTUPINFOEX (STARTUPINFO + lpAttributeList)
                MemorySegment siEx = arena.allocate(STARTUPINFOEX_SIZE);
                siEx.set(ValueLayout.JAVA_INT, 0, (int) STARTUPINFOEX_SIZE); // cb
                siEx.set(ValueLayout.ADDRESS, STARTUPINFO_SIZE, attrListMem); // lpAttributeList

                // 12. 工作目录
                MemorySegment workDirSeg = workDir != null
                        ? toWideString(arena, workDir)
                        : MemorySegment.NULL;

                // 13. 进程信息
                MemorySegment pi = arena.allocate(PROCESS_INFORMATION_LAYOUT);

                // 14. 创建进程
                MemorySegment envBlock = buildEnvBlock(arena);
                try {
                    ret = (int) CreateProcessW.invokeExact(
                            MemorySegment.NULL,                 // lpApplicationName
                            cmdLineMem,                          // lpCommandLine
                            MemorySegment.NULL,                  // lpProcessAttributes
                            MemorySegment.NULL,                  // lpThreadAttributes
                            1,                                   // bInheritHandles = TRUE
                            EXTENDED_STARTUPINFO_PRESENT | CREATE_UNICODE_ENVIRONMENT,
                            envBlock,                            // lpEnvironment
                            workDirSeg,                          // lpCurrentDirectory
                            siEx,                                // lpStartupInfo
                            pi                                   // lpProcessInformation
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

                // 15. 关闭属性列表
                try { DeleteProcThreadAttributeList.invokeExact(attrListMem); } catch (Throwable ignored) {}

                // 16. 关闭输入管道的写端
                closeHandle(inWrite);
                inWrite = null;

                // 17. 创建输入流
                ConPtyInputStream is = new ConPtyInputStream(outRead);

                return new WindowsConPtyProcess(hPC, null, outRead, hProc, hThr, is);

            } catch (Exception e) {
                if (inRead != null && inRead != MemorySegment.NULL) closeHandle(inRead);
                if (inWrite != null && inWrite != MemorySegment.NULL) closeHandle(inWrite);
                if (outRead != null && outRead != MemorySegment.NULL) closeHandle(outRead);
                if (outWrite != null && outWrite != MemorySegment.NULL) closeHandle(outWrite);
                throw e instanceof IOException ? (IOException) e : new IOException(e);
            }
        }
    }

    public InputStream getInputStream() {
        return inputStream;
    }

    public int waitFor() throws InterruptedException {
        if (hProcess == null) return -1;
        try {
            int waitRet = (int) WaitForSingleObject.invokeExact(hProcess, INFINITE);
            if (waitRet != 0) return -1;
            MemorySegment exitCodeSeg = Arena.ofAuto().allocate(ValueLayout.JAVA_INT.byteSize());
            boolean ok = (int) GetExitCodeProcess.invokeExact(hProcess, exitCodeSeg) != 0;
            if (!ok) return -1;
            return exitCodeSeg.get(ValueLayout.JAVA_INT, 0);
        } catch (Throwable t) {
            return -1;
        }
    }

    public boolean waitFor(long timeout) throws InterruptedException {
        if (hProcess == null) return true;
        try {
            return (int) WaitForSingleObject.invokeExact(hProcess, (int) timeout) == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
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

    private static class ConPtyInputStream extends InputStream {
        private final MemorySegment handle;

        ConPtyInputStream(MemorySegment handle) {
            this.handle = handle;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n == -1 ? -1 : b[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (handle == null) return -1;
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
                    if (err == 0x6D || err == 0xE8) return -1;
                    return -1;
                }
                int cb = bytesReadSeg.get(ValueLayout.JAVA_INT, 0);
                if (cb == 0) return -1;
                for (int i = 0; i < cb; i++) {
                    b[off + i] = bufSeg.get(ValueLayout.JAVA_BYTE, i);
                }
                return cb;
            }
        }

        @Override
        public void close() {
        }
    }
}
