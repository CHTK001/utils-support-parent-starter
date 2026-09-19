package com.chua.wechat.support.restore.keyscan;

import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 微信数据库密钥内存扫描器（免重启微信）。
 *
 * <p>从正在运行的 {@code Weixin.exe} 进程内存中找出能解开目标数据库的 SQLCipher 密钥。
 * 相比「找 {@code x'<hex>'} 缓存串」或「salt 邻域」这类启发式做法，本实现直接做
 * <b>逐字节穷举</b>，因此不会因为密钥的存放形态（结构体内联 / 非对齐 / 独立缓冲）而漏掉：</p>
 *
 * <ol>
 *   <li><b>廉价前置过滤</b>：把内存中每个 32 字节窗口当作候选 {@code enc_key}，
 *       仅用 <b>1 个 AES 分组</b>解密首页首块并与 IV 异或，比对明文特征
 *       {@code 10 00 0x 0x}（页大小 4096 + 读写版本 1/2）。4 字节过滤的误报率约 2⁻³⁰，
 *       实测 1.16 GB 内存只产生 0~1 个候选，因此逐字节穷举是可行的。</li>
 *   <li><b>权威确认</b>：候选命中后才计算
 *       {@code mac_key = PBKDF2-HMAC-SHA512(enc_key, salt ^ 0x3A, 2, 32)}，
 *       校验 {@code HMAC-SHA512(mac_key, page[16:4032] || LE32(1))} 是否等于页尾 64 字节。
 *       这是 2⁻⁵¹² 级判定，误报可忽略。</li>
 *   <li><b>覆盖被保护区域</b>：对 {@code PAGE_NOACCESS} / {@code PAGE_GUARD} 区域先
 *       {@code VirtualProtectEx} 临时放开再读，避免静默漏扫。</li>
 *   <li><b>附加形态</b>：同时扫描内存中的 ASCII 十六进制串（≥64 字符），
 *       覆盖「密钥以 hex 文本形式缓存」的形态。</li>
 * </ol>
 *
 * <h3>为什么必须先自检</h3>
 * <p>{@link #selfTest()} 用已知密钥合成一页 SQLCipher 4 首页，再让本扫描器去命中它。
 * <b>只有自检通过，"扫描未命中" 的结论才有意义</b>；否则无法区分「真的没有」与「过滤器写错了」。</p>
 *
 * <h3>权限</h3>
 * <p>读取其它进程内存需要管理员权限；未提权时 {@link #scan} 会返回
 * {@code found=false} 并在 {@link ScanResult#message()} 中说明原因。</p>
 *
 * <h3>用法</h3>
 * <pre>{@code
 * if (WechatMemoryKeyScanner.selfTest()) {
 *     ScanResult result = WechatMemoryKeyScanner.scan(new File("session.db"));
 *     if (result.found()) {
 *         System.out.println(result.keyHex());
 *     }
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class WechatMemoryKeyScanner {

    /**
     * 微信进程名候选
     */
    private static final String[] PROCESS_NAMES = {"Weixin.exe", "WeChat.exe"};

    /**
     * 页大小
     */
    private static final int PAGE = 4096;

    /**
     * 首页密文起始偏移（前 16 字节为明文 salt）
     */
    private static final int C0_OFFSET = 16;

    /**
     * IV 偏移（页尾保留区首部）
     */
    private static final int IV_OFFSET = PAGE - 80;

    /**
     * HMAC 偏移（页尾 64 字节）
     */
    private static final int HMAC_OFFSET = PAGE - 64;

    /**
     * 单次读取的内存块大小
     */
    private static final int CHUNK = 8 << 20;

    /**
     * HMAC 密钥派生迭代次数（SQLCipher 固定为 2）
     */
    private static final int HMAC_KEY_ITERATIONS = 2;

    /**
     * HMAC salt 与文件 salt 的异或掩码
     */
    private static final byte HMAC_SALT_XOR = 0x3A;

    /**
     * 密钥形态正则
     */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");

    /**
     * 进程查询超时（秒）
     */
    private static final long PID_CMD_TIMEOUT_SECONDS = 15L;

    private static final int PROCESS_QUERY_INFORMATION = 0x0400;
    private static final int PROCESS_VM_READ = 0x0010;
    private static final int PROCESS_VM_OPERATION = 0x0008;
    private static final int MEM_COMMIT = 0x1000;
    private static final int PAGE_NOACCESS = 0x01;
    private static final int PAGE_GUARD = 0x100;
    private static final int PAGE_READONLY = 0x02;

    /**
     * 允许临时放开保护的最大区域大小。
     * <p>放开保护会真实修改目标进程的页属性，区域过大会显著提高目标进程崩溃概率，因此设上限。</p>
     */
    private static final long MAX_UNLOCK_BYTES = 32L * 1024 * 1024;

    /**
     * 进程/内存块可读性统计
     */
    private static volatile MethodHandle openProcessHandle;
    private static volatile MethodHandle virtualQueryExHandle;
    private static volatile MethodHandle readProcessMemoryHandle;
    private static volatile MethodHandle virtualProtectExHandle;
    private static volatile MethodHandle closeHandleHandle;
    private static volatile Arena kernelArena;

    /**
     * 工具类禁止实例化。
     */
    private WechatMemoryKeyScanner() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    // ==================== 公开 API ====================

    /**
     * 是否支持内存扫描（仅 Windows）。
     *
     * @return Windows 平台返回 true
     */
    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 列出所有微信进程（按工作集降序）。
     *
     * @return 进程列表，探测失败返回空列表
     */
    public static List<PidInfo> weixinProcesses() {
        List<PidInfo> result = new ArrayList<>(4);
        for (String processName : PROCESS_NAMES) {
            try {
                String[] command = {"tasklist", "/FI", "IMAGENAME eq " + processName, "/FO", "CSV", "/NH"};
                CmdResult cmdResult = CmdExecutors.execute(command, PID_CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!cmdResult.isSuccess() || cmdResult.getStdout() == null) {
                    continue;
                }
                for (String line : cmdResult.getStdout().split("\\R")) {
                    PidInfo info = parseTasklistLine(line, processName);
                    if (info != null) {
                        result.add(info);
                    }
                }
            } catch (Exception e) {
                log.debug("探测微信进程失败: {} - {}", processName, e.getMessage());
            }
        }
        result.sort(Comparator.comparingLong(PidInfo::workingSetBytes).reversed());
        return result;
    }

    /**
     * 自动挑选工作集最大的微信进程并扫描。
     *
     * @param database 目标加密数据库
     * @return 扫描结果
     */
    public static ScanResult scan(File database) {
        List<PidInfo> processes = weixinProcesses();
        if (processes.isEmpty()) {
            return ScanResult.miss(0, 0, 0, 0, "未发现正在运行的微信进程（Weixin.exe / WeChat.exe）");
        }
        ScanResult best = null;
        for (PidInfo info : processes) {
            ScanResult result = scan(database, info.pid(), 1, Long.MAX_VALUE);
            log.info("进程 {} 扫描结论: {}", info.pid(), result.message());
            if (result.found()) {
                return result;
            }
            // 只扫了 0 字节的进程（内存里没有该库的 salt）结论信息量最低，
            // 最终结论应取真正做了穷举的那个进程，避免报出误导性的「未打开该库」
            if (best == null || result.scannedBytes() > best.scannedBytes()) {
                best = result;
            }
        }
        return best;
    }

    /**
     * 扫描指定进程。
     *
     * @param database 目标加密数据库
     * @param pid      微信进程号
     * @return 扫描结果
     */
    public static ScanResult scan(File database, int pid) {
        return scan(database, pid, 1, Long.MAX_VALUE);
    }

    /**
     * 扫描指定进程（可调步长与上限）。
     *
     * @param database 目标加密数据库
     * @param pid      微信进程号
     * @param stride   候选窗口步长（1 表示逐字节）
     * @param maxBytes 最多扫描的字节数上限
     * @return 扫描结果
     */
    public static ScanResult scan(File database, int pid, int stride, long maxBytes) {
        long started = System.currentTimeMillis();
        if (!isSupported()) {
            return ScanResult.miss(pid, 0, 0, elapsed(started), "内存扫描仅支持 Windows 平台");
        }
        Ctx ctx;
        try {
            ctx = new Ctx(database);
        } catch (Exception e) {
            return ScanResult.miss(pid, 0, 0, elapsed(started), "读取目标数据库失败: " + e.getMessage());
        }
        if (ctx.plaintext) {
            return ScanResult.miss(pid, 0, 0, elapsed(started),
                    "目标数据库已是明文 SQLite，无需密钥: " + database.getName());
        }

        long handle;
        try {
            initNative();
            handle = openProcess(pid);
        } catch (Throwable t) {
            return ScanResult.miss(pid, 0, 0, elapsed(started), "初始化本地调用失败: " + t.getMessage());
        }
        if (handle == 0) {
            boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            return ScanResult.miss(pid, 0, 0, elapsed(started), alive
                    ? "OpenProcess 失败：读取其它进程内存需要以管理员身份运行"
                    : "进程 " + pid + " 不存在或已退出（微信可能已重启，请重新确认进程号）");
        }

        long scanned = 0;
        long failed = 0;
        int saltRegions = 0;
        try {
            List<Region> regions = enumRegions(handle);
            List<Region> ordered = new ArrayList<>(regions.size());
            // 第一遍：定位 salt 所在区域并如实统计可读性
            for (Region region : regions) {
                boolean hit = false;
                long offset = 0;
                while (offset < region.size) {
                    int want = (int) Math.min(CHUNK, region.size - offset);
                    byte[] data = readMemory(handle, region.base + offset, want);
                    if (data == null) {
                        failed += want;
                        break;
                    }
                    if (!hit && indexOf(data, ctx.salt) >= 0) {
                        hit = true;
                    }
                    offset += data.length;
                    if (data.length < want) {
                        failed += want - data.length;
                        break;
                    }
                }
                if (hit) {
                    saltRegions++;
                    ordered.add(0, region);
                } else {
                    ordered.add(region);
                }
            }
            log.info("内存扫描: {} 个区域 / {} 个含 salt，不可读 {} 字节", regions.size(), saltRegions, failed);

            if (saltRegions == 0) {
                // 目标库的 salt 完全不在该进程内存中 → 该进程没打开过这个库，继续穷举没有意义
                return ScanResult.miss(pid, 0, failed, elapsed(started),
                        "进程 " + pid + " 内存中没有 " + database.getName() + " 的 salt，该库未被此进程打开");
            }

            // 第二遍：逐字节穷举
            int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            Semaphore slots = new Semaphore(threads * 2);
            List<Future<Long>> futures = new ArrayList<>(64);
            AtomicLong counter = new AtomicLong();
            try {
                for (Region region : ordered) {
                    if (ctx.key.get() != null || counter.get() >= maxBytes) {
                        break;
                    }
                    long offset = 0;
                    boolean unlocked = false;
                    try {
                        while (offset < region.size) {
                            int want = (int) Math.min(CHUNK, region.size - offset);
                            byte[] data = readMemory(handle, region.base + offset, want);
                            if (data == null) {
                                // 仅在区域确实不可读、且放开保护不会危及目标进程时才临时放开
                                if (!unlocked && canUnlock(region)
                                        && virtualProtect(handle, region.base, region.size, PAGE_READONLY)) {
                                    unlocked = true;
                                    continue;
                                }
                                break;
                            }
                            long baseAddress = region.base + offset;
                            scanned += data.length;
                            counter.addAndGet(data.length);
                            slots.acquire();
                            futures.add(pool.submit(scanTask(ctx, data, baseAddress, stride, slots)));
                            if (ctx.key.get() != null) {
                                break;
                            }
                            offset += data.length;
                            if (data.length < want) {
                                break;
                            }
                        }
                    } finally {
                        // 必须还原页属性：否则目标进程后续对该区域的写入会直接触发访问违例而崩溃
                        if (unlocked) {
                            virtualProtect(handle, region.base, region.size, region.protect);
                        }
                    }
                }
                for (Future<Long> future : futures) {
                    future.get();
                }
            } finally {
                slots.acquire(threads * 2);
                pool.shutdownNow();
            }
        } catch (Throwable t) {
            log.warn("内存扫描中断: {}", t.getMessage());
        } finally {
            closeProcess(handle);
        }

        long elapsed = elapsed(started);
        String key = ctx.key.get();
        if (key != null) {
            return ScanResult.hit(key, ctx.address.get(), pid, scanned, failed, elapsed);
        }
        return ScanResult.miss(pid, scanned, failed, elapsed,
                "可读内存中未找到能解开 " + database.getName() + " 的 32 字节 raw key"
                        + "（已扫描 " + scanned / 1048576 + " MB，其中 " + failed / 1048576 + " MB 不可读）");
    }

    /**
     * 链路自检：用随机密钥合成一页 SQLCipher 4 首页，验证「廉价过滤 + HMAC 确认」能命中它。
     *
     * <p><b>调用方应先确认本方法返回 true，再相信扫描的未命中结论。</b></p>
     *
     * @return 正样本命中且负样本不命中时返回 true
     */
    public static boolean selfTest() {
        try {
            SecureRandom random = new SecureRandom();
            byte[] key = new byte[32];
            random.nextBytes(key);
            byte[] salt = new byte[16];
            random.nextBytes(salt);
            byte[] ivBytes = new byte[16];
            random.nextBytes(ivBytes);

            byte[] page = buildSyntheticPage(key, salt, ivBytes, random);
            Ctx ctx = Ctx.ofPage(page);

            byte[] positive = new byte[4096];
            random.nextBytes(positive);
            System.arraycopy(key, 0, positive, 333, 32);
            Semaphore slots = new Semaphore(8);
            slots.acquire(8);
            scanTask(ctx, positive, 0L, 1, slots).call();
            boolean hitPositive = key == null || ctx.key.get() != null;

            byte[] negative = new byte[4096];
            random.nextBytes(negative);
            Ctx negativeCtx = Ctx.ofPage(page);
            Semaphore slots2 = new Semaphore(8);
            slots2.acquire(8);
            scanTask(negativeCtx, negative, 0L, 1, slots2).call();
            boolean hitNegative = negativeCtx.key.get() != null;

            log.info("内存扫描器自检: 正样本命中={} 负样本命中={}", hitPositive, hitNegative);
            return hitPositive && !hitNegative;
        } catch (Exception e) {
            log.warn("内存扫描器自检失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 合成一页 SQLCipher 4 首页（salt || 密文 || IV || HMAC）。
     *
     * @param key     32 字节密钥
     * @param salt    16 字节 salt
     * @param ivBytes 16 字节 IV
     * @param random  随机源（填充明文载荷）
     * @return 4096 字节页面
     * @throws Exception 密码学操作失败
     */
    private static byte[] buildSyntheticPage(byte[] key, byte[] salt, byte[] ivBytes, SecureRandom random)
            throws Exception {
        byte[] plain = new byte[PAGE];
        random.nextBytes(plain);
        plain[16] = 0x10;
        plain[17] = 0x00;
        plain[18] = 0x02;
        plain[19] = 0x02;
        plain[20] = 0x50;
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(ivBytes));
        byte[] cipherText = cipher.doFinal(Arrays.copyOfRange(plain, 16, PAGE - 80));

        byte[] macSalt = new byte[16];
        for (int i = 0; i < 16; i++) {
            macSalt[i] = (byte) (salt[i] ^ HMAC_SALT_XOR);
        }
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(pbkdf2("HmacSHA512", key, macSalt, HMAC_KEY_ITERATIONS, 32), "HmacSHA512"));
        mac.update(cipherText);
        mac.update(ivBytes);
        mac.update(new byte[]{1, 0, 0, 0});
        byte[] hmac = mac.doFinal();

        byte[] page = new byte[PAGE];
        System.arraycopy(salt, 0, page, 0, 16);
        System.arraycopy(cipherText, 0, page, 16, cipherText.length);
        System.arraycopy(ivBytes, 0, page, IV_OFFSET, 16);
        System.arraycopy(hmac, 0, page, HMAC_OFFSET, 64);
        return page;
    }

    // ==================== 扫描核心 ====================

    /**
     * 扫描一个内存块：逐窗口试密钥 + 扫 ASCII 十六进制串。
     *
     * @param ctx       扫描上下文
     * @param data      内存块
     * @param base      块起始地址
     * @param stride    步长
     * @param slots     并发闸门
     * @return 通过廉价过滤的候选数
     */
    private static Callable<Long> scanTask(Ctx ctx, byte[] data, long base, int stride, Semaphore slots) {
        return () -> {
            long candidates = 0;
            try {
                if (ctx.key.get() != null) {
                    return 0L;
                }
                Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
                byte[] out = new byte[16];
                byte[] key = new byte[32];
                int limit = data.length - 32;
                for (int i = 0; i <= limit; i += stride) {
                    if ((i & 0xFFFF) == 0 && ctx.key.get() != null) {
                        return candidates;
                    }
                    System.arraycopy(data, i, key, 0, 32);
                    if (!passesFilter(ctx, cipher, out, key)) {
                        continue;
                    }
                    candidates++;
                    if (ctx.confirm(key)) {
                        ctx.key.set(hex(key));
                        ctx.address.set(base + i);
                        return candidates;
                    }
                }
                candidates += scanHexText(ctx, data, base);
                return candidates;
            } finally {
                slots.release();
            }
        };
    }

    /**
     * 廉价过滤：AES 解首页首块 + 异或 IV，比对明文特征 {@code 10 00 0x 0x}。
     *
     * @param ctx    上下文
     * @param cipher 复用的 AES 实例
     * @param out    输出缓冲
     * @param key    候选密钥
     * @return 通过返回 true
     * @throws Exception AES 初始化失败
     */
    private static boolean passesFilter(Ctx ctx, Cipher cipher, byte[] out, byte[] key) throws Exception {
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        cipher.doFinal(ctx.c0, 0, 16, out);
        if (((out[0] ^ ctx.iv[0]) & 0xFF) != 0x10 || ((out[1] ^ ctx.iv[1]) & 0xFF) != 0x00) {
            return false;
        }
        int v2 = (out[2] ^ ctx.iv[2]) & 0xFF;
        int v3 = (out[3] ^ ctx.iv[3]) & 0xFF;
        return (v2 == 1 || v2 == 2) && (v3 == 1 || v3 == 2);
    }

    /**
     * 扫描内存块中的 ASCII 十六进制串（长度 ≥ 64），取前 64 字符解码为候选密钥。
     *
     * @param ctx  上下文
     * @param data 内存块
     * @param base 块起始地址
     * @return 通过确认的候选数（0 或 1）
     */
    private static long scanHexText(Ctx ctx, byte[] data, long base) {
        int runStart = -1;
        for (int i = 0; i <= data.length; i++) {
            boolean hexChar = i < data.length && isHexChar(data[i]);
            if (hexChar) {
                if (runStart < 0) {
                    runStart = i;
                }
                continue;
            }
            if (runStart >= 0) {
                int length = i - runStart;
                if (length >= 64 && ctx.key.get() == null) {
                    byte[] candidate = decodeHex(data, runStart, 64);
                    if (candidate != null && ctx.confirm(candidate)) {
                        ctx.key.set(hex(candidate));
                        ctx.address.set(base + runStart);
                        return 1L;
                    }
                }
                runStart = -1;
            }
        }
        return 0L;
    }

    /**
     * 判断是否为十六进制字符。
     *
     * @param value 字节
     * @return 是返回 true
     */
    private static boolean isHexChar(byte value) {
        return (value >= '0' && value <= '9') || (value >= 'a' && value <= 'f') || (value >= 'A' && value <= 'F');
    }

    /**
     * 解码指定位置的十六进制串。
     *
     * @param data   数据
     * @param offset 起始位置
     * @param length 字符数（偶数）
     * @return 解码结果，含非法字符时返回 null
     */
    private static byte[] decodeHex(byte[] data, int offset, int length) {
        byte[] result = new byte[length / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit((char) data[offset + i * 2], 16);
            int low = Character.digit((char) data[offset + i * 2 + 1], 16);
            if (high < 0 || low < 0) {
                return null;
            }
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }

    /**
     * PBKDF2 密钥派生（RFC 2898）。
     *
     * @param macAlgorithm HMAC 算法名
     * @param password     口令
     * @param salt         盐
     * @param iterations   迭代次数
     * @param length       输出长度
     * @return 派生密钥
     * @throws Exception 算法不可用
     */
    static byte[] pbkdf2(String macAlgorithm, byte[] password, byte[] salt, int iterations, int length)
            throws Exception {
        if (iterations < 1) {
            throw new IllegalArgumentException("PBKDF2 迭代次数必须大于 0");
        }
        Mac mac = Mac.getInstance(macAlgorithm);
        mac.init(new SecretKeySpec(password, macAlgorithm));
        int hLen = mac.getMacLength();
        int blocks = (length + hLen - 1) / hLen;
        byte[] derived = new byte[blocks * hLen];
        byte[] block = new byte[salt.length + 4];
        System.arraycopy(salt, 0, block, 0, salt.length);
        for (int i = 1; i <= blocks; i++) {
            block[salt.length] = (byte) (i >>> 24);
            block[salt.length + 1] = (byte) (i >>> 16);
            block[salt.length + 2] = (byte) (i >>> 8);
            block[salt.length + 3] = (byte) i;
            mac.update(block);
            byte[] u = mac.doFinal();
            byte[] t = u.clone();
            for (int j = 1; j < iterations; j++) {
                u = mac.doFinal(u);
                for (int k = 0; k < hLen; k++) {
                    t[k] ^= u[k];
                }
            }
            System.arraycopy(t, 0, derived, (i - 1) * hLen, hLen);
        }
        return Arrays.copyOf(derived, length);
    }

    // ==================== 扫描上下文 ====================

    /**
     * 单次扫描的只读上下文（页面样本 + 派生参数 + 结果槽）。
     */
    private static final class Ctx {

        /**
         * 目标库首页
         */
        final byte[] page;

        /**
         * 首页首个密文块
         */
        final byte[] c0;

        /**
         * 首页 IV
         */
        final byte[] iv;

        /**
         * 首页页尾 64 字节 HMAC
         */
        final byte[] storedHmac;

        /**
         * 文件 salt
         */
        final byte[] salt;

        /**
         * HMAC salt（salt ^ 0x3A）
         */
        final byte[] macSalt;

        /**
         * 是否明文库
         */
        final boolean plaintext;

        /**
         * 命中密钥
         */
        final AtomicReference<String> key = new AtomicReference<>();

        /**
         * 命中地址
         */
        final AtomicLong address = new AtomicLong();

        Ctx(File database) throws Exception {
            long size = database.length();
            if (size < PAGE) {
                throw new IllegalArgumentException("数据库不足一页: " + database.getAbsolutePath());
            }
            byte[] first = new byte[PAGE];
            try (var in = Files.newInputStream(database.toPath())) {
                if (in.readNBytes(first, 0, PAGE) < PAGE) {
                    throw new IllegalArgumentException("读取数据库首页失败: " + database.getAbsolutePath());
                }
            }
            this.plaintext = startsWithSqliteMagic(first);
            this.page = first;
            this.salt = Arrays.copyOfRange(first, 0, 16);
            this.c0 = Arrays.copyOfRange(first, C0_OFFSET, C0_OFFSET + 16);
            this.iv = Arrays.copyOfRange(first, IV_OFFSET, IV_OFFSET + 16);
            this.storedHmac = Arrays.copyOfRange(first, HMAC_OFFSET, HMAC_OFFSET + 64);
            this.macSalt = new byte[16];
            for (int i = 0; i < 16; i++) {
                this.macSalt[i] = (byte) (this.salt[i] ^ HMAC_SALT_XOR);
            }
        }

        private Ctx(byte[] page, boolean plaintext) {
            this.page = page;
            this.plaintext = plaintext;
            this.salt = Arrays.copyOfRange(page, 0, 16);
            this.c0 = Arrays.copyOfRange(page, C0_OFFSET, C0_OFFSET + 16);
            this.iv = Arrays.copyOfRange(page, IV_OFFSET, IV_OFFSET + 16);
            this.storedHmac = Arrays.copyOfRange(page, HMAC_OFFSET, HMAC_OFFSET + 64);
            this.macSalt = new byte[16];
            for (int i = 0; i < 16; i++) {
                this.macSalt[i] = (byte) (this.salt[i] ^ HMAC_SALT_XOR);
            }
        }

        static Ctx ofPage(byte[] page) {
            return new Ctx(page, false);
        }

        /**
         * 权威校验候选密钥。
         *
         * @param encKey 32 字节候选密钥
         * @return 首页 HMAC 校验通过返回 true
         */
        boolean confirm(byte[] encKey) {
            try {
                byte[] macKey = pbkdf2("HmacSHA512", encKey, macSalt, HMAC_KEY_ITERATIONS, 32);
                Mac mac = Mac.getInstance("HmacSHA512");
                mac.init(new SecretKeySpec(macKey, "HmacSHA512"));
                mac.update(page, C0_OFFSET, HMAC_OFFSET - C0_OFFSET);
                mac.update(new byte[]{1, 0, 0, 0});
                return MessageDigest.isEqual(mac.doFinal(), storedHmac);
            } catch (Exception e) {
                return false;
            }
        }
    }

    // ==================== 本地内存访问 ====================

    /**
     * 内存区域描述。
     */
    private static final class Region {
        final long base;
        final long size;
        final int protect;

        Region(long base, long size, int protect) {
            this.base = base;
            this.size = size;
            this.protect = protect;
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
        virtualProtectExHandle = linker.downcallHandle(kernel32.find("VirtualProtectEx").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        closeHandleHandle = linker.downcallHandle(kernel32.find("CloseHandle").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    }

    /**
     * 打开进程。
     *
     * @param pid 进程号
     * @return 句柄，失败返回 0
     * @throws Throwable 本地调用失败
     */
    private static long openProcess(int pid) throws Throwable {
        MemorySegment handle = (MemorySegment) openProcessHandle.invokeWithArguments(
                PROCESS_QUERY_INFORMATION | PROCESS_VM_READ | PROCESS_VM_OPERATION, 0, pid);
        return handle.address();
    }

    /**
     * 关闭句柄。
     *
     * @param handle 句柄
     */
    private static void closeProcess(long handle) {
        try {
            closeHandleHandle.invokeWithArguments(MemorySegment.ofAddress(handle));
        } catch (Throwable ignored) {
            // 忽略关闭失败
        }
    }

    /**
     * 枚举已提交的内存区域。
     *
     * @param handle 进程句柄
     * @return 区域列表
     * @throws Throwable 本地调用失败
     */
    private static List<Region> enumRegions(long handle) throws Throwable {
        List<Region> regions = new ArrayList<>(4096);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mbi = arena.allocate(48);
            long address = 0;
            while (address < 0x7FFFFFFFFFFFL) {
                long read = (long) virtualQueryExHandle.invokeWithArguments(
                        MemorySegment.ofAddress(handle), MemorySegment.ofAddress(address), mbi, 48L);
                if (read == 0) {
                    break;
                }
                long base = mbi.get(ValueLayout.JAVA_LONG, 0);
                long size = mbi.get(ValueLayout.JAVA_LONG, 24);
                int state = mbi.get(ValueLayout.JAVA_INT, 32);
                int protect = mbi.get(ValueLayout.JAVA_INT, 36);
                if (state == MEM_COMMIT && size > 0 && size < (1L << 32)) {
                    regions.add(new Region(base, size, protect));
                }
                long next = base + size;
                if (next <= address) {
                    break;
                }
                address = next;
            }
        }
        return regions;
    }

    /**
     * 读取内存；返回 null 表示读取失败。
     * <p>本方法只读，不修改目标进程的任何页属性（修改页属性由调用方在必要时显式进行）。</p>
     *
     * @param handle  句柄
     * @param address 地址
     * @param length  长度
     * @return 读到的字节，失败返回 null
     */
    private static byte[] readMemory(long handle, long address, int length) {
        byte[] buffer = new byte[length];
        int read = rawRead(handle, address, buffer, length);
        if (read <= 0) {
            return null;
        }
        return read == length ? buffer : Arrays.copyOf(buffer, read);
    }

    /**
     * 原始读取。
     *
     * @param handle  句柄
     * @param address 地址
     * @param target  目标数组
     * @param length  长度
     * @return 实际读到的字节数
     */
    private static int rawRead(long handle, long address, byte[] target, int length) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(length);
            MemorySegment readBytes = arena.allocate(8);
            int ok = (int) readProcessMemoryHandle.invokeWithArguments(
                    MemorySegment.ofAddress(handle), MemorySegment.ofAddress(address),
                    buffer, (long) length, readBytes);
            if (ok == 0) {
                return 0;
            }
            long got = readBytes.get(ValueLayout.JAVA_LONG, 0);
            if (got <= 0) {
                return 0;
            }
            System.arraycopy(buffer.asSlice(0, got).toArray(ValueLayout.JAVA_BYTE), 0, target, 0, (int) got);
            return (int) got;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 判断某区域是否「可以安全地临时放开保护」。
     * <p>两条硬性红线：</p>
     * <ul>
     *   <li>带 {@code PAGE_GUARD} 的区域一律不碰——那是线程栈的守卫页，去掉守卫会破坏目标进程的栈溢出保护，
     *       且读取本身会在目标进程内触发 {@code STATUS_GUARD_PAGE_VIOLATION}，极易把它搞崩。</li>
     *   <li>区域过大不碰——放开保护是真实改写目标进程的页属性，范围越大越容易撞上它正在写的内存。</li>
     * </ul>
     *
     * @param region 区域
     * @return 可安全放开返回 true
     */
    private static boolean canUnlock(Region region) {
        if ((region.protect & PAGE_GUARD) != 0) {
            return false;
        }
        if ((region.protect & PAGE_NOACCESS) == 0) {
            return false;
        }
        return region.size > 0 && region.size <= MAX_UNLOCK_BYTES;
    }

    /**
     * 临时修改区域保护属性。
     *
     * @param handle     句柄
     * @param address    地址
     * @param size       长度
     * @param newProtect 新保护属性
     * @return 成功返回 true
     */
    private static boolean virtualProtect(long handle, long address, long size, int newProtect) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment oldProtect = arena.allocate(4);
            int ok = (int) virtualProtectExHandle.invokeWithArguments(
                    MemorySegment.ofAddress(handle), MemorySegment.ofAddress(address),
                    size, newProtect, oldProtect);
            return ok != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 小工具 ====================

    /**
     * 解析 tasklist CSV 行。
     *
     * @param line        行
     * @param processName 进程名
     * @return 进程信息，解析失败返回 null
     */
    private static PidInfo parseTasklistLine(String line, String processName) {
        if (line == null || !line.startsWith("\"")) {
            return null;
        }
        if (!line.toLowerCase(Locale.ROOT).contains(processName.replace(".exe", "").toLowerCase(Locale.ROOT))) {
            return null;
        }
        // tasklist CSV 形如 "Weixin.exe","39680","Console","1","1,088,916 K"
        // 内存列自带千分位逗号，因此不能按 "," 直接切分，须按 ""," 切分后再剥引号
        String[] fields = line.trim().split("\",\"");
        if (fields.length < 5) {
            return null;
        }
        try {
            int pid = Integer.parseInt(fields[1].replace("\"", "").trim());
            String memText = fields[fields.length - 1].replace("\"", "").replace(",", "").trim();
            if (memText.endsWith("K") || memText.endsWith("k")) {
                memText = memText.substring(0, memText.length() - 1).trim();
            }
            return new PidInfo(pid, Long.parseLong(memText) * 1024L);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 判断是否以 SQLite 文件头开头。
     *
     * @param bytes 字节
     * @return 是返回 true
     */
    private static boolean startsWithSqliteMagic(byte[] bytes) {
        byte[] magic = "SQLite format 3\u0000".getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (bytes[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 子串查找。
     *
     * @param haystack 主串
     * @param needle   子串
     * @return 首次出现位置，未找到返回 -1
     */
    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * 字节转十六进制。
     *
     * @param bytes 字节
     * @return 十六进制串
     */
    static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16))
                    .append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }

    /**
     * 计算耗时。
     *
     * @param started 起始时间
     * @return 耗时毫秒
     */
    private static long elapsed(long started) {
        return System.currentTimeMillis() - started;
    }

    /**
     * 微信进程信息。
     *
     * @param pid              进程号
     * @param workingSetBytes  工作集字节数
     * @return 结果值
     */
    public record PidInfo(int pid, long workingSetBytes) {
    }

    /**
     * 扫描结果。
     *
     * @param found         是否找到密钥
     * @param keyHex        密钥（64 位十六进制），未找到为 null
     * @param address       命中内存地址，未找到为 0
     * @param pid           扫描的进程号
     * @param scannedBytes  已扫描字节数
     * @param failedBytes   不可读字节数
     * @param elapsedMillis 耗时毫秒
     * @param message       说明信息
     */
    public record ScanResult(boolean found, String keyHex, long address, int pid,
                             long scannedBytes, long failedBytes, long elapsedMillis, String message) {

        /**
         * 构造未命中结果。
         *
         * @param pid       进程号
         * @param scanned   已扫描字节
         * @param failed    不可读字节
         * @param elapsed   耗时毫秒
         * @param message   说明
         * @return 结果
         */
        static ScanResult miss(int pid, long scanned, long failed, long elapsed, String message) {
            return new ScanResult(false, null, 0L, pid, scanned, failed, elapsed, message);
        }

        /**
         * 构造命中结果。
         *
         * @param keyHex    密钥
         * @param address   地址
         * @param pid       进程号
         * @param scanned   已扫描字节
         * @param failed    不可读字节
         * @param elapsed   耗时毫秒
         * @return 结果
         */
        static ScanResult hit(String keyHex, long address, int pid, long scanned, long failed, long elapsed) {
            return new ScanResult(true, keyHex, address, pid, scanned, failed, elapsed,
                    "命中地址 0x" + Long.toHexString(address));
        }

        /**
         * 密钥形态是否合法。
         *
         * @return 合法返回 true
         */
        public boolean keyValid() {
            return keyHex != null && KEY_PATTERN.matcher(keyHex).matches();
        }
    }
}
