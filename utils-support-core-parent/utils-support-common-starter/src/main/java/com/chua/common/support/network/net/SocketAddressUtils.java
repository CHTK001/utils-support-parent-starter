package com.chua.common.support.network.net;

import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 对外发布地址解析工具。
 *
 * <p>服务实例自注册（服务发现存活来源）与配置同步（写库兜底值）需要解析出
 * <b>同一个</b>对外地址，调用方共用这一份实现，避免口径漂移。典型配置项
 * 形如 {@code plugin.socket.publish-host} / {@code plugin.socket.host} 等。</p>
 *
 * <p>解析规则：</p>
 * <ul>
 *     <li>显式配置 publish-host 时直接使用；</li>
 *     <li>否则当 host 是本地地址（localhost / 0.0.0.0 / 本机名）时，
 *     按网卡候选地址打分选取（192.168. &gt; 10. &gt; 172.16-31 &gt; 其它），排除 Docker 类虚拟网卡；</li>
 *     <li>否则原样使用配置的 host。</li>
 * </ul>
 */
@Slf4j
public final class SocketAddressUtils {

    private SocketAddressUtils() {
    }

    /**
     * 解析对外发布主机。
     *
     * @param configuredPublishHost 配置的发布主机
     * @param socketHost            服务绑定主机（本地地址时按网卡重新挑选）
     * @return 对外发布主机
     */
    public static String resolvePublishHost(String configuredPublishHost, String socketHost) {
        if (StringUtils.isNotBlank(configuredPublishHost)) {
            return configuredPublishHost.trim();
        }

        if (isLocalPublishHost(socketHost)) {
            String preferredHost = resolvePreferredPublishHost();
            if (StringUtils.isNotBlank(preferredHost)) {
                return preferredHost;
            }

            return NetUtils.getLocalHost();
        }

        return socketHost;
    }

    /**
     * 解析对外发布端口。
     *
     * @param configuredPublishPort 配置的发布端口（&le;0 表示未配置）
     * @param socketPort            服务监听端口
     * @return 对外发布端口
     */
    public static int resolvePublishPort(int configuredPublishPort, int socketPort) {
        return configuredPublishPort > 0 ? configuredPublishPort : socketPort;
    }

    /**
     * 解析对外发布地址（完整 origin）。
     *
     * <p>显式配置 public-url 时直接使用（HTTPS 反向代理 / NAT 场景拼不出真实地址）；
     * 否则按发布主机 + 发布端口拼 {@code http://host:port}。配置同步写库兜底值与
     * 服务实例自注册共用本口径。</p>
     *
     * @param publicUrl            显式配置的对外地址（可为空）
     * @param publishHost          配置的发布主机
     * @param socketHost           服务绑定主机
     * @param publishPort          配置的发布端口（&le;0 表示未配置）
     * @param socketPort           服务监听端口
     * @return 对外发布地址
     */
    public static String resolvePublishUrl(String publicUrl, String publishHost, String socketHost,
                                           int publishPort, int socketPort) {
        if (StringUtils.isNotBlank(publicUrl)) {
            return publicUrl.trim();
        }
        return String.format("http://%s:%d", resolvePublishHost(publishHost, socketHost),
                resolvePublishPort(publishPort, socketPort));
    }

    /**
     * 是否本地发布主机（需要按网卡重新挑选对外地址）。
     *
     * @param host 绑定主机
     * @return 是否本地地址
     */
    private static boolean isLocalPublishHost(String host) {
        if (StringUtils.isBlank(host)) {
            return true;
        }

        String normalizedHost = host.trim();
        if ("localhost".equalsIgnoreCase(normalizedHost)) {
            return true;
        }

        try {
            return NetUtils.isAnyHost(normalizedHost) || NetUtils.isLocalHost(normalizedHost);
        } catch (Exception e) {
            log.debug("[发布地址] 判断本地地址失败: host={}, error={}", normalizedHost, e.getMessage());
            return false;
        }
    }

