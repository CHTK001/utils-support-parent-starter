package com.chua.gateway.server.artifact;

import com.chua.common.support.lang.cmd.RuntimeType;
import com.chua.runtime.core.model.RuntimeArtifact;
import com.chua.gateway.server.config.GatewayProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * guacd 子进程 RuntimeArtifact 描述（经 RuntimeBoot 启动）。
 *
 * <p>启动流程（由 {@code RuntimeBoot.startAsService()} 驱动）：</p>
 * <ol>
 *   <li>{@code install()} — 通过 common-starter {@link com.chua.common.support.network.download.Downloader}
 *       把 guacd tarball 下载到 {@code ~/.utils-support-gateway/cache/guacd/<version>/}（或读 local-override）</li>
 *   <li>解压后获得 guacd 可执行文件路径</li>
 *   <li>{@code run()} — {@code ProcessBuilder} 启动 guacd 监听 {@code 0.0.0.0:<guacd.port>}</li>
 * </ol>
 *
 * <p>前端通过 {@code @guacamole/client} 连 guacd 进行 RDP/VNC/SSH。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class GuacdArtifact {

    /**
     * artifact 唯一标识
     */
    private static final String ARTIFACT_ID = "guacd";

    /**
     * guacd 默认版本
     */
    public static final String DEFAULT_VERSION = "1.5.5";

    /**
     * 默认下载源（Apache 官方 source tarball，自行编译）。
     * 注：Apache 不发布 guacd 预编译二进制，必须 source 编译。
     */
    private static final String DEFAULT_DOWNLOAD_URL =
            "https://archive.apache.org/dist/guacamole/1.5.5/source/guacamole-server-1.5.5.tar.gz";

    /**
     * guacd 可执行文件名（依据 OS 调整）
     */
    private static final String GUACD_BINARY = detectGuacdBinary();

    /**
     * guacd 二进制相对路径（解压后）
     */
    private static final String GUACD_RELATIVE_PATH = "sbin/guacd";

    /**
     * guacd 启动超时（毫秒）
     */
    private static final long STARTUP_TIMEOUT_MS = 30_000L;

    /**
     * cache 目录模板
     */
    private static final String CACHE_DIR_TEMPLATE =
            "%s/.utils-support-gateway/cache/%s/%s";

    /**
     * Windows 平台关键字
     */
    private static final String OS_WINDOWS = "windows";

    /**
     * Mac 平台关键字
     */
    private static final String OS_MAC = "mac";

    /**
     * user.home 系统属性名
     */
    private static final String USER_HOME = "user.home";

    /**
     * guacd 默认监听端口占位（实际从 GatewayProperties 读取）
     */
    private static final String DEFAULT_PORT_PLACEHOLDER = "${guacd.port}";

    /**
     * 私有构造，禁止实例化。
     */
    private GuacdArtifact() {
    }

    /**
     * 根据当前 OS 选正确的 guacd 二进制。
     *
     * @return 在 Windows 上 {@code guacd.exe}，其他 {@code guacd}
     */
    private static String detectGuacdBinary() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains(OS_WINDOWS)) {
            return "guacd-windows-x86_64.exe";
        }
        if (os.contains(OS_MAC)) {
            return "guacd-macosx";
        }
        return "guacd";
    }

    /**
     * 默认版本的 GuacdArtifact。
     *
     * @return RuntimeArtifact
     */
    public static RuntimeArtifact createDefault() {
        return create(DEFAULT_VERSION, DEFAULT_DOWNLOAD_URL);
    }

    /**
     * 展开 {@code ${user.home}}。
     *
     * @return 真实 user.home 路径
     */
    private static String userHome() {
        return System.getProperty(USER_HOME, "").replace("${user.home}", System.getProperty(USER_HOME, ""));
    }

    /**
     * 构造 RuntimeArtifact。
     *
     * @param version      版本字符串
     * @param downloadUrl  下载地址（tar.gz）
     * @return RuntimeArtifact
     */
    public static RuntimeArtifact create(String version, String downloadUrl) {
        log.info("[gateway-server] 构造 GuacdArtifact: version={} url={}", version, downloadUrl);
        java.io.File extractDir = new java.io.File(
                String.format(CACHE_DIR_TEMPLATE, userHome(), ARTIFACT_ID, version));
        return RuntimeArtifact.builder()
                .id(ARTIFACT_ID + "-" + version)
                .name("Apache Guacamole guacd " + version)
                .type(RuntimeType.NATIVE)
                .downloadUrl(downloadUrl)
                .downloadFilename("guacamole-server-" + version + ".tar.gz")
                .autoExtract(true)
                .extractTo(extractDir.toPath())
                .args(java.util.Arrays.asList("-b", "127.0.0.1", "-p", String.valueOf(GatewayProperties.guacdPort())))
                .startupTimeoutMs(STARTUP_TIMEOUT_MS)
                .build();
    }

    /**
     * guacd 二进制文件名（按 OS 选择）。
     *
     * @return guacd / guacd.exe
     */
    public static String binaryName() {
        return GUACD_BINARY;
    }

    /**
     * 二进制相对路径（解压包内）。
     *
     * @return "sbin/guacd"
     */
    public static String binaryRelativePath() {
        return GUACD_RELATIVE_PATH;
    }
}
