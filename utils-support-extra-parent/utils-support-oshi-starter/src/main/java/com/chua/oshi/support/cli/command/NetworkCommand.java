package com.chua.oshi.support.cli.command;

import com.chua.common.support.utils.CommandLine;
import com.chua.oshi.support.Network;
import com.chua.oshi.support.Oshi;
import com.chua.oshi.support.cli.display.Formatter;

import java.util.ArrayList;
import java.util.List;

/**
 * oshc net — 网络接口明细：IP/MAC、收发字节、速率、状态。
 *
 * @author CH
 * @since 4.0.0.42
 * @param bps bps
 * @return 格式化速度的结果
 * @param options 期权
 */
public final class NetworkCommand extends AbstractCommand {

    @Override
    public String name() {
        return "net";
    /**
     * description。
     * @return description的结果
     * @param bps bps
     * @param options 期权
     */
    }

    @Override
    public String description() {
        return "Network interfaces: IP/MAC, bytes, speed, status";
    }

    @Override
    public void execute(CommandLine options) {
        List<Network> nets = Oshi.newNetwork();
        if (options.has("up")) {
            nets = nets.stream().filter(n -> "UP".equalsIgnoreCase(n.getIfOperStatus())).toList();
        }

        System.out.println();
        System.out.println("Network Interfaces (" + nets.size() + "):");
        List<String[]> rows = new ArrayList<>();
        for (Network net : nets) {
            String[] ipv4 = net.getIpv4();
            String ip = (ipv4 != null && ipv4.length > 0) ? String.join(", ", ipv4) : "N/A";
            rows.add(new String[]{
                    net.getName() == null ? "" : net.getName(),
                    net.getDisplayName() == null ? "" : net.getDisplayName(),
                    ip,
                    net.getMac() == null ? "" : net.getMac(),
                    Formatter.formatBytes(net.getReceiveBytes()),
                    Formatter.formatBytes(net.getTransmitBytes()),
                    formatSpeed(net.getSpeed()),
                    net.getIfOperStatus() == null ? "" : net.getIfOperStatus()
            });
        }
        if (rows.isEmpty()) {
            System.out.println("  (none)");
        } else {
            System.out.println(Formatter.table("", new String[]{"Name", "Display", "IPv4", "MAC", "RX", "TX", "Speed", "Status"}, rows));
        }
    }

    /**
     * 格式化Speed。
     *
     * @param bps 方法入参 bps
     * @return 结果字符串
     */
    private static String formatSpeed(long bps) {
        if (bps <= 0) {
            return "N/A";
        }
        if (bps >= 1_000_000_000L) {
            return String.format("%.1f Gbps", bps / 1_000_000_000.0);
        }
        if (bps >= 1_000_000L) {
            return String.format("%.1f Mbps", bps / 1_000_000.0);
        }
        return bps + " bps";
    }
}
