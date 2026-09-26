package com.chua.network.support.tshark.cli;

import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * tshark 命令行工具的设置项：键名、默认值与三级配置查找。
 *
 * <p>配置查找顺序为 系统属性 &rarr; 环境变量 &rarr; {@link DirectoryPollerEnvironment} 属性 &rarr; 默认值。
 * 系统属性便于单次运行覆盖，环境变量便于容器化部署，
 * {@link DirectoryPollerEnvironment} 属性则是与轮询目录同一份配置来源。</p>
 *
 * <p><b>超时约定</b>：本模块所有阻塞操作都必须有上界，禁止出现无界等待。
 * 抓包与解析是长时间运行的任务，一旦上游静默，无界等待会让虚拟线程执行器
 * 永久占用一个线程且无法回收。各处超时的语义与默认值：</p>
 * <ul>
 *   <li>{@link #KEY_EXEC_TIMEOUT_SECONDS} — 单次 tshark 命令执行的墙钟上限，默认 120 秒。
 *       超过后强杀进程并抛出超时异常，避免解析大 pcap 时执行器被无限期占用。</li>
 *   <li>{@link #KEY_VERSION_TIMEOUT_SECONDS} — 版本探测上限，默认 10 秒。版本探测在
 *       每次定位后都会发生，必须远小于执行超时，否则"工具卡住"会表现为长时间无响应。</li>
 *   <li>{@link #KEY_DOWNLOAD_TIMEOUT_SECONDS} — 单个下载制品的墙钟上限，默认 900 秒。
 *       便携版 Wireshark 约 100MB，慢速镜像下需要给足时间；超时按"该镜像失败"处理并尝试下一个镜像。</li>
 *   <li>{@link #KEY_INSTALL_TIMEOUT_SECONDS} — 解压或静默安装的墙钟上限，默认 600 秒。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class TsharkSettings {

    /**
     * tshark 可执行文件显式路径
     */
    public static final String KEY_BINARY = "tshark.binary";

    /**
     * 找不到 tshark 时是否自动装（定位 → 包管理器 → 便携版下载）
     */
    public static final String KEY_AUTO_INSTALL = "tshark.autoInstall";

    /**
     * 便携版安装根目录
     */
    public static final String KEY_INSTALL_DIR = "tshark.install.dir";

    /**
     * 要安装的 Wireshark 版本
     */
    public static final String KEY_DOWNLOAD_VERSION = "tshark.download.version";

    /**
     * 完整下载地址，为空时按版本与镜像拼装
     */
    public static final String KEY_DOWNLOAD_URL = "tshark.download.url";

    /**
     * 镜像基址，逗号分隔多个时按顺序尝试
     */
    public static final String KEY_DOWNLOAD_MIRRORS = "tshark.download.mirrors";

    /**
     * 下载制品的期望 SHA-256，为空则跳过校验
     */
    public static final String KEY_DOWNLOAD_SHA256 = "tshark.download.sha256";

    /**
     * 强制指定包管理器（winget/choco/brew/apt/dnf/yum/apk），为空则自动探测
     */
    public static final String KEY_PACKAGE_MANAGER = "tshark.packageManager";

    /**
     * 单次 tshark 命令执行超时（秒）
     */
    public static final String KEY_EXEC_TIMEOUT_SECONDS = "tshark.execTimeout.seconds";

    /**
     * 版本探测超时（秒）
     */
    public static final String KEY_VERSION_TIMEOUT_SECONDS = "tshark.versionTimeout.seconds";

    /**
     * 单个下载制品的超时（秒）
     */
    public static final String KEY_DOWNLOAD_TIMEOUT_SECONDS = "tshark.downloadTimeout.seconds";

    /**
     * 解压或静默安装的超时（秒）
     */
    public static final String KEY_INSTALL_TIMEOUT_SECONDS = "tshark.installTimeout.seconds";

    /**
     * 装配级别的执行顺序
     *
     * <p>取值逗号分隔，元素为 {@code locate}、{@code packageManager}、{@code download}。
     * 默认三级全开且按此顺序。允许裁剪或重排的原因：包管理器安装通常需要管理员权限，
     * 在受限环境下会耗时数十秒后失败——若已知镜像可达，跳过它可直接省下这段等待。</p>
     */
    public static final String KEY_STAGES = "tshark.stages";

    /**
     * 采集网卡编号（{@code tshark -D} 的输出）
     */
    public static final String KEY_CAPTURE_INTERFACE = "capture.interface";

    /**
     * tshark 显示过滤器
     */
    public static final String KEY_CAPTURE_DISPLAY_FILTER = "capture.displayFilter";

    /**
     * BPF 抓包过滤器
     */
    public static final String KEY_CAPTURE_FILTER = "capture.filter";

    /**
     * 每包捕获字节数上限
     */
    public static final String KEY_CAPTURE_SNAP_LENGTH = "capture.snapLen";

    /**
     * 是否开启混杂模式
     */
    public static final String KEY_CAPTURE_PROMISCUOUS = "capture.promiscuous";

    /**
     * 抓包缓冲大小（MB）
     */
    public static final String KEY_CAPTURE_BUFFER_MB = "capture.bufferMb";

    /**
     * 环形缓冲文件路径前缀
     */
    public static final String KEY_CAPTURE_RING_FILE = "capture.ringFile";

    /**
     * 环形缓冲文件数量
     */
    public static final String KEY_CAPTURE_RING_FILE_COUNT = "capture.ringFileCount";

    /**
     * 环形缓冲轮转间隔（秒）
     */
    public static final String KEY_CAPTURE_RING_FILE_SECONDS = "capture.ringFileSeconds";

    /**
     * 采集时长上限（秒），0 表示不限
     */
    public static final String KEY_CAPTURE_DURATION_SECONDS = "capture.durationSeconds";

    /**
     * 最多采集的数据包数，0 表示不限
     */
    public static final String KEY_CAPTURE_MAX_PACKETS = "capture.maxPackets";

    /**
     * 单包原始 JSON 保留上限（字节）
     */
    public static final String KEY_CAPTURE_MAX_RAW_JSON = "capture.maxRawJsonBytes";

    /**
     * 读循环单次等待新数据的时长（毫秒）
     */
    public static final String KEY_CAPTURE_POLL_TIMEOUT_MILLIS = "capture.pollTimeoutMillis";

    /**
     * 连续无报文的上限（毫秒），0 表示不检测
     */
    public static final String KEY_CAPTURE_IDLE_TIMEOUT_MILLIS = "capture.idleTimeoutMillis";

    /**
     * 停止时温和终止的等待时长（毫秒），超时后强杀
     */
    public static final String KEY_CAPTURE_STOP_GRACE_MILLIS = "capture.stopGraceMillis";

    /**
     * 读循环单次等待新数据的默认时长（毫秒）
     *
     * <p>取值偏小：它决定暂停与停止的响应延迟，
     * 而等待本身几乎不消耗 CPU（仅做 {@code available()} 轮询）。</p>
     */
    public static final long DEFAULT_POLL_TIMEOUT_MILLIS = 200L;

    /**
     * 连续无报文检测的默认上限（毫秒）
     */
    public static final long DEFAULT_IDLE_TIMEOUT_MILLIS = 120_000L;

    /**
     * 停止宽限期的默认时长（毫秒）
     */
    public static final long DEFAULT_STOP_GRACE_MILLIS = 5_000L;

    /**
     * 单次命令执行默认超时（秒）
     */
    public static final long DEFAULT_EXEC_TIMEOUT_SECONDS = 120L;

    /**
     * 版本探测默认超时（秒）
     */
    public static final long DEFAULT_VERSION_TIMEOUT_SECONDS = 10L;

    /**
     * 下载默认超时（秒）
     */
    public static final long DEFAULT_DOWNLOAD_TIMEOUT_SECONDS = 900L;

    /**
     * 安装默认超时（秒）
     */
    public static final long DEFAULT_INSTALL_TIMEOUT_SECONDS = 600L;

    /**
     * 默认安装的 Wireshark 版本，与 {@code CliToolDescriptor} 的最低版本要求配套
     */
    public static final String DEFAULT_DOWNLOAD_VERSION = "4.6.9";

    /**
     * 默认镜像列表，按顺序尝试；单镜像不可达不影响后续镜像
     */
    public static final List<String> DEFAULT_MIRRORS = List.of(
            "https://2.na.dl.wireshark.org",
            "https://1.as.dl.wireshark.org",
            "https://1.eu.dl.wireshark.org",
            "https://1.na.dl.wireshark.org");

    /**
     * 禁止实例化
     */
    private TsharkSettings() {
  // 工具 类
    }

    /**
     * 按 系统属性 &rarr; 环境变量 &rarr; 环境配置 &rarr; 默认值 的顺序取字符串配置。
     *
     * @param key       配置键
     * @param environment 环境配置，可为空
     * @param defaultValue 默认值
     * @return 配置值，均未命中返回默认值
     */
    @Nullable
    public static String string(@Nonnull String key, @Nullable DirectoryPollerEnvironment environment,
                                @Nullable String defaultValue) {
        String value = System.getProperty(key);
        if (isBlank(value)) {
            value = System.getenv(toEnvKey(key));
        }
        if (isBlank(value) && environment != null) {
            value = environment.getProperty(key);
        }
        return isBlank(value) ? defaultValue : value.trim();
    }

    /**
     * 按三级查找顺序取整数配置，非法值回退默认值。
     *
     * @param key         配置键
     * @param environment 环境配置，可为空
     * @param defaultValue 默认值
     * @return 配置值
     */
    public static long seconds(@Nonnull String key, @Nullable DirectoryPollerEnvironment environment,
                               long defaultValue) {
        String value = string(key, environment, null);
        if (isBlank(value)) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed <= 0 ? defaultValue : parsed;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 按三级查找顺序取布尔配置，非法值回退默认值。
     *
     * @param key         配置键
     * @param environment 环境配置，可为空
     * @param defaultValue 默认值
     * @return 配置值
     */
    public static boolean bool(@Nonnull String key, @Nullable DirectoryPollerEnvironment environment,
                               boolean defaultValue) {
        String value = string(key, environment, null);
        return isBlank(value) ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    /**
     * 按三级查找顺序取镜像列表，与 {@link #DEFAULT_MIRRORS} 合并去重。
     *
     * @param environment 环境配置，可为空
     * @return 镜像列表，至少含一个默认镜像
     */
    @Nonnull
    public static List<String> mirrors(@Nullable DirectoryPollerEnvironment environment) {
        List<String> result = new ArrayList<>();
        String configured = string(KEY_DOWNLOAD_MIRRORS, environment, null);
        if (!isBlank(configured)) {
            for (String part : configured.split(",")) {
                String mirror = normalizeMirror(part);
                if (mirror != null) {
                    result.add(mirror);
                }
            }
        }
        for (String mirror : DEFAULT_MIRRORS) {
            if (!result.contains(mirror)) {
                result.add(mirror);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 把配置键转换为环境变量名：{@code tshark.download.version} -> {@code TSHARK_DOWNLOAD_VERSION}。
     *
     * @param key 配置键
     * @return 环境变量名
     */
    @Nonnull
    public static String toEnvKey(@Nonnull String key) {
        return key.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
    }

    /**
     * 归一化镜像地址，去掉尾部斜杠并校验协议。
     *
     * @param mirror 原始镜像地址
     * @return 合法镜像地址，非法返回 {@code null}
     */
    @Nullable
    private static String normalizeMirror(@Nullable String mirror) {
        if (isBlank(mirror)) {
            return null;
        }
        String trimmed = mirror.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return null;
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 判断字符串是否为空或全空白。
     *
     * @param text 待判断的字符串
     * @return 为空返回 true
     */
    private static boolean isBlank(@Nullable String text) {
        return text == null || text.isBlank();
    }

    /**
     * 取全部已知的配置键，供诊断接口罗列当前生效配置。
     *
     * @return 配置键数组
     */
    @Nonnull
    public static String[] knownKeys() {
        return Arrays.asList(
                KEY_BINARY, KEY_AUTO_INSTALL, KEY_INSTALL_DIR, KEY_DOWNLOAD_VERSION,
                KEY_DOWNLOAD_URL, KEY_DOWNLOAD_MIRRORS, KEY_DOWNLOAD_SHA256,
                KEY_PACKAGE_MANAGER, KEY_STAGES, KEY_EXEC_TIMEOUT_SECONDS, KEY_VERSION_TIMEOUT_SECONDS,
                KEY_DOWNLOAD_TIMEOUT_SECONDS, KEY_INSTALL_TIMEOUT_SECONDS,
                KEY_CAPTURE_INTERFACE, KEY_CAPTURE_DISPLAY_FILTER, KEY_CAPTURE_FILTER,
                KEY_CAPTURE_SNAP_LENGTH, KEY_CAPTURE_PROMISCUOUS, KEY_CAPTURE_BUFFER_MB,
                KEY_CAPTURE_RING_FILE, KEY_CAPTURE_RING_FILE_COUNT, KEY_CAPTURE_RING_FILE_SECONDS,
                KEY_CAPTURE_DURATION_SECONDS, KEY_CAPTURE_MAX_PACKETS, KEY_CAPTURE_MAX_RAW_JSON,
                KEY_CAPTURE_POLL_TIMEOUT_MILLIS, KEY_CAPTURE_IDLE_TIMEOUT_MILLIS,
                KEY_CAPTURE_STOP_GRACE_MILLIS).toArray(new String[0]);
    }
}
