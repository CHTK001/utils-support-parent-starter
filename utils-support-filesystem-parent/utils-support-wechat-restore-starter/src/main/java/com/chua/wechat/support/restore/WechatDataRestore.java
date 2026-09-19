package com.chua.wechat.support.restore;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.task.restore.AbstractDataRestore;
import com.chua.common.support.task.restore.DataRestoreConfig;
import com.chua.common.support.task.restore.DataRestoreResult;
import com.chua.common.support.task.restore.ExportFormat;
import com.chua.wechat.support.restore.jdbc.WechatJdbcExporter;
import com.chua.wechat.support.restore.keyscan.WechatMemoryKeyScanner;
import com.chua.wechat.support.restore.memory.WechatMemoryAccess;
import com.chua.wechat.support.restore.memory.WechatMemoryAccumulator;
import com.chua.wechat.support.restore.memory.WechatMemoryExtractor;
import com.chua.wechat.support.restore.memory.WechatMemoryMessages;
import com.chua.wechat.support.restore.memory.WechatMemoryRebuilder;
import com.chua.wechat.support.restore.nativebridge.WcdbNativeBridge;
import com.chua.wechat.support.restore.nativebridge.WechatNativeExporter;
import com.chua.wechat.support.restore.nativebridge.WxKeyNativeBridge;
import com.chua.wechat.support.restore.sqlcipher.SqlCipherDecryptor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 本机微信数据还原器。
 *
 * <p>将本机微信（Windows 4.x 版，xwechat_files）聊天数据库还原为可读数据文件
 * （CSV / SQL / Excel），通过 SPI 名称 {@code wechat} / {@code wechat-export} 注册。</p>
 *
 * <h3>四条执行路径（{@code auto} 模式按此顺序依次降级）</h3>
 * <ol>
 *   <li><b>native（零外部运行时）</b>— Java 25 FFM 直接绑定微信自带的
 *       WCDB.dll / wcdb_api.dll / wx_key.dll，无需 Python、Node.js 或 Electron。
 *       注意微信 4.1.13.x 已把 WCDB 静态链接进 {@code Weixin.dll}，安装目录下没有
 *       {@code wcdb_api.dll}，该路径在这些版本上不可用；</li>
 *   <li><b>sqlcipher（需要数据库密钥）</b>— 用密钥把加密库直接解成明文 SQLite，
 *       能拿到全量数据，不受「只覆盖已缓存页」的限制；配了 {@code key} 才会走这条；</li>
 *   <li><b>memory（内存明文页，不需要密钥）</b>— 微信运行时 SQLCipher 会把解密后的明文页
 *       留在 SQLite 的 pager cache 里，直接读微信进程内存即可拿到聊天记录。
 *       不需要数据库密钥、不需要重启微信、不需要注入。
 *       代价是<b>只能看到微信已经缓存过的页</b>。本机微信 4.1.13.x 实测走通的路径。
 *       产出除各表 CSV 外，还有可直接阅读的 {@code messages.csv}（时间 / 发送者 / 正文）；</li>
 *   <li><b>tool（兜底）</b>— 编排
 *       <a href="https://github.com/minglin2012/Wechat-Export">Wechat-Export</a>
 *       的 Python 工具链，适用于 WCDB 宿主环境校验拒绝 JVM 初始化（错误码 -1006）的场景。</li>
 * </ol>
 *
 * <h3>配置项（{@link DataRestoreConfig#getOptions()}）</h3>
 * <ul>
 *   <li>{@code mode} — {@code auto}（默认，native → memory → tool 依次降级）/
 *       {@code native} / {@code memory} / {@code tool}</li>
 *   <li>{@code decode.blob} — 内存路径是否解压 zstd 压缩的消息体，默认 true</li>
 *   <li>{@code runtime.dir} — native 路径必需，原生库目录（含 WCDB.dll、SDL2.dll、wcdb_api.dll、wx_key.dll，
 *       即 Wechat-Export 的 runtime 目录）；tool.path 已配置时按相邻目录自动推导</li>
 *   <li>{@code key} — 64 位十六进制数据库密钥（可选）</li>
 *   <li>{@code key.file} — 密钥文件路径，缺省为输出目录下的 key.txt</li>
 *   <li>{@code auto.key} — 密钥缺失时自动引导重启微信并通过 FFM 捕获（需管理员权限），默认 false</li>
 *   <li>{@code tool.path} — tool 路径的 Wechat-Export export.py 脚本路径（或工具根目录）</li>
 *   <li>{@code data.dir} — 微信数据目录（xwechat_files/wxid_xxx），缺省取源目录本身
 *       （源是文件时取其所在目录）</li>
 *   <li>{@code limit} — 每个会话最多导出条数，0 表示全部</li>
 *   <li>{@code whitelist} / {@code blacklist} — 会话标识/显示名白名单、黑名单（逗号分隔）</li>
 *   <li>{@code skip.groups} — native 路径是否跳过群聊，默认 false</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 *
 * <pre>{@code
 * // 一键还原：源是目录（账号目录 / db_storage / xwechat_files 都行），
 * // 零配置即可跑 —— auto 模式会依次尝试 native → sqlcipher → memory → tool，
 * // 本机拿不到密钥时自动落到「内存明文页」路径，不需要密钥、不需要重启微信。
 * DataRestore.create("wechat").restore(new File("E:/微信/xwechat_files"));
 *
 * // 指定输出格式与目录（并显式走内存明文页路径）
 * Map<String, Object> options = new HashMap<>(2);
 * options.put("mode", "memory");
 * DataRestoreConfig config = DataRestoreConfig.builder()
 *         .format(ExportFormat.EXCEL)
 *         .outputDir(new File("out"))
 *         .options(options)
 *         .build();
 * DataRestoreResult result = DataRestore.create("wechat", config)
 *         .restore(new File("E:/微信/xwechat_files"));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"wechat", "wechat-export"})
