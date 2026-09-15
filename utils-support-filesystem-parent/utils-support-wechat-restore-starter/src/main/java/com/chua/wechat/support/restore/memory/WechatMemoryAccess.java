package com.chua.wechat.support.restore.memory;

import lombok.extern.slf4j.Slf4j;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 微信进程内存只读访问器（Windows / FFM）。
 *
 * <p>基于 Java FFM 直接调用 kernel32 的 {@code OpenProcess} / {@code VirtualQueryEx} /
 * {@code ReadProcessMemory} / {@code CloseHandle}，只读读取微信进程的私有内存。</p>
 *
 * <p><b>为什么读内存就够了：</b>SQLCipher 每次读页都会把密文解密进 SQLite 的
 * pager cache，因此<b>解密后的明文页常驻在微信自己的私有堆里</b>。这条路绕开了
 * 「拿不到数据库密钥」的全部死结，且不需要重启微信、不需要注入。</p>
 *
 * <h3>安全红线（改动本类前务必先读）</h3>
 * <ul>
 *   <li><b>绝不触碰 {@code PAGE_GUARD}</b> —— 那是线程栈守卫页，读取它会在目标进程内
 *       触发 {@code STATUS_GUARD_PAGE_VIOLATION}，破坏栈溢出保护；</li>
 *   <li>本类<b>纯只读</b>，全程不做 {@code VirtualProtectEx}，目标进程的页属性不会改变；</li>
 *   <li>单个区域读取设长度上限，避免一次申请过大缓冲区。</li>
 * </ul>
 *
 * <p>历史教训：早期版本同时犯了「放开保护时包含 PAGE_GUARD」与「从不还原保护」两个错误，
 * 实测期间微信进程号反复变化直至全部消失。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class WechatMemoryAccess implements AutoCloseable {

    /**
     * 进程查询信息权限
     */
    private static final int PROCESS_QUERY_INFORMATION = 0x0400;

    /**
     * 进程内存读取权限
     */
    private static final int PROCESS_VM_READ = 0x0010;

    /**
     * 已提交的内存页
     */
    private static final int MEM_COMMIT = 0x1000;

    /**
     * 私有内存（堆 / 栈）
     */
    private static final int MEM_PRIVATE = 0x20000;

    /**
     * 不可访问页
     */
    private static final int PAGE_NOACCESS = 0x01;

    /**
     * 线程栈守卫页 —— 读取会破坏目标进程栈保护，必须跳过
     */
    private static final int PAGE_GUARD = 0x100;

    /**
     * 只读页
     */
    private static final int PAGE_READONLY = 0x02;

    /**
     * 读写页
     */
    private static final int PAGE_READWRITE = 0x04;

    /**
     * 写时复制页
     */
    private static final int PAGE_WRITECOPY = 0x08;

    /**
     * 可执行 + 只读
     */
    private static final int PAGE_EXECUTE_READ = 0x20;

    /**
     * 可执行 + 读写
     */
    private static final int PAGE_EXECUTE_READWRITE = 0x40;

    /**
     * 可执行 + 写时复制
     */
    private static final int PAGE_EXECUTE_WRITECOPY = 0x80;

    /**
     * 保护属性掩码（低 8 位）
     */
    private static final int PROTECT_MASK = 0xFF;

    /**
     * 单次读取的最大字节数（64MB），超过则分片
     */
    private static final int MAX_READ_CHUNK = 64 << 20;

    /**
     * 用户态地址上界
     */
    private static final long MAX_USER_ADDRESS = 0x7FFFFFFFFFFFL;

    /**
     * MEMORY_BASIC_INFORMATION 结构大小（64 位）
     */
    private static final long MBI_SIZE = 48L;

    private static volatile MethodHandle openProcessHandle;

    private static volatile MethodHandle virtualQueryExHandle;

    private static volatile MethodHandle readProcessMemoryHandle;

    private static volatile MethodHandle closeHandleHandle;

    private static volatile Arena kernelArena;

    /**
     * 进程句柄
     */
    private final long handle;

    /**
     * 进程号
     */
    private final int pid;

    private WechatMemoryAccess(long handle, int pid) {
        this.handle = handle;
        this.pid = pid;
    }

    /**
     * 是否支持内存访问（仅 Windows）。
     *
     * @return Windows 平台返回 true
     */
    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 打开微信进程。
     *
     * @param pid 进程号
     * @return 访问器；失败返回 null
     */
    public static WechatMemoryAccess open(int pid) {
        if (!isSupported()) {
            log.warn("内存访问仅支持 Windows 平台");
            return null;
        }
        try {
            initNative();
            MemorySegment segment = (MemorySegment) openProcessHandle.invokeWithArguments(
                    PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, 0, pid);
            long address = segment.address();
            if (address == 0) {
                log.warn("OpenProcess 失败（pid={}）：读取其它进程内存通常需要管理员权限", pid);
                return null;
            }
            return new WechatMemoryAccess(address, pid);
        } catch (Throwable t) {
            log.warn("初始化本地调用失败: {}", t.getMessage());
            return null;
        }
    }

    /**
     * 进程号。
     *
     * @return 进程号
     */
    public int pid() {
        return pid;
    }

    /**
     * 枚举已提交且可读的内存区域。
     *
     * <p>{@code PAGE_GUARD} 与 {@code PAGE_NOACCESS} 区域会被直接排除。</p>
     *
     * @return 区域列表
     */
    public List<Region> regions() {
        List<Region> regions = new ArrayList<>(4096);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mbi = arena.allocate(MBI_SIZE);
            long address = 0;
            while (address < MAX_USER_ADDRESS) {
                long read = (long) virtualQueryExHandle.invokeWithArguments(
                        MemorySegment.ofAddress(handle), MemorySegment.ofAddress(address), mbi, MBI_SIZE);
                if (read == 0) {
                    break;
                }
                long base = mbi.get(ValueLayout.JAVA_LONG, 0);
                long size = mbi.get(ValueLayout.JAVA_LONG, 24);
                int state = mbi.get(ValueLayout.JAVA_INT, 32);
                int protect = mbi.get(ValueLayout.JAVA_INT, 36);
                int type = mbi.get(ValueLayout.JAVA_INT, 40);
                if (state == MEM_COMMIT && size > 0 && size < (1L << 32) && canRead(protect)) {
                    regions.add(new Region(base, size, protect, type == MEM_PRIVATE));
                }
                long next = base + size;
                if (next <= address) {
                    break;
                }
                address = next;
            }
        } catch (Throwable t) {
            log.warn("枚举内存区域失败（pid={}）: {}", pid, t.getMessage());
        }
        return regions;
    }

    /**
     * 判断保护属性是否允许读取。
     *
     * <p>{@code PAGE_GUARD} 必须排除：读取它会在目标进程内触发守卫页违例。</p>
     *
     * @param protect 保护属性
     * @return 可读返回 true
     */
    private static boolean canRead(int protect) {
        if ((protect & PAGE_GUARD) != 0 || (protect & PAGE_NOACCESS) != 0) {
            return false;
        }
        int base = protect & PROTECT_MASK;
        return base == PAGE_READONLY || base == PAGE_READWRITE || base == PAGE_WRITECOPY
                || base == PAGE_EXECUTE_READ || base == PAGE_EXECUTE_READWRITE
                || base == PAGE_EXECUTE_WRITECOPY;
    }

    /**
     * 读取内存。
     *
     * <p>纯只读操作，不修改目标进程任何页属性。读取失败（区域被释放、越界等）返回 null，
     * 由调用方决定跳过还是终止。</p>
     *
     * @param address 起始地址
     * @param length  长度（字节）
     * @return 读到的字节；失败返回 null
     */
    public byte[] read(long address, int length) {
        if (length <= 0) {
            return null;
        }
        if (length > MAX_READ_CHUNK) {
            return readChunked(address, length);
        }
        return readOnce(address, length);
    }

    /**
     * 分片读取超大区域。
     *
     * @param address 起始地址
     * @param length  总长度
     * @return 读到的字节；失败返回 null
     */
    private byte[] readChunked(long address, int length) {
        byte[] all = new byte[length];
        int done = 0;
        while (done < length) {
            int step = Math.min(MAX_READ_CHUNK, length - done);
            byte[] part = readOnce(address + done, step);
            if (part == null) {
                return done == 0 ? null : java.util.Arrays.copyOf(all, done);
            }
            System.arraycopy(part, 0, all, done, part.length);
            done += part.length;
            if (part.length < step) {
                return java.util.Arrays.copyOf(all, done);
            }
        }
        return all;
    }

    /**
     * 单次读取。
     *
     * @param address 起始地址
     * @param length  长度
     * @return 读到的字节；失败返回 null
     */
    private byte[] readOnce(long address, int length) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(length);
            MemorySegment readBytes = arena.allocate(8);
            int ok = (int) readProcessMemoryHandle.invokeWithArguments(
                    MemorySegment.ofAddress(handle), MemorySegment.ofAddress(address),
                    buffer, (long) length, readBytes);
            if (ok == 0) {
                return null;
            }
            long got = readBytes.get(ValueLayout.JAVA_LONG, 0);
            if (got <= 0) {
                return null;
            }
            byte[] result = new byte[(int) Math.min(got, length)];
            MemorySegment.copy(buffer, ValueLayout.JAVA_BYTE, 0, result, 0, result.length);
            return result;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void close() {
        if (handle == 0) {
            return;
        }
        try {
            closeHandleHandle.invokeWithArguments(MemorySegment.ofAddress(handle));
        } catch (Throwable ignored) {
            // 忽略关闭失败
        }
    }

    /**
     * 初始化本地调用句柄（幂等）。
     *
     * @throws Throwable 初始化失败
     */
    private static synchronized void initNative() throws Throwable {
        if (openProcessHandle != null) {
            return;
        }
        Linker linker = Linker.nativeLinker();
        kernelArena = Arena.ofShared();
        SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32.dll", kernelArena);
        openProcessHandle = linker.downcallHandle(kernel32.find("OpenProcess").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT));
        virtualQueryExHandle = linker.downcallHandle(kernel32.find("VirtualQueryEx").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        readProcessMemoryHandle = linker.downcallHandle(kernel32.find("ReadProcessMemory").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        closeHandleHandle = linker.downcallHandle(kernel32.find("CloseHandle").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    }

    /**
     * 内存区域描述。
     *
     * @param base      起始地址
     * @param size      大小
     * @param protect   保护属性
     * @param privateMem 是否私有内存（堆 / 栈）；false 表示映射区（DLL / 共享内存）
     */
    public record Region(long base, long size, int protect, boolean privateMem) {
    }
}