    /**
     * 解析优先发布主机：遍历非回环 IPv4 地址，排除 Docker 类虚拟网卡，
     * 按网段打分取最高者。
     *
     * @return 优先发布主机；解析失败返回 null
     */
    private static String resolvePreferredPublishHost() {
        try {
            List<String> candidates = new ArrayList<>();
            List<String> fallbackCandidates = new ArrayList<>();
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            interfaces.sort(Comparator.comparingInt(NetworkInterface::getIndex));

            for (NetworkInterface item : interfaces) {
                if (!item.isUp() || item.isLoopback()) {
                    continue;
                }

                String interfaceName = StringUtils.defaultString(item.getName(), "").toLowerCase(Locale.ROOT);
                String displayName = StringUtils.defaultString(item.getDisplayName(), "").toLowerCase(Locale.ROOT);
                boolean dockerLikeInterface = isDockerLikeInterface(interfaceName, displayName);

                for (var address : Collections.list(item.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress() || address.isLinkLocalAddress()) {
                        continue;
                    }

                    String hostAddress = address.getHostAddress();
                    if (StringUtils.isBlank(hostAddress)) {
                        continue;
                    }

                    if (dockerLikeInterface || isDockerLikeAddress(hostAddress)) {
                        fallbackCandidates.add(hostAddress);
                        continue;
                    }

                    candidates.add(hostAddress);
                }
            }

            if (!candidates.isEmpty()) {
                candidates.sort(SocketAddressUtils::comparePublishHost);
                return candidates.get(0);
            }

            if (!fallbackCandidates.isEmpty()) {
                fallbackCandidates.sort(SocketAddressUtils::comparePublishHost);
                return fallbackCandidates.get(0);
            }
        } catch (Exception e) {
            log.debug("[发布地址] 解析对外发布地址失败: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 比较两个候选主机地址的优先级（分高者优先）。
     *
     * @param left left
     * @param right right
     * @return 比较结果
     */
    private static int comparePublishHost(String left, String right) {
        return Integer.compare(scorePublishHost(right), scorePublishHost(left));
    }

    /**
     * 主机地址打分：192.168. &gt; 10. &gt; 172.16-31 &gt; 其它。
     *
     * @param host 主机地址
     * @return 分数
     */
    private static int scorePublishHost(String host) {
        if (StringUtils.isBlank(host)) {
            return Integer.MIN_VALUE;
        }

        if (host.startsWith("192.168.")) {
            return 300;
        }

        if (host.startsWith("10.")) {
            return 250;
        }

        if (isSiteLocal172Address(host)) {
            return 200;
        }

        return 100;
    }

    /**
     * 是否 172.16-31 网段地址。
     *
     * @param host 主机地址
     * @return 是否命中
     */
    private static boolean isSiteLocal172Address(String host) {
        if (!host.startsWith("172.")) {
            return false;
        }

        String[] parts = host.split("\\.");
        if (parts.length < 2) {
            return false;
        }

        try {
            int second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 是否 Docker 类虚拟接口。
     *
     * @param name 接口名
     * @param displayName 接口显示名
     * @return 是否命中
     */
    private static boolean isDockerLikeInterface(String name, String displayName) {
        return name.startsWith("docker")
                || name.startsWith("br-")
                || name.startsWith("veth")
                || displayName.contains("docker")
                || displayName.contains("container")
                || displayName.contains("podman")
                || displayName.contains("virtual");
    }

    /**
     * 是否 Docker 类网段地址（172.17-31）。
     *
     * @param host 主机地址
     * @return 是否命中
     */
    private static boolean isDockerLikeAddress(String host) {
        return host.startsWith("172.17.")
                || host.startsWith("172.18.")
                || host.startsWith("172.19.")
                || host.startsWith("172.20.")
                || host.startsWith("172.21.")
                || host.startsWith("172.22.")
                || host.startsWith("172.23.")
                || host.startsWith("172.24.")
                || host.startsWith("172.25.")
                || host.startsWith("172.26.")
                || host.startsWith("172.27.")
                || host.startsWith("172.28.")
                || host.startsWith("172.29.")
                || host.startsWith("172.30.")
                || host.startsWith("172.31.");
    }
}