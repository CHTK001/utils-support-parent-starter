// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.scatter.discovery;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.scatter.ScatterSetting;
import com.chua.common.support.scatter.protocol.ScatterFrame;
import com.chua.common.support.scatter.protocol.ScatterProtocol;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UDP 自动发现：多播 + 广播宣告。
 *
 * <p>protocol=udp 时的自动发现实现：每轮把本地全量服务表以 DISC 帧向
 * 广播地址（255.255.255.255）与多播组（{@code multicastGroup}）各宣告一次，
 * 收到 DISC 的节点合并表内同分组条目（见 {@link AbstractScatterDiscovery#handle}），
 * 无需逐台探测。新节点入网即被全网感知，天然适配局域网零配置场景。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public class UdpBroadcastDiscovery extends AbstractScatterDiscovery {

    /**
     * 单个数据报安全载荷上限（留余量，避免超过 64KB UDP 上限）
     */
    private static final int MAX_DATAGRAM_BYTES = 60_000;
    /**
     * 广播地址
     */
    private static final String BROADCAST_ADDR = "255.255.255.255";

    /**
     * 端口未配置告警（仅告警一次）
     */
    private final AtomicBoolean portWarned = new AtomicBoolean(false);

    /**
     * 构造方法，创建 Udp广播发现 实例。
     *
     * @param setting 方法入参 setting
     */
    public UdpBroadcastDiscovery(ScatterSetting setting) {
        super(setting);
    }

    @Override
    protected void doDiscoveryRound() {
        int port = setting.discoveryPort();
        if (port <= 0) {
            if (portWarned.compareAndSet(false, true)) {
                log.warn("UDP 自动发现要求固定端口（port>0），当前 port=0，跳过宣告");
            }
            return;
        }
        byte[] payload = buildAnnouncePayload();
        ScatterFrame disc = new ScatterFrame(ScatterProtocol.TYPE_DISC,
                genRequestId(), setting.getServicePath(), payload);
        byte[] data = disc.encode();
        announce(BROADCAST_ADDR, port, data, "广播");
        String group = setting.getMulticastGroup();
        if (StringUtils.isNotBlank(group)) {
            announce(group, port, data, "多播");
        }
    }

    /**
     * 构建宣告载荷：本地全量服务表；超过数据报上限时仅携带自身条目。
     *
     * @return JSON 字节
     */
    private byte[] buildAnnouncePayload() {
        Set<Discovery> services = getServiceAll(setting.getServicePath());
        byte[] payload = Json.toJson(Wraps.list(services)).getBytes(StandardCharsets.UTF_8);
        if (payload.length <= MAX_DATAGRAM_BYTES) {
            return payload;
        }
        List<Discovery> selfOnly = Wraps.list();
        for (Discovery d : services) {
            if (setting.getNodeId().equals(d.getServerId())) {
                selfOnly.add(d);
            }
        }
        return Json.toJson(selfOnly).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 向目标地址发送一次 DISC 宣告（fire-and-forget，不等待响应）。
     *
     * @param host 目标地址，不允许为 null
     * @param port 目标端口，不允许为 null
     * @param data 帧字节，不允许为 null
     * @param desc 日志描述（广播/多播）
     */
    private void announce(String host, int port, byte[] data, String desc) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.send(new DatagramPacket(data, data.length,
                    new InetSocketAddress(host, port)));
            log.debug("UDP 自动发现{}完成: {}:{}", desc, host, port);
        } catch (Exception e) {
            log.debug("UDP 自动发现{}失败: {}:{} - {}", desc, host, port, e.getMessage());
        }
    }
}
