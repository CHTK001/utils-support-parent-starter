package com.chua.wechat.support.restore;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.task.restore.AbstractDataRestore;
import com.chua.common.support.task.restore.DataRestoreConfig;
import com.chua.common.support.task.restore.DataRestoreResult;
import com.chua.wechat.support.restore.nativebridge.WcdbNativeBridge;
import com.chua.wechat.support.restore.nativebridge.WechatNativeExporter;
import com.chua.wechat.support.restore.nativebridge.WxKeyNativeBridge;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

/**
 * 本机微信数据还原器。
 *
 * <p>将本机微信（Windows 4.x 版，xwechat_files）聊天数据库还原为可读数据文件
 * （CSV / SQL / Excel），通过 SPI 名称 {@code wechat} / {@code wechat-export} 注册。</p>
 *
 * <h3>双执行路径</h3>
 * <ol>
 *   <li><b>native（推荐，零外部运行时）</b>— Java 25 FFM 直接绑定微信自带的
 *       WCDB.dll / wcdb_api.dll / wx_key.dll，无需 Python、Node.js 或 Electron；</li>
 *   <li><b>tool（兜底）</b>— 编排
 *       <a href="https://github.com/minglin2012/Wechat-Export">Wechat-Export</a>
 *       的 Python 工具链，适用于 WCDB 宿主环境校验拒绝 JVM 初始化（错误码 -1006）的场景。</li>
 * </ol>
 *
 * <h3>配置项（{@link DataRestoreConfig#getOptions()}）</h3>
 * <ul>
 *   <li>{@code mode} — {@code auto}（默认，优先 native 失败回退 tool）/ {@code native} / {@code tool}</li>
 *   <li>{@code runtime.dir} — native 路径必需，原生库目录（含 WCDB.dll、SDL2.dll、wcdb_api.dll、wx_key.dll，
 *       即 Wechat-Export 的 runtime 目录）；tool.path 已配置时按相邻目录自动推导</li>
 *   <li>{@code key} — 64 位十六进制数据库密钥（可选）</li>
 *   <li>{@code key.file} — 密钥文件路径，缺省为输出目录下的 key.txt</li>
 *   <li>{@code auto.key} — 密钥缺失时自动引导重启微信并通过 FFM 捕获（需管理员权限），默认 false</li>
 *   <li>{@code tool.path} — tool 路径的 Wechat-Export export.py 脚本路径（或工具根目录）</li>
 *   <li>{@code data.dir} — 微信数据目录（xwechat_files/wxid_xxx），缺省取源文件所在目录</li>
 *   <li>{@code limit} — 每个会话最多导出条数，0 表示全部</li>
 *   <li>{@code whitelist} / {@code blacklist} — 会话标识/显示名白名单、黑名单（逗号分隔）</li>
 *   <li>{@code skip.groups} — native 路径是否跳过群聊，默认 false</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * Map<String, Object> options = new HashMap<>(8);
 * options.put("mode", "native");
 * options.put("runtime.dir", "D:/tools/Wechat-Export/runtime");
 * options.put("key", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
 * DataRestoreConfig config = DataRestoreConfig.builder()
 *         .format(ExportFormat.EXCEL)
 *         .outputDir(new File("out"))
 *         .options(options)
 *         .build();
 * DataRestore restore = DataRestore.create("wechat", config);
 * DataRestoreResult result = restore.restore(new File("C:/Users/me/xwechat_files/wxid_xxx/session.db"));
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
     * 执行模式：仅 Python 工具编排路径
     */
    public static final String MODE_TOOL = "tool";

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
            case MODE_TOOL:
                return WechatToolExporter.export(source, config);
            case MODE_AUTO:
            default:
                return runAuto(source, config, mode);
        }
    }

    /**
     * 自动模式：原生路径优先，失败且配置了 tool.path 时回退 Python 工具路径。
     *
     * @param source 源文件
     * @param config 还原配置
     * @param mode   原始模式值（异常提示用）
     * @return 还原结果
     * @throws Exception 两条路径均不可用时抛出
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
                if (!toolConfigured) {
                    throw e;
                }
                log.warn("FFM 原生路径失败，回退 Python 工具路径: {}", e.getMessage());
                return WechatToolExporter.export(source, config);
            }
        }
        if (toolConfigured) {
            return WechatToolExporter.export(source, config);
        }
        throw new IllegalArgumentException("微信还原缺少运行环境配置：请设置 options['" + OPTION_RUNTIME_DIR
                + "']（FFM 原生库目录）或 options['" + OPTION_TOOL_PATH + "']（Wechat-Export 脚本路径）");
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
     * 解析微信数据目录。
     *
     * <p>options 中的 {@code data.dir} 优先；缺省取源文件所在目录。</p>
     *
     * @param source 源文件
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
        File parent = source.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new IllegalArgumentException("无法定位微信数据目录，请通过 options['"
                    + OPTION_DATA_DIR + "'] 指定");
        }
        return parent;
    }
}
