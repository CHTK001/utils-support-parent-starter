package com.chua.wechat.support.restore;

import com.chua.common.support.task.restore.DataRestore;
import com.chua.common.support.task.restore.DataRestoreConfig;
import com.chua.common.support.task.restore.DataRestoreResult;
import com.chua.common.support.task.restore.ExportFormat;
import com.chua.wechat.support.restore.jdbc.WechatJdbcExporter;
import com.chua.wechat.support.restore.keyscan.WechatMemoryKeyScanner;
import com.chua.wechat.support.restore.sqlcipher.SqlCipherDecryptor;

import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 微信数据还原命令行入口。
 *
 * <p>把 {@link WechatDataRestore}（SPI 还原编排）、{@link SqlCipherDecryptor}（SQLCipher 4 纯 JDK 直解）
 * 与 {@link WechatJdbcExporter}（CSV / SQL / Excel 导出）串成一条可直接执行的命令，
 * 无需写代码即可在本机还原微信聊天数据。</p>
 *
 * <h3>用法</h3>
 * <pre>{@code
 * java -cp <classpath> com.chua.wechat.support.restore.WechatRestoreCli [选项] <数据源>
 *
 * # 数据源可以是：微信账号目录（含 db_storage）、db_storage 目录，或单个 .db 文件
 *
 * # 1) 先看一眼有哪些库、各自是明文还是加密（不需要密钥）
 * WechatRestoreCli E:/微信/xwechat_files/wxid_xxx_yyyy --list
 *
 * # 2) 带密钥直解并导出 CSV
 * WechatRestoreCli E:/微信/xwechat_files/wxid_xxx_yyyy --key <64位hex> --out out/csv
 *
 * # 3) 导出 SQL（含建表语句）并保留解密后的明文库
 * WechatRestoreCli E:/微信/... --key-file key.txt --format sql --decrypt-dir out/plain
 *
 * # 4) 只还原 session.db，且只导出 SessionTable 的前 100 行
 * WechatRestoreCli E:/微信/.../session/session.db --key <hex> --whitelist SessionTable --limit 100
 * }</pre>
 *
 * <h3>退出码</h3>
 * <ul>
 *   <li>0 — 成功</li>
 *   <li>1 — 还原失败（密钥不匹配、无可用路径等）</li>
 *   <li>2 — 参数错误</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class WechatRestoreCli {

    /**
    * 帮助文本
    */
    private static final String USAGE = String.join(System.lineSeparator(),
            "微信数据还原命令行工具",
            "",
            "用法: WechatRestoreCli [选项] <数据源>",
            "",
            "数据源: 微信账号目录（含 db_storage）/ db_storage 目录 / 单个 .db 文件",
            "",
            "选项:",
            "  --data-dir <dir>       微信数据目录（等价于位置参数）",
            "  --key <hex>            SQLCipher 密钥：64 位 hex，或携带 salt 的 96 位 hex",
            "  --key-file <file>      密钥文件；缺省读取 <输出目录>/key.txt",
            "  --scan-key             内存扫描 Weixin.exe 取密钥（免重启微信，需管理员权限）",
            "  --scan-pid <pid>       指定要扫描的微信进程（缺省自动选工作集最大的）",
            "  --scan-max-mb <n>      内存扫描字节上限（MB），用于快速试跑；缺省不限",
            "  --auto-key             允许走原生库自动捕获密钥（需管理员并重启微信）",
            "  --out <dir>            输出目录，默认 ./wechat-restore-out",
            "  --format <fmt>         csv | sql | excel | json，默认 csv",
            "  --mode <mode>          auto | memory | native | sqlcipher | tool，默认 auto",
            "                         memory = 读微信进程内存里的 SQLCipher 明文页，不需要密钥",
            "  --decrypt-dir <dir>    保留解密后的明文数据库到此目录（默认用完即删）",
            "  --runtime-dir <dir>    native 模式的原生库目录",
            "  --tool-path <path>     tool 模式 Wechat-Export 脚本路径",
            "  --limit <n>            每张表最多导出行数",
            "  --whitelist <a,b>      仅导出这些表",
            "  --blacklist <a,b>      排除这些表",
            "  --skip-groups          跳过群聊相关表",
            "  --list                 仅列出数据库并探测加密形态，不做还原",
            "  --probe <hex>          仅用给定密钥探测各数据库是否可解",
            "  -h, --help             显示本帮助",
            "",
            "输出（memory 模式）:",
            "  report.html            自包含的聊天记录查看器（双击即可看，支持搜索与按发送者筛选）",
            "  messages.csv           可直接阅读的聊天记录（时间 / 发送者 / 正文）",
            "  id_map.csv             各库簇的 sender_id → 用户名映射，便于核对归属",
            "  wechat_memory.db       重建出的明文 SQLite 库，可用任意客户端查询",
            "  wechat_memory__*.csv   各表原始导出（含 schema.sql / summary.md）",
            "");

    /**
     * 构造方法，创建 WechatRestoreCli 实例。
     */
    private WechatRestoreCli() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
    * 命令行入口。
    *
    * @param args 参数
    */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
    * 可测试的执行入口。
    *
    * @param args 参数
    * @param out  标准输出
    * @param err  错误输出
    * @return 退出码
    */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("[参数错误] " + e.getMessage());
            err.println();
            err.println(USAGE);
            return 2;
        }
        if (options.help) {
            out.println(USAGE);
            return 0;
        }
        if (options.source == null) {
            err.println("[参数错误] 缺少数据源。请传入微信账号目录 / db_storage 目录 / 单个 .db 文件。");
            err.println();
            err.println(USAGE);
            return 2;
        }
        File source = new File(options.source);
        if (!source.exists()) {
            err.println("[参数错误] 数据源不存在: " + source.getAbsolutePath());
            return 2;
        }

        try {
            if (options.list || options.probeKey != null) {
                return listDatabases(source, options, out, err);
            }
            return restore(source, options, out, err);
        } catch (Exception e) {
            err.println("[还原失败] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return 1;
        }
    }

    // ==================== 探测 ====================

    /**
    * 列出数据源下的数据库并探测加密形态。
    *
    * @param source  数据源
    * @param options 选项
    * @param out     标准输出
    * @param err     错误输出
    * @return 退出码
    */
    private static int listDatabases(File source, Options options, PrintStream out, PrintStream err) {
        List<File> databases = collectDatabases(source);
        if (databases.isEmpty()) {
            err.println("[未找到数据库] " + source.getAbsolutePath());
            return 1;
        }
        byte[] probe = null;
        if (options.probeKey != null) {
            try {
                probe = SqlCipherDecryptor.parseHexKey(options.probeKey);
            } catch (IllegalArgumentException e) {
                err.println("[参数错误] --probe 密钥非法: " + e.getMessage());
                return 2;
            }
        }
        out.printf("%-46s %10s  %-10s %s%n", "数据库", "大小", "形态", "密钥");
        out.println("-".repeat(96));
        int plaintext = 0;
        int encrypted = 0;
        int unlocked = 0;
        for (File db : databases) {
            try {
                SqlCipherDecryptor.Detection detection = SqlCipherDecryptor.detect(db, probe);
                String state;
                String keyState;
                if (detection.plaintext()) {
                    state = "明文";
                    keyState = "无需密钥";
                    plaintext++;
                } else if (probe == null) {
                    state = "SQLCipher";
                    keyState = "未提供密钥";
                    encrypted++;
                } else if (detection.keyValid()) {
                    state = "SQLCipher";
                    keyState = "可解 (" + detection.profile().name() + ")";
                    encrypted++;
                    unlocked++;
                } else {
                    state = "SQLCipher";
                    keyState = "密钥不匹配";
                    encrypted++;
                }
                out.printf("%-46s %10s  %-10s %s%n", relative(source, db), humanSize(db.length()), state, keyState);
            } catch (Exception e) {
                out.printf("%-46s %10s  %-10s %s%n", relative(source, db), humanSize(db.length()), "探测失败",
                        e.getMessage());
            }
        }
        out.println("-".repeat(96));
        out.printf("合计 %d 个数据库：明文 %d，加密 %d%n", databases.size(), plaintext, encrypted);
        if (probe != null) {
            out.printf("给定密钥可解 %d 个%n", unlocked);
        } else {
            out.println("提示：加 --probe <64位hex> 可验证某个密钥能解开哪些库。");
        }
        return 0;
    }

    // ==================== 还原 ====================

    /**
    * 执行还原。
    *
    * @param source  数据源
    * @param options 选项
    * @param out     标准输出
    * @param err     错误输出
    * @return 退出码
    * @throws Exception 还原异常
    */
    private static int restore(File source, Options options, PrintStream out, PrintStream err) throws Exception {
        DataRestoreConfig config = buildConfig(options);
        out.println("数据源   : " + source.getAbsolutePath());
        out.println("模式     : " + options.mode);
        out.println("输出格式 : " + config.getFormat().value());
        out.println("输出目录 : " + config.getOutputDir().getAbsolutePath());
        if (options.decryptDir != null) {
            out.println("明文目录 : " + new File(options.decryptDir).getAbsolutePath());
        }
        out.println();

        // memory 模式扫的是「进程内存」而不是某个库文件，一次就能拿到全部库的数据，
        // 所以不需要（也不应该）先解析密钥；直接交给还原器处理整个数据源。
        if (!WechatDataRestore.MODE_MEMORY.equals(options.mode)) {
            String keyText = resolveKeyText(options, config.getOutputDir());
            if (keyText == null && options.scanKey) {
                keyText = acquireKeyByScan(source, options, out, err);
                if (keyText == null) {
                    return 1;
                }
            }
            if (keyText != null) {
                config.getOptions().put(WechatDataRestore.OPTION_KEY, keyText);
            } else {
                // 无密钥时，若数据源全是明文库则直接导出（微信 key_info.db 即属此类）
                List<File> databases = collectDatabases(source);
                boolean allPlaintext = !databases.isEmpty();
                for (File db : databases) {
                    if (!SqlCipherDecryptor.isPlainSqlite(db)) {
                        allPlaintext = false;
                        break;
                    }
                }
                if (allPlaintext) {
                    return exportPlainDatabases(databases, config, out, err);
                }
            }
        }

        // 数据源可以是文件或目录：WechatDataRestore 重写了 restore(File, DataRestoreConfig)
        // 为目录源另开了一条等价入口（基类的「源必须是文件」校验对微信天然不适用，
        // 它的数据分散在 db_storage 下的二十来个库里），这里直接交给标准接口即可。
        DataRestoreResult result = DataRestore.create("wechat", config).restore(source);
        if (!result.isSuccess()) {
            err.println("[还原失败] " + result.getErrorMessage());
            return 1;
        }
        out.printf("还原成功：%d 个文件 / %s，耗时 %d ms%n",
                result.getFileCount(), humanSize(result.getTotalSize()), result.getDurationMillis());
        for (File file : result.getOutputFiles()) {
            out.printf("  %-58s %10s%n", file.getName(), humanSize(file.length()));
        }
        return 0;
    }

    /**
    * 明文数据库直导：无需密钥，逐个用 JDBC 导出器输出 CSV / SQL / Excel。
    *
    * <p>微信的 {@code key_info.db} 等库本身不加密，这条路径让「只拿到明文库」的场景
    * 也能一键导出，而不必伪造一个密钥走 SQLCipher 分支。</p>
    *
    * @param databases 明文数据库
    * @param config    还原配置
    * @param out       标准输出
    * @param err       错误输出
    * @return 退出码
    * @throws Exception 导出异常
    */
    private static int exportPlainDatabases(List<File> databases, DataRestoreConfig config,
                                            PrintStream out, PrintStream err) throws Exception {
        out.println("形态     : 全部为明文 SQLite（无需密钥）");
        out.println();
        // 先复制到临时目录再导出：微信运行时会持续持有 -wal / -shm，
        // 直接对实时库建连可能触发 SQLITE_IOERR_TRUNCATE（本机实测复现）。
        File staging = Files.createTempDirectory("wechat-plain-").toFile();
        List<File> outputs = new ArrayList<>(64);
        List<String> failures = new ArrayList<>(8);
        try {
            for (File db : databases) {
                File copy = new File(staging, db.getName());
                Files.copy(db.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
                copySidecar(db, copy, "-wal");
                copySidecar(db, copy, "-shm");
                DataRestoreResult result = WechatJdbcExporter.export(copy, config, config.getOutputDir());
                if (result.isSuccess()) {
                    outputs.addAll(result.getOutputFiles());
                } else {
                    failures.add(db.getName() + ": " + result.getErrorMessage());
                }
            }
        } finally {
            deleteRecursively(staging);
        }
        if (outputs.isEmpty()) {
            err.println("[还原失败] " + String.join("; ", failures));
            return 1;
        }
        long totalSize = outputs.stream().mapToLong(File::length).sum();
        out.printf("还原成功：%d 个文件，共 %s%n", outputs.size(), humanSize(totalSize));
        for (File file : outputs) {
            out.printf("  %-58s %10s%n", file.getName(), humanSize(file.length()));
        }
        if (!failures.isEmpty()) {
            out.println("部分库跳过: " + String.join("; ", failures));
        }
        return 0;
    }

    /**
    * 复制 SQLite 附属文件（{@code -wal} / {@code -shm}），不存在时忽略。
    *
    * @param source 源数据库
    * @param copy   副本数据库
    * @param suffix 附属文件后缀
    */
    private static void copySidecar(File source, File copy, String suffix) {
        File sidecar = new File(source.getParentFile(), source.getName() + suffix);
        if (!sidecar.isFile()) {
            return;
        }
        try {
            Files.copy(sidecar.toPath(), new File(copy.getParentFile(), copy.getName() + suffix).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            // 附属文件复制失败不影响主库导出
        }
    }

    /**
    * 递归删除临时目录。
    *
    * @param dir 目录
    */
    private static void deleteRecursively(File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!dir.delete()) {
            dir.deleteOnExit();
        }
    }

    /**
    * 通过内存扫描获取密钥（免重启微信）。
    *
    * <p>先跑扫描器自检——只有自检通过，"未命中" 结论才可信；再挑一个加密库作扫描目标，
    * 逐个微信进程扫描直到命中。</p>
    *
    * @param source  数据源
    * @param options 选项
    * @param out     标准输出
    * @param err     错误输出
    * @return 64 位十六进制密钥，未获取到返回 null
    */
    private static String acquireKeyByScan(File source, Options options, PrintStream out, PrintStream err) {
        out.println("密钥获取 : 扫描微信进程内存（免重启）");
        if (!WechatMemoryKeyScanner.isSupported()) {
            err.println("[密钥获取失败] 内存扫描仅支持 Windows 平台");
            return null;
        }
        if (!WechatMemoryKeyScanner.selfTest()) {
            err.println("[密钥获取失败] 扫描器自检未通过，此时 \"未命中\" 结论不可信，已中止");
            return null;
        }
        List<File> databases = collectDatabases(source);
        File target = null;
        for (File db : databases) {
            if ("session.db".equalsIgnoreCase(db.getName())) {
                target = db;
                break;
            }
        }
        if (target == null) {
            for (File db : databases) {
                if (!SqlCipherDecryptor.isPlainSqlite(db)) {
                    target = db;
                    break;
                }
            }
        }
        if (target == null) {
            err.println("[密钥获取失败] 数据源下没有加密数据库可作扫描目标");
            return null;
        }
        out.println("扫描目标 : " + target.getName());
        for (WechatMemoryKeyScanner.PidInfo info : WechatMemoryKeyScanner.weixinProcesses()) {
            out.printf("  微信进程 %-8d 工作集 %s%n", info.pid(), humanSize(info.workingSetBytes()));
        }
        long maxBytes = options.scanMaxMb != null && options.scanMaxMb > 0
                ? options.scanMaxMb * 1048576L
                : Long.MAX_VALUE;
        WechatMemoryKeyScanner.ScanResult result = options.scanPid != null
                ? WechatMemoryKeyScanner.scan(target, options.scanPid, 1, maxBytes)
                : WechatMemoryKeyScanner.scan(target);
        out.printf("扫描完成 : 已扫 %s / 不可读 %s / 耗时 %d ms%n",
                humanSize(result.scannedBytes()), humanSize(result.failedBytes()), result.elapsedMillis());
        if (result.found()) {
            out.println("密钥命中 : " + result.keyHex());
            out.println("命中地址 : 0x" + Long.toHexString(result.address()));
            out.println();
            return result.keyHex();
        }
        err.println("[密钥获取失败] " + result.message());
        err.println("  提示：内存扫描不保证成功——密钥可能由服务端包裹，或不在可读内存中。");
        err.println("        可改用 --key <64位hex> 或 --key-file <file> 直接提供密钥。");
        return null;
    }

    /**
    * 解析可用密钥文本（显式 {@code --key} 优先，其次密钥文件，再次 {@code <输出目录>/key.txt}）。
    *
    * @param options   选项
    * @param outputDir 输出目录
    * @return 64 位十六进制密钥，未解析到返回 null
    */
    private static String resolveKeyText(Options options, File outputDir) {
        if (options.key != null && options.key.trim().matches("^[0-9a-fA-F]{64}$")) {
            return options.key.trim();
        }
        File keyFile = options.keyFile != null ? new File(options.keyFile) : new File(outputDir, "key.txt");
        return readKeyFile(keyFile);
    }

    /**
    * 由命令行选项构建还原配置。
    *
    * @param options 选项
    * @return 还原配置
    */
    private static DataRestoreConfig buildConfig(Options options) {
        File outputDir = new File(options.out == null ? "wechat-restore-out" : options.out);
        Map<String, Object> ext = new HashMap<>(16);
        ext.put(WechatDataRestore.OPTION_MODE, options.mode);
        if (options.dataDir != null) {
            ext.put(WechatDataRestore.OPTION_DATA_DIR, options.dataDir);
        }
        if (options.key != null) {
            ext.put(WechatDataRestore.OPTION_KEY, options.key);
        }
        if (options.keyFile != null) {
            ext.put(WechatDataRestore.OPTION_KEY_FILE, options.keyFile);
        }
        if (options.autoKey) {
            ext.put(WechatDataRestore.OPTION_AUTO_KEY, Boolean.TRUE);
        }
        if (options.decryptDir != null) {
            ext.put(WechatDataRestore.OPTION_DECRYPT_DIR, options.decryptDir);
        }
        if (options.runtimeDir != null) {
            ext.put(WechatDataRestore.OPTION_RUNTIME_DIR, options.runtimeDir);
        }
        if (options.toolPath != null) {
            ext.put(WechatDataRestore.OPTION_TOOL_PATH, options.toolPath);
        }
        if (options.limit != null) {
            ext.put(WechatDataRestore.OPTION_LIMIT, options.limit);
        }
        if (options.whitelist != null) {
            ext.put(WechatDataRestore.OPTION_WHITELIST, options.whitelist);
        }
        if (options.blacklist != null) {
            ext.put(WechatDataRestore.OPTION_BLACKLIST, options.blacklist);
        }
        if (options.skipGroups) {
            ext.put(WechatDataRestore.OPTION_SKIP_GROUPS, Boolean.TRUE);
        }
        // 表级过滤由导出器消费（WechatDataRestore 侧的同名选项仅透传给 tool 路径）
        ext.put(WechatJdbcExporter.OPTION_TABLE_WHITELIST, options.whitelist);
        ext.put(WechatJdbcExporter.OPTION_TABLE_BLACKLIST, options.blacklist);

        return DataRestoreConfig.builder()
                .format(ExportFormat.of(options.format))
                .outputDir(outputDir)
                .options(ext)
                .build();
    }

    // ==================== 工具方法 ====================

    /**
    * 递归收集数据库文件（排除 {@code -wal} / {@code -shm}、临时文件与还原器自己的产物目录）。
    *
    * <p>跳过 {@code wechat-restore-out}：缺省输出目录就在数据源内部，
    * 里面的 {@code decrypted} 子目录放着上一次解出的明文库，不排除就会被当成数据源重复导出。</p>
    *
    * @param source 数据源（文件或目录）
    * @return 按体积升序排列的数据库文件
    */
    static List<File> collectDatabases(File source) {
        List<File> result = new ArrayList<>(32);
        if (source.isFile()) {
            if (isDatabase(source)) {
                result.add(source);
            }
            return result;
        }
        File[] children = source.listFiles();
        if (children == null) {
            return result;
        }
        Arrays.sort(children);
        for (File child : children) {
            if (child.isDirectory()) {
                if (WechatDataRestore.DIRECTORY_OUTPUT_NAME.equals(child.getName())) {
                    continue;
                }
                result.addAll(collectDatabases(child));
            } else if (isDatabase(child)) {
                result.add(child);
            }
        }
        result.sort((a, b) -> Long.compare(a.length(), b.length()));
        return result;
    }

    /**
    * 判断是否微信数据库文件。
    *
    * @param file 文件
    * @return 是数据库返回 true
    */
    private static boolean isDatabase(File file) {
        if (!file.isFile()) {
            return false;
        }
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".db")) {
            return false;
        }
        return !name.endsWith("-wal") && !name.endsWith("-shm");
    }

    /**
    * 计算相对路径用于展示。
    *
    * @param root 根
    * @param file 文件
    * @return 相对路径，失败时返回绝对路径
    */
    private static String relative(File root, File file) {
        try {
            File base = root.isDirectory() ? root : root.getParentFile();
            if (base == null) {
                return file.getAbsolutePath();
            }
            return base.toPath().toAbsolutePath().relativize(file.toPath().toAbsolutePath()).toString();
        } catch (Exception e) {
            return file.getAbsolutePath();
        }
    }

    /**
    * 人类可读的体积。
    *
    * @param bytes 字节数
    * @return 形如 {@code 1.2 MB}
    */
    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int index = -1;
        while (value >= 1024 && index < units.length - 1) {
            value /= 1024;
            index++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[index]);
    }

    /**
    * 读取密钥文件（兼容整行 hex 与 {@code key=...} 形式）。
    *
    * @param file 密钥文件
    * @return 密钥文本，读取失败返回 null
    */
    static String readKeyFile(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String text = line.trim();
                if (text.isEmpty() || text.startsWith("#")) {
                    continue;
                }
                int eq = text.indexOf('=');
                if (eq > 0) {
                    text = text.substring(eq + 1).trim();
                }
                if (text.matches("^[0-9a-fA-F]{64}$")) {
                    return text;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /**
    * 命令行选项。
    */
    static final class Options {
        String source;
        String dataDir;
        String key;
        String keyFile;
        boolean autoKey;
        boolean scanKey;
        Integer scanPid;
        Long scanMaxMb;
        String out;
        String format = "csv";
        String mode = WechatDataRestore.MODE_AUTO;
        String decryptDir;
        String runtimeDir;
        String toolPath;
        Integer limit;
        String whitelist;
        String blacklist;
        boolean skipGroups;
        boolean list;
        String probeKey;
        boolean help;

        /**
        * 解析命令行参数。
        *
        * @param args 参数
        * @return 选项对象
        */
        static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "-h":
                    case "--help":
                        options.help = true;
                        break;
                    case "--list":
                        options.list = true;
                        break;
                    case "--skip-groups":
                        options.skipGroups = true;
                        break;
                    case "--auto-key":
                        options.autoKey = true;
                        break;
                    case "--scan-key":
                        options.scanKey = true;
                        break;
                    case "--scan-pid":
                        String scanPid = value(args, ++i, arg);
                        try {
                            options.scanPid = Integer.valueOf(scanPid);
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("--scan-pid 必须是整数: " + scanPid);
                        }
                        break;
                    case "--scan-max-mb":
                        String scanMaxMb = value(args, ++i, arg);
                        try {
                            options.scanMaxMb = Long.valueOf(scanMaxMb);
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("--scan-max-mb 必须是整数: " + scanMaxMb);
                        }
                        break;
                    case "--data-dir":
                        options.dataDir = value(args, ++i, arg);
                        break;
                    case "--key":
                        options.key = value(args, ++i, arg);
                        break;
                    case "--key-file":
                        options.keyFile = value(args, ++i, arg);
                        break;
                    case "--out":
                        options.out = value(args, ++i, arg);
                        break;
                    case "--format":
                        options.format = value(args, ++i, arg);
                        break;
                    case "--mode":
                        options.mode = value(args, ++i, arg);
                        break;
                    case "--decrypt-dir":
                        options.decryptDir = value(args, ++i, arg);
                        break;
                    case "--runtime-dir":
                        options.runtimeDir = value(args, ++i, arg);
                        break;
                    case "--tool-path":
                        options.toolPath = value(args, ++i, arg);
                        break;
                    case "--limit":
                        String limit = value(args, ++i, arg);
                        try {
                            options.limit = Integer.valueOf(limit);
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("--limit 必须是整数: " + limit);
                        }
                        break;
                    case "--whitelist":
                        options.whitelist = value(args, ++i, arg);
                        break;
                    case "--blacklist":
                        options.blacklist = value(args, ++i, arg);
                        break;
                    case "--probe":
                        options.probeKey = value(args, ++i, arg);
                        break;
                    default:
                        if (arg.startsWith("--")) {
                            throw new IllegalArgumentException("未知选项: " + arg);
                        }
                        if (options.source != null) {
                            throw new IllegalArgumentException("只能指定一个数据源，已指定: " + options.source);
                        }
                        options.source = arg;
                        break;
                }
            }
            if (options.source == null && options.dataDir != null) {
                options.source = options.dataDir;
            }
            if (options.key == null && options.keyFile != null) {
                options.key = readKeyFile(new File(options.keyFile));
            }
            if (options.key == null && options.keyFile == null && options.out != null) {
                options.key = readKeyFile(new File(options.out, "key.txt"));
            }
            return options;
        }

        /**
        * 取下一个参数值。
        *
        * @param args 参数数组
        * @param index 下标
        * @param name 选项名
        * @return 值
        */
        private static String value(String[] args, int index, String name) {
            if (index >= args.length) {
                throw new IllegalArgumentException("选项缺少取值: " + name);
            }
            return args[index];
        }
    }
}
