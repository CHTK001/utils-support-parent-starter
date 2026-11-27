// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.scatter.discovery;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.scatter.ScatterSetting;
import com.chua.common.support.scatter.ScatterSyncHelper;
import com.chua.common.support.scatter.protocol.ScatterFrame;
import com.chua.common.support.scatter.protocol.ScatterProtocol;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import com.chua.common.support.utils.CollectionUtils;

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
        byte[] payload = announcePayloadBytes();
        ScatterFrame disc = new ScatterFrame(ScatterProtocol.TYPE_DISC,
                genRequestId(), setting.getServicePath(), payload);
        byte[] data = ScatterSyncHelper.sign(disc.encode());
        announce(BROADCAST_ADDR, port, data, "广播");
        String group = setting.getMulticastGroup();
        if (StringUtils.isNotBlank(group)) {
            announce(group, port, data, "多播");
        }
    }

    /**
     * DISC 宣告合并出新增成员后，向新成员单播回本地图（消除「等宣告方下一周期」的收敛上界）。
     * 回表目标取新增条目自身声明的 host:port（宣告方发送 DISC 用的是一次性临时端口，
     * 回源端口不可达）；优先选与收包源 IP 一致的条目（即宣告方本人）。
     *
     * @param sender 收包源地址（可能为 null）
     * @param added  本次合并新增的成员条目（非空）
     */
    @Override
    protected void onNewMemberDiscovered(InetSocketAddress sender, List<Discovery> added) {
        if (CollectionUtils.isEmpty(added)) {
            return;
        }
        Discovery target = null;
        if (sender != null && sender.getAddress() != null) {
            String senderIp = sender.getAddress().getHostAddress();
            for (Discovery d : added) {
                if (d != null && senderIp.equals(d.getHost())) {
                    target = d;
                    break;
                }
            }
        }
        if (target == null) {
            target = added.get(0);
        }
        if (target == null || target.getHost() == null || target.getPort() <= 0
                || setting.getNodeId().equals(target.getServerId())) {
            return;
        }
        sendAnnounceTo(target.getHost(), target.getPort());
    }

    /**
     * 向目标地址单播发送一次 DISC 宣告（携带本地全量服务表，fire-and-forget）。
     *
     * @param host 目标地址，不允许为 null
     * @param port 目标端口，不允许为 null
     */
    private void sendAnnounceTo(String host, int port) {
        byte[] data = ScatterSyncHelper.sign(new ScatterFrame(ScatterProtocol.TYPE_DISC,
                genRequestId(), setting.getServicePath(), announcePayloadBytes()).encode());
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.send(new DatagramPacket(data, data.length,
                    new InetSocketAddress(host, port)));
            log.debug("UDP 自动发现单播回表: {}:{}", host, port);
        } catch (Exception e) {
            log.debug("UDP 自动发现单播回表失败: {}:{} - {}", host, port, e.getMessage());
        }
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