public class WechatDataRestore extends AbstractDataRestore {

    /**
     * 微信还原器 SPI 类型名
     */
    private static final String SPI_TYPE = "wechat";

    /**
     * 执行模式：自动（native 优先，失败回退 tool）
     */
    public static final String MODE_AUTO = "auto";

    /**
     * 执行模式：仅 FFM 原生路径
     */
    public static final String MODE_NATIVE = "native";

    /**
     * 执行模式：仅 SQLCipher 直解路径（需要数据库密钥）
     */
    public static final String MODE_SQLCIPHER = "sqlcipher";

    /**
     * 执行模式：仅 Python 工具编排路径
     */
    public static final String MODE_TOOL = "tool";

    /**
     * 执行模式：内存明文页提取路径（不需要数据库密钥）
     */
    public static final String MODE_MEMORY = "memory";

    /**
     * options 键：执行模式
     */
    public static final String OPTION_MODE = "mode";

    /**
     * options 键：原生库目录
     */
    public static final String OPTION_RUNTIME_DIR = "runtime.dir";

    /**
     * options 键：数据库密钥
     */
    public static final String OPTION_KEY = "key";

    /**
     * options 键：密钥文件路径
     */
    public static final String OPTION_KEY_FILE = "key.file";

    /**
     * options 键：是否自动捕获密钥
     */
    public static final String OPTION_AUTO_KEY = "auto.key";

    /**
     * options 键：Wechat-Export 的 export.py 脚本路径
     */
    public static final String OPTION_TOOL_PATH = "tool.path";

    /**
     * options 键：微信数据目录
     */
    public static final String OPTION_DATA_DIR = "data.dir";

    /**
     * options 键：SQLCipher 解密后的明文库保留目录
     */
    public static final String OPTION_DECRYPT_DIR = "decrypt.dir";

    /**
     * options 键：每个会话最多导出条数
     */
    public static final String OPTION_LIMIT = "limit";

    /**
     * options 键：会话白名单
     */
    public static final String OPTION_WHITELIST = "whitelist";

    /**
     * options 键：会话黑名单
     */
    public static final String OPTION_BLACKLIST = "blacklist";

    /**
     * options 键：是否跳过群聊
     */
    public static final String OPTION_SKIP_GROUPS = "skip.groups";

    /**
     * options 键：内存路径是否解压 zstd 压缩的消息体，默认 true
     */
    public static final String OPTION_DECODE_BLOB = "decode.blob";

    /**
     * options 键：auto 模式是否允许降级到内存明文页路径，默认 true
     */
    public static final String OPTION_MEMORY_ENABLED = "memory.enabled";

    /**
     * options 键：内存路径是否把本次扫描结果合并进累积文件（默认 true）。
     *
     * <p>内存路线只能看到微信当前缓存过的页，反复扫描 + 累积才能逼近全量；
     * 设为 false 则每次都是干净的单次快照。</p>
     */
    public static final String OPTION_MEMORY_ACCUMULATE = "memory.accumulate";

    /**
     * 内存路径重建出的明文库文件名
     */
    private static final String MEMORY_DB_NAME = "wechat_memory.db";

    /**
     * 目录源缺省输出目录名（{@code <数据源目录>/wechat-restore-out}）。
     *
     * <p>该目录位于数据源内部，收集数据库文件时必须排除，否则会把上一次的产物当数据源。</p>
     */
    public static final String DIRECTORY_OUTPUT_NAME = "wechat-restore-out";

    /**
     * 递归查找数据库文件的最大深度
     */
    private static final int MAX_SCAN_DEPTH = 8;

    /**
     * 合法密钥文件名
     */
    private static final String KEY_FILE_NAME = "key.txt";

    /**
     * 原生库核心文件名（完整性校验用）
     */
    private static final String NATIVE_API_DLL = "wcdb_api.dll";

    /**
     * 64 位十六进制密钥校验正则
     */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");

    /**
     * 使用默认配置创建。
     */
    public WechatDataRestore() {
        super(SPI_TYPE);
    }

    /**
     * 使用指定配置创建。
     *
     * @param config 还原配置
     */
    public WechatDataRestore(DataRestoreConfig config) {
        super(SPI_TYPE, config);
    }

    @Override
    protected DataRestoreResult doRestore(File source, DataRestoreConfig config) throws Exception {
        String mode = String.valueOf(config.getOptions().getOrDefault(OPTION_MODE, MODE_AUTO)).trim();
        switch (mode) {
            case MODE_NATIVE:
                return runNative(source, config);
            case MODE_MEMORY:
                return runMemory(source, config);
            case MODE_SQLCIPHER:
                return runSqlCipher(source, config);
            case MODE_TOOL:
                return WechatToolExporter.export(source, config);
            case MODE_AUTO:
            default:
                return runAuto(source, config, mode);
        }
    }

    /**
     * 还原数据源（<b>文件或目录</b>）。
     *
     * <p>与其它还原器不同，微信的数据源<b>天然是目录</b> —— 账号目录、
     * {@code db_storage}、甚至 {@code xwechat_files} 根目录都可以直接传进来，
     * 由还原器自己去里面找库。而 {@link AbstractDataRestore} 的 SPI 契约是
     * 「源必须是单个文件」（会以「还原源文件不存在或不是文件」直接拒绝目录），
     * 所以这里为目录源另开一条等价入口。</p>
     *
     * <p>目录源相比文件源更贴近真实用法：微信的数据分散在 {@code db_storage} 下的
     * 二十来个库里，逐个传文件既繁琐又容易漏。传目录时按 {@code mode} 一次性处理整个数据源：</p>
     * <ul>
     *   <li>{@code memory} —— 扫进程内存，与数据源内容无关，一次拿全部库；</li>
     *   <li>{@code sqlcipher} —— 递归找出目录下全部库，逐个解密导出；</li>
     *   <li>{@code native} / {@code tool} —— 递归定位 {@code session.db}；</li>
     *   <li>{@code auto}（默认）—— 按 native → sqlcipher → memory → tool 依次降级。</li>
     * </ul>
     *
     * <p>一键还原：</p>
     * <pre>{@code
     * // 源是目录，输出目录缺省为 <源目录>/wechat-restore-out
     * DataRestore.create("wechat").restore(new File("E:/微信/xwechat_files"));
     * }</pre>
     *
     * @param source 数据源文件或目录
     * @param config 还原配置
     * @return 还原结果
     * @throws Exception 还原异常
     */
    @Override
    public DataRestoreResult restore(File source, DataRestoreConfig config) throws Exception {
        if (source == null || !source.isDirectory()) {
            return super.restore(source, config);
        }
        DataRestoreConfig effective = withDirectoryDefaults(source, config);
        File outputDir = effective.getOutputDir();
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            return DataRestoreResult.failure("创建输出目录失败: " + outputDir.getAbsolutePath());
        }
        long started = System.currentTimeMillis();
        try {
            DataRestoreResult result = doRestore(source, effective);
            if (result == null) {
                return DataRestoreResult.failure("还原实现返回空结果: " + type());
            }
            result.setDurationMillis(System.currentTimeMillis() - started);
            return result;
        } catch (Exception e) {
            log.error("数据还原失败, 类型: {}, 源目录: {}", type(), source.getAbsolutePath(), e);
            DataRestoreResult result = DataRestoreResult.failure(e.getMessage());
            result.setDurationMillis(System.currentTimeMillis() - started);
            return result;
        }
    }

    /**
     * 为目录源补齐缺省配置。
     *
     * <p>两件事：</p>
     * <ol>
     *   <li><b>输出目录</b>缺省为 {@code <源目录>/wechat-restore-out}。
     *       不能沿用基类的「源文件所在目录」—— 目录源的父目录不是它的输出位置，
     *       落到数据目录外面反而不好找；</li>
     *   <li><b>{@code data.dir}</b> 缺省为源目录本身。
     *       native / tool 路径靠它定位 {@code session.db}，不补上就会退化成
     *       「源目录的父目录」而找错地方。</li>
     * </ol>
     *
     * <p>包级可见，便于单测直接校验缺省值。</p>
     *
     * @param source 源目录
     * @param config 原始配置（可为 null）
     * @return 补齐后的配置
     */
    DataRestoreConfig withDirectoryDefaults(File source, DataRestoreConfig config) {
        DataRestoreConfig base = config != null ? config : DataRestoreConfig.builder().build();
        Map<String, Object> options = new HashMap<>(base.getOptions());
        if (!options.containsKey(OPTION_DATA_DIR)) {
            options.put(OPTION_DATA_DIR, source.getAbsolutePath());
        }
        ExportFormat format = base.getFormat() != null ? base.getFormat() : ExportFormat.CSV;
        File outputDir = base.getOutputDir() != null
                ? base.getOutputDir()
                : new File(source, DIRECTORY_OUTPUT_NAME);
        log.info("微信数据源为目录，输出目录: {}", outputDir.getAbsolutePath());
        return DataRestoreConfig.builder()
                .format(format)
                .outputDir(outputDir)
                .targetSchema(base.getTargetSchema())
                .targetTable(base.getTargetTable())
                .includeStructure(base.isIncludeStructure())
                .charset(base.getCharset())
                .options(options)
                .build();
    }

    /**
     * 自动模式：原生路径优先，其次内存明文页路径，最后回退 Python 工具路径。
     *
     * <p>三条路径的适用性差异很大，这里按「对本机环境的依赖从低到高」排序：</p>
     * <ul>
     *   <li><b>native</b> 需要微信安装目录带 {@code wcdb_api.dll}。微信 4.1.13.x 已把
     *       WCDB 静态链接进 {@code Weixin.dll}，该路径在这些版本上不可用；</li>
     *   <li><b>memory</b> 只需要微信正在运行（且本进程有读其它进程内存的权限），
     *       不需要密钥 —— 拿不到密钥时的主路径；</li>
     *   <li><b>tool</b> 需要额外安装 Wechat-Export 的 Python 工具链。</li>
     * </ul>
     *
     * @param source 源文件
     * @param config 还原配置
     * @param mode   原始模式值（异常提示用）
     * @return 还原结果
     * @throws Exception 三条路径均不可用时抛出
     */
    private DataRestoreResult runAuto(File source, DataRestoreConfig config, String mode) throws Exception {
        if (!MODE_AUTO.equals(mode)) {
            log.warn("未知微信还原模式 '{}'，按 auto 处理", mode);
        }
        File runtimeDir = resolveRuntimeDir(config, false);
        boolean toolConfigured = hasToolPath(config);
        if (WcdbNativeBridge.isSupported() && runtimeDir != null) {
            try {
                return runNative(source, config, runtimeDir);
            } catch (Exception e) {
                log.warn("FFM 原生路径失败，尝试其它路径: {}", e.getMessage());
            }
        }
        // 配了密钥就走 SQLCipher 直解：它能拿到全量数据，不受「只覆盖已缓存页」的限制，
        // 所以优先级高于内存路径
        String keyHex = resolveKeyHex(config);
        if (keyHex != null) {
            DataRestoreResult result = runSqlCipher(source, config);
            if (result.isSuccess()) {
                return result;
            }
            log.warn("SQLCipher 直解失败: {}", result.getErrorMessage());
            if (!toolConfigured && !isMemoryEnabled(config)) {
                // 没有任何兜底路径时，必须抛出而不是静默返回失败结果
                throw new IllegalStateException(result.getErrorMessage());
            }
        }
        if (isMemoryEnabled(config) && WechatMemoryAccess.isSupported()) {
            try {
                return runMemory(source, config);
            } catch (Exception e) {
                log.warn("内存明文页路径失败: {}", e.getMessage());
                if (!toolConfigured) {
                    throw e;
                }
            }
        }
        if (toolConfigured) {
            return WechatToolExporter.export(source, config);
        }
        throw new IllegalArgumentException("微信还原缺少可用运行环境：请设置 options['" + OPTION_RUNTIME_DIR
                + "']（FFM 原生库目录）、确保微信正在运行（内存明文页路径），"
                + "或设置 options['" + OPTION_TOOL_PATH + "']（Wechat-Export 脚本路径）");
    }

    /**
     * 执行内存明文页提取路径。
     *
     * <p>微信运行时，SQLCipher 会把解密后的明文页留在 SQLite 的 pager cache 里，
     * 因此<b>不需要数据库密钥</b>即可读出聊天记录。代价是只能看到微信<b>已经缓存过</b>的页 ——
     * 要让更多记录进入缓存，需在微信里打开/滚动对应聊天后重新扫描。</p>
     *
     * <p>注意本路径会枚举<b>全部</b> {@code Weixin.exe} 进程：微信是多进程架构，
     * 消息数据可能落在任意一个进程里，且与内存大小无关（实测 700MB 的进程只有 54 条消息，
     * 366MB 的进程有 398 条）。</p>
     *
     * @param source 源文件
     * @param config 还原配置
     * @return 还原结果
     * @throws Exception 提取失败
     */
    private DataRestoreResult runMemory(File source, DataRestoreConfig config) throws Exception {
        if (!WechatMemoryAccess.isSupported()) {
            throw new IllegalStateException("内存明文页提取仅支持 Windows 平台，请改用 mode=" + MODE_TOOL);
        }
        File outputDir = config.getOutputDir() != null ? config.getOutputDir() : source.getParentFile();
        if (outputDir == null) {
            throw new IllegalArgumentException("无法确定输出目录，请通过 config.outputDir 指定");
        }
        boolean decodeBlob = !Boolean.FALSE.toString().equalsIgnoreCase(
                String.valueOf(config.getOptions().getOrDefault(OPTION_DECODE_BLOB, Boolean.TRUE)));

        // 跨次扫描累积 + 逐进程落盘检查点。
        //
        // 内存路线只能看到「微信当前缓存过的页」，而 pager cache 会被换出，
        // 所以默认把每次扫描的结果合并进输出目录里的累积文件，反复扫就越来越全。
        //
        // 另一个必须逐进程落盘的原因：内存扫描会被外部随机杀掉（本机实测 8 次里 3 次 ——
        // 进程直接消失，没有异常、没有 hs_err、连导出后的最后一行日志都可能丢）。
        // 一次扫完所有进程再统一落盘的话，被杀就全丢；每扫完一个进程落盘一次，
        // 被打断最多损失当前这一个进程。
        File store = new File(outputDir, WechatMemoryAccumulator.FILE_NAME);
        List<WechatMemoryKeyScanner.PidInfo> processes = WechatMemoryExtractor.listProcesses();
        List<Integer> pids = new ArrayList<>(processes.size());
        for (WechatMemoryKeyScanner.PidInfo info : processes) {
            pids.add(info.pid());
        }

        WechatMemoryAccumulator accumulator =
                WechatMemoryAccumulator.open(store, isAccumulateEnabled(config));
        WechatMemoryExtractor.ExtractResult[] current = new WechatMemoryExtractor.ExtractResult[1];
        // 逐进程回调拿到的都是「到目前为止」的完整视图，快照里的增量只反映最后一次 add，
        // 所以这里自己把各次增量累加，日志才是本次运行真正的新增量
        int[] added = new int[2];
        try {
            WechatMemoryExtractor.extractEach(pids, processes, decodeBlob, result -> {
                current[0] = result;
                WechatMemoryAccumulator.Snapshot each = accumulator.add(result);
                added[0] += each.addedRecords();
                added[1] += each.addedMessages();
            });
        } finally {
            accumulator.close();
        }
        WechatMemoryExtractor.ExtractResult scanned = current[0];
        if (scanned == null || scanned.records().isEmpty()) {
            throw new IllegalStateException("未从微信进程内存中提取到任何明文页："
                    + "请确认微信正在运行（当前发现 " + pids.size() + " 个 Weixin.exe 进程），"
                    + "且当前进程有读取其它进程内存的权限（通常需管理员身份）");
        }
        WechatMemoryAccumulator.Snapshot snapshot = accumulator.snapshot();
        WechatMemoryExtractor.ExtractResult merged = snapshot.merged();
        log.info("本次扫描 {} 个进程 / {} 条记录（新增 {} 条记录 / {} 条消息）/ 累积 {} 条记录 / {} 条消息",
                pids.size(), scanned.records().size(), added[0], added[1],
                merged.records().size(), snapshot.messages().size());

        File dbFile = new File(outputDir, MEMORY_DB_NAME);
        WechatMemoryRebuilder.rebuild(merged, dbFile);
        File schemaFile = new File(outputDir, "schema.sql");
        File summaryFile = new File(outputDir, "summary.md");
        WechatMemoryRebuilder.writeSchema(merged, schemaFile);
        WechatMemoryRebuilder.writeSummary(merged, summaryFile);

        // 可读消息视图：把 real_sender_id 按「页 → 库簇」的覆盖度还原成发送者名字。
        // 原始 Msg_All 表里只有库内自增 id，跨库合并后单独看 id 无法还原发送者。
        // 注意这里写的是「已解析好的消息」（含历次扫描的结果），不是重新解析 ——
        // 上一次扫描的页已不在内存里，重新解析会把历史消息的发送者全丢掉。
        WechatMemoryMessages.writeCsv(snapshot.messages(), new File(outputDir,
                WechatMemoryMessages.FILE_NAME));
        WechatMemoryMessages.writeIdMap(scanned, new File(outputDir,
                WechatMemoryMessages.ID_MAP_FILE_NAME));
        File reportFile = new File(outputDir, WechatMemoryMessages.REPORT_FILE_NAME);
        WechatMemoryMessages.writeHtml(snapshot.messages(), reportFile);

        DataRestoreResult exported = WechatJdbcExporter.export(dbFile, config, outputDir);
        List<File> extras = new ArrayList<>();
        extras.add(new File(outputDir, WechatMemoryMessages.FILE_NAME));
        extras.add(new File(outputDir, WechatMemoryMessages.ID_MAP_FILE_NAME));
        extras.add(reportFile);
        extras.add(schemaFile);
        extras.add(summaryFile);
        extras.add(store);
        return appendOutputs(exported, extras);
    }

    /**
     * 是否启用跨次扫描累积（默认启用）。
     *
     * <p>关掉（{@code options['memory.accumulate']=false}）时每次都是干净的单次快照。</p>
     *
     * @param config 还原配置
     * @return 启用返回 true
     */
    private static boolean isAccumulateEnabled(DataRestoreConfig config) {
        Object value = config.getOptions().get(OPTION_MEMORY_ACCUMULATE);
        if (value == null) {
            return true;
        }
        return !Boolean.FALSE.toString().equalsIgnoreCase(String.valueOf(value).trim());
    }

    /**
     * 把附加产物并入还原结果。
     *
     * @param result 原始结果
     * @param extras 附加文件（不存在的会被忽略）
     * @return 合并后的结果
     */
    private static DataRestoreResult appendOutputs(DataRestoreResult result, List<File> extras) {
        if (result == null || !result.isSuccess()) {
            return result;
        }
        List<File> files = new ArrayList<>(result.getOutputFiles());
        Set<String> seen = new HashSet<>();
        for (File file : files) {
            seen.add(file.getAbsolutePath());
        }
        long size = result.getTotalSize();
        for (File extra : extras) {
            if (extra.isFile() && seen.add(extra.getAbsolutePath())) {
                files.add(extra);
                size += extra.length();
            }
        }
        return DataRestoreResult.builder()
                .success(true)
                .outputFiles(files)
                .fileCount(files.size())
                .totalSize(size)
                .durationMillis(result.getDurationMillis())
                .build();
    }

    /**
     * 执行 SQLCipher 直解路径。
     *
     * <p>用数据库密钥把加密库解密成明文 SQLite，再交给 JDBC 导出器输出 CSV / SQL / Excel。
     * 配置了 {@code decrypt.dir} 时明文副本会被保留下来，否则写到输出目录的
     * {@code decrypted} 子目录。</p>
     *
     * @param source 源文件或源目录
     * @param config 还原配置
     * @return 还原结果
     * @throws Exception 解密或导出异常
     */
    private DataRestoreResult runSqlCipher(File source, DataRestoreConfig config) throws Exception {
        File outputDir = config.getOutputDir() != null ? config.getOutputDir() : source.getParentFile();
        if (outputDir == null) {
            return DataRestoreResult.failure("无法确定输出目录，请通过 config.outputDir 指定");
        }
        // 排除输出目录：缺省输出目录位于数据源内部，否则会把上一次解出的明文库当数据源
        List<File> databases = collectDatabases(source, outputDir);
        if (databases.isEmpty()) {
            return DataRestoreResult.failure("未找到可还原的微信数据库文件: " + source.getAbsolutePath());
        }
        Object keyOption = config.getOptions().get(OPTION_KEY);
        String keyHex = keyOption == null ? null : String.valueOf(keyOption).trim();
        if (keyHex == null || !KEY_PATTERN.matcher(keyHex).matches()) {
            return DataRestoreResult.failure("SQLCipher 直解缺少数据库密钥：请通过 options['"
                    + OPTION_KEY + "'] 传入 64 位十六进制密钥");
        }
        byte[] encKey = SqlCipherDecryptor.parseHexKey(keyHex);
        File plainDir = resolveDecryptDir(config, outputDir);

        long started = System.currentTimeMillis();
        List<File> outputs = new ArrayList<>();
        long totalSize = 0;
        for (File db : databases) {
            File target = db;
            if (!SqlCipherDecryptor.isPlainSqlite(db)) {
                try {
                    target = new File(plainDir, db.getName());
                    SqlCipherDecryptor.decrypt(db, encKey, target);
                } catch (Exception e) {
                    // 密钥不匹配或页布局不认识：跳过这个库，
                    // 若最后一个文件都没产出，会在下面统一返回失败
                    log.warn("SQLCipher 解密失败（{}）: {}", db.getName(), e.getMessage());
                    continue;
                }
            }
            DataRestoreResult one = WechatJdbcExporter.export(target, config, outputDir);
            if (one.isSuccess()) {
                outputs.addAll(one.getOutputFiles());
                totalSize += one.getTotalSize();
            }
        }
        if (outputs.isEmpty()) {
            return DataRestoreResult.failure("SQLCipher 直解未生成任何文件");
        }
        return DataRestoreResult.success(outputs, totalSize, System.currentTimeMillis() - started);
    }

    /**
     * 解析明文库保留目录。
     *
     * @param config    还原配置
     * @param outputDir 输出目录
     * @return 明文库目录
     */
    private File resolveDecryptDir(DataRestoreConfig config, File outputDir) {
        Object option = config.getOptions().get(OPTION_DECRYPT_DIR);
        File plainDir = option != null && !String.valueOf(option).isBlank()
                ? new File(String.valueOf(option))
                : new File(outputDir, "decrypted");
        if (!plainDir.isDirectory() && !plainDir.mkdirs()) {
            log.warn("明文库目录创建失败: {}", plainDir.getAbsolutePath());
        }
        return plainDir;
    }

    /**
     * 收集数据源下的数据库文件（源本身是文件时直接返回它）。
     *
     * @param source 源文件或源目录
     * @return 数据库文件列表
     */
    private List<File> collectDatabases(File source) {
        return collectDatabases(source, null);
    }

    /**
     * 收集数据源下的数据库文件，并排除还原器自己生成的目录。
     *
     * <p>缺省输出目录 {@code <源目录>/wechat-restore-out} <b>位于数据源内部</b>，
     * 而 SQLCipher 路径会在它的 {@code decrypted} 子目录里留下解出的明文库。
     * 若不排除，第二次还原会把上一次的产物当成数据源，把同一个库重复导出一遍
     * （实测：本该 2 个文件却出了 4 个，且库里还混进了自己生成的
     * {@code wechat_memory.db}）。所以这里按「目录名 = 缺省输出目录名」或
     * 「目录 = 显式输出目录」两种口径剪枝。</p>
     *
     * @param source  源文件或源目录
     * @param exclude 需要排除的输出目录，可为 null
     * @return 数据库文件列表
     */
    private List<File> collectDatabases(File source, File exclude) {
        List<File> out = new ArrayList<>(16);
        if (source.isFile()) {
            // 源本身是文件时也要校验后缀：数据源里常混着 note.txt 之类的非数据库文件
            if (isDatabase(source)) {
                out.add(source);
            }
            return out;
        }
        Set<String> excluded = new HashSet<>(4);
        if (exclude != null) {
            excluded.add(normalizePath(exclude));
        }
        collectDatabases(source, out, 0, excluded);
        return out;
    }

    /**
     * 递归收集数据库文件。
     *
     * @param dir      目录
     * @param out      输出列表
     * @param depth    当前深度
     * @param excluded 需排除的目录（规范化绝对路径）
     */
    private void collectDatabases(File dir, List<File> out, int depth, Set<String> excluded) {
        if (depth > MAX_SCAN_DEPTH) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if (isGeneratedDir(child, excluded)) {
                    continue;
                }
                collectDatabases(child, out, depth + 1, excluded);
            } else if (isDatabase(child)) {
                out.add(child);
            }
        }
    }

    /**
     * 判断目录是否为还原器自己生成的产物目录。
     *
     * @param dir      目录
     * @param excluded 需排除的目录（规范化绝对路径）
     * @return 是产物目录返回 true
     */
    private static boolean isGeneratedDir(File dir, Set<String> excluded) {
        return DIRECTORY_OUTPUT_NAME.equals(dir.getName()) || excluded.contains(normalizePath(dir));
    }

    /**
     * 规范化路径（用于目录比较）。
     *
     * @param file 文件或目录
     * @return 规范化绝对路径
     */
    private static String normalizePath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    /**
     * 判断文件是否为数据库文件。
     *
     * @param file 文件
     * @return 是数据库返回 true
     */
    private boolean isDatabase(File file) {
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".db") || name.endsWith(".sqlite") || name.endsWith(".sqlite3");
    }

    /**
     * 执行 FFM 原生路径。
     *
     * @param source 源文件
     * @param config 还原配置
     * @return 还原结果
     * @throws Exception 执行异常
     */
    private DataRestoreResult runNative(File source, DataRestoreConfig config) throws Exception {
        File runtimeDir = resolveRuntimeDir(config, true);
        return runNative(source, config, runtimeDir);
    }

    /**
     * 执行 FFM 原生路径（运行时目录已解析）。
     *
     * @param source     源文件
     * @param config     还原配置
     * @param runtimeDir 原生库目录
     * @return 还原结果
     * @throws Exception 执行异常
     */
    private DataRestoreResult runNative(File source, DataRestoreConfig config, File runtimeDir) throws Exception {
        if (!WcdbNativeBridge.isSupported()) {
            throw new IllegalStateException("FFM 原生路径仅支持 Windows 平台，请改用 mode=" + MODE_TOOL);
        }

        // 微信数据目录与 session.db
        File dataDir = resolveDataDir(source, config);
        File sessionDb = resolveSessionDb(source, dataDir);
        File accountDir = WechatExportUtils.deriveAccountDir(sessionDb);
        String myWxid = accountDir != null ? accountDir.getName() : null;

        // 输出目录（AbstractDataRestore 已兜底创建）
        File outputDir = config.getOutputDir() != null ? config.getOutputDir() : source.getParentFile();

        // 密钥解析
        String key = resolveKey(config, runtimeDir, outputDir);

        return WechatNativeExporter.export(runtimeDir, sessionDb, myWxid, key, config);
    }

    /**
     * 解析数据库密钥：显式配置 → key.file → 可选的自动捕获。
     *
     * @param config     还原配置
     * @param runtimeDir 原生库目录
     * @param outputDir  输出目录
     * @return 64 位十六进制密钥
     * @throws Exception 密钥捕获异常
     */
    private String resolveKey(DataRestoreConfig config, File runtimeDir, File outputDir) throws Exception {
        // 1. 显式密钥
        Object explicitKey = config.getOptions().get(OPTION_KEY);
        if (explicitKey != null && KEY_PATTERN.matcher(String.valueOf(explicitKey).trim()).matches()) {
            return String.valueOf(explicitKey).trim();
        }

        // 2. 密钥文件
        Object keyFileOption = config.getOptions().get(OPTION_KEY_FILE);
        File keyFile = keyFileOption != null && !String.valueOf(keyFileOption).isBlank()
                ? new File(String.valueOf(keyFileOption))
                : new File(outputDir, KEY_FILE_NAME);
        if (keyFile.isFile()) {
            String key = Files.readString(keyFile.toPath(), StandardCharsets.UTF_8).trim();
            if (KEY_PATTERN.matcher(key).matches()) {
                return key;
            }
        }

        // 3. 自动捕获（需管理员权限并重启微信）
        boolean autoKey = Boolean.parseBoolean(
                String.valueOf(config.getOptions().getOrDefault(OPTION_AUTO_KEY, Boolean.FALSE)));
        if (autoKey && WxKeyNativeBridge.isSupported()) {
            return WxKeyNativeBridge.captureKey(runtimeDir, outputDir);
        }

        throw new IllegalArgumentException("缺少微信数据库密钥：请通过 options['" + OPTION_KEY
                + "'] 传入 64 位十六进制密钥，或提供 " + keyFile.getAbsolutePath()
                + "，或设置 options['" + OPTION_AUTO_KEY + "']=true 引导自动捕获");
    }

    /**
     * 定位 session.db：源文件本身即会话库时直接使用，否则在数据目录下递归查找。
     *
     * @param source  源文件
     * @param dataDir 微信数据目录
     * @return session.db 文件
     */
    private File resolveSessionDb(File source, File dataDir) {
        if ("session.db".equals(source.getName()) && source.isFile()) {
            return source;
        }
        File sessionDb = WechatExportUtils.findSessionDb(dataDir);
        if (sessionDb == null) {
            throw new IllegalArgumentException("在微信数据目录下未找到 session.db: " + dataDir.getAbsolutePath());
        }
        return sessionDb;
    }

    /**
     * 解析原生库目录。
     *
     * <p>显式配置 {@code runtime.dir} 优先；未配置时根据 tool.path 按
     * runtime / dll / ../runtime / ../dll 的相邻布局推导（对齐 Wechat-Export 目录结构）。</p>
     *
     * @param config     还原配置
     * @param strictMode 严格模式（native）下找不到时抛出异常
     * @return 原生库目录，自动模式下找不到返回 null
     */
    private File resolveRuntimeDir(DataRestoreConfig config, boolean strictMode) {
        Object explicit = config.getOptions().get(OPTION_RUNTIME_DIR);
        if (explicit != null && !String.valueOf(explicit).isBlank()) {
            File dir = new File(String.valueOf(explicit));
            if (dir.isFile()) {
                dir = dir.getParentFile();
            }
            if (isValidRuntimeDir(dir)) {
                return dir;
            }
            if (strictMode) {
                throw new IllegalArgumentException("原生库目录缺少 " + NATIVE_API_DLL + ": " + dir.getAbsolutePath());
            }
            return null;
        }

        // 根据 tool.path 推导
        Object toolPath = config.getOptions().get(OPTION_TOOL_PATH);
        if (toolPath != null && !String.valueOf(toolPath).isBlank()) {
            File tool = new File(String.valueOf(toolPath));
            File toolDir = tool.isDirectory() ? tool : tool.getParentFile();
            if (toolDir != null) {
                File[] candidates = {
                        new File(toolDir, "runtime"),
                        new File(toolDir, "dll"),
                        new File(toolDir.getParentFile(), "runtime"),
                        new File(toolDir.getParentFile(), "dll")
                };
                for (File candidate : candidates) {
                    if (isValidRuntimeDir(candidate)) {
                        return candidate;
                    }
                }
            }
        }

        if (strictMode) {
            throw new IllegalArgumentException("缺少 options['" + OPTION_RUNTIME_DIR
                    + "']：请指定包含 WCDB.dll、SDL2.dll、wcdb_api.dll 的原生库目录");
        }
        return null;
    }

    /**
     * 校验目录是否包含 WCDB C 接口库。
     *
     * @param dir 待校验目录
     * @return 有效返回 true
     */
    private boolean isValidRuntimeDir(File dir) {
        return dir != null && dir.isDirectory() && new File(dir, NATIVE_API_DLL).isFile();
    }

    /**
     * 判断是否配置了 Python 工具路径。
     *
     * @param config 还原配置
     * @return 已配置返回 true
     */
    private boolean hasToolPath(DataRestoreConfig config) {
        Object toolPath = config.getOptions().get(OPTION_TOOL_PATH);
        return toolPath != null && !String.valueOf(toolPath).isBlank();
    }

    /**
     * 判断 auto 模式是否允许降级到内存明文页路径。
     *
     * <p>默认允许。显式设为 {@code false} 可以强制 auto 只走 native / sqlcipher / tool，
     * 便于在「已确认密钥可用」的场景下验证密钥路径。</p>
     *
     * @param config 还原配置
     * @return 允许返回 true
     */
    private boolean isMemoryEnabled(DataRestoreConfig config) {
        return !Boolean.FALSE.toString().equalsIgnoreCase(
                String.valueOf(config.getOptions().getOrDefault(OPTION_MEMORY_ENABLED, Boolean.TRUE)));
    }

    /**
     * 取配置里的合法密钥。
     *
     * @param config 还原配置
     * @return 64 位十六进制密钥；未配置或格式非法返回 null
     */
    private String resolveKeyHex(DataRestoreConfig config) {
        Object keyOption = config.getOptions().get(OPTION_KEY);
        if (keyOption == null) {
            return null;
        }
        String keyHex = String.valueOf(keyOption).trim();
        return KEY_PATTERN.matcher(keyHex).matches() ? keyHex : null;
    }

    /**
     * 解析微信数据目录。
     *
     * <p>options 中的 {@code data.dir} 优先；缺省时源是目录就取它本身，
     * 源是文件才退到它所在目录。</p>
     *
     * @param source 源文件或源目录
     * @param config 还原配置
     * @return 微信数据目录
     */
    private File resolveDataDir(File source, DataRestoreConfig config) {
        Object dataDirOption = config.getOptions().get(OPTION_DATA_DIR);
        if (dataDirOption != null && !String.valueOf(dataDirOption).isBlank()) {
            File dataDir = new File(String.valueOf(dataDirOption));
            if (!dataDir.exists() || !dataDir.isDirectory()) {
                throw new IllegalArgumentException("微信数据目录不存在或不是目录: " + dataDir.getAbsolutePath());
            }
            return dataDir;
        }
        if (source.isDirectory()) {
            return source;
        }
        File parent = source.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new IllegalArgumentException("无法定位微信数据目录，请通过 options['"
                    + OPTION_DATA_DIR + "'] 指定");
        }
        return parent;
    }
}
