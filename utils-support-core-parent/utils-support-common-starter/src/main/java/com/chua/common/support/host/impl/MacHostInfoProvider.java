package com.chua.common.support.host.impl;

import com.chua.common.support.host.HostAnchor;
import com.chua.common.support.host.HostFacts;
import com.chua.common.support.host.HostInfoProvider;
import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.lang.cmd.OsFamily;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * macOS 主机不可变信息采集。
 *
 * <p>能力边界必须先说清楚：macOS 没有 Linux 的 {@code machine-id}，
 * 也没有 Windows 的 {@code MachineGuid}；主板序列号不是独立可读字段
 * （{@code IOPlatformSerialNumber} 是整机序列号）。因此本实现只提供两项一级信息：
 * <ul>
 *   <li>整机 UUID —— {@code IOPlatformUUID}，Apple 在主板烧录，换主板即变；</li>
 *   <li>整机序列号 —— {@code IOPlatformSerialNumber}，同样由 Apple 烧录。</li>
 * </ul>
 * 判定时两项都会参与比对，两者同时不一致即判定为不同机器；这与 Windows/Linux
 * 「换主板丢掉主板序列号 + 整机 UUID」的语义是一致的。</p>
 *
 * <p>卷 UUID 归入易变项（重新格式化即变），只做说明，不参与判定。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Slf4j
@Spi(value = "macos-ioreg", order = 300)
public class MacHostInfoProvider implements HostInfoProvider {

    /**
     * SPI 名称
     */
    private static final String NAME = "macos-ioreg";

    /**
     * 采集超时（毫秒）：ioreg 与 diskutil 都是本地查询，2~3 秒足够
     */
    private static final long TIMEOUT_MS = 10_000L;

    /**
     * 整机 UUID 的正则
     */
    private static final Pattern UUID_PATTERN =
            Pattern.compile("\"IOPlatformUUID\"\\s*=\\s*\"([^\"]+)\"");

    /**
     * 整机序列号的正则
     */
    private static final Pattern SERIAL_PATTERN =
            Pattern.compile("\"IOPlatformSerialNumber\"\\s*=\\s*\"([^\"]+)\"");

    /**
     * 卷 UUID 的正则（diskutil 文本输出：{@code Volume UUID: XXXX}）
     */
    private static final Pattern VOLUME_UUID_PATTERN =
            Pattern.compile("Volume UUID:\\s*([0-9A-Fa-f-]+)");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports() {
        return OsFamily.current().isMacOs();
    }

    @Override
    public int priority() {
        return 300;
    }

    @Override
    public HostFacts capture() {
        Map<HostAnchor, String> values = new EnumMap<>(HostAnchor.class);
        // 一次 ioreg 取回整机 UUID 与整机序列号
        String platform = exec(new String[]{"ioreg", "-rd1", "-c", "IOPlatformExpertDevice"});
        put(values, HostAnchor.SYSTEM_UUID, group1(UUID_PATTERN, platform));
        put(values, HostAnchor.BIOS_SERIAL, group1(SERIAL_PATTERN, platform));
        // 卷 UUID 属易变项，仅作说明
        put(values, HostAnchor.VOLUME_SERIAL, group1(VOLUME_UUID_PATTERN, execVolumeInfo()));
        put(values, HostAnchor.HOSTNAME, hostname());
        put(values, HostAnchor.PRIMARY_MAC, primaryMac());
        return new HostFacts(values, NAME);
    }

    /**
     * 执行命令并返回标准输出，失败返回空串。
     *
     * @param command 命令与参数
     * @return 标准输出；失败返回空串
     */
    private static String exec(String[] command) {
        try {
            CmdResult result = CmdExecutors.execute(command, TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return result != null && result.isSuccess() ? result.getStdout() : "";
        } catch (Exception e) {
            log.debug("[host] 命令 {} 执行失败: {}", command[0], e.getMessage());
            return "";
        }
    }

    /**
     * 读取根卷信息。
     *
     * @return diskutil 文本输出；失败返回空串
     */
    private static String execVolumeInfo() {
        return exec(new String[]{"diskutil", "info", "/"});
    }

    /**
     * 取正则的第一个捕获组。
     *
     * @param pattern 正则
     * @param text    待匹配文本
     * @return 捕获组内容；未匹配返回空串
     */
    private static String group1(Pattern pattern, String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    /**
     * 只在取到非空值时才放入。
     *
     * @param values 取值表
     * @param anchor 锚点
     * @param value  取值
     */
    private static void put(Map<HostAnchor, String> values, HostAnchor anchor, String value) {
        if (value != null && !value.isBlank()) {
            values.put(anchor, value);
        }
    }

    /**
     * 取主机名。
     *
     * @return 主机名；取不到返回空串
     */
    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 取主网卡 MAC。
     *
     * @return MAC；取不到返回空串
     */
    private static String primaryMac() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nic.isLoopback() || nic.isVirtual() || !nic.isUp()) {
                    continue;
                }
                byte[] hardware = nic.getHardwareAddress();
                if (hardware == null || hardware.length != 6) {
                    continue;
                }
                StringBuilder sb = new StringBuilder(17);
                for (int i = 0; i < hardware.length; i++) {
                    if (i > 0) {
                        sb.append(':');
                    }
                    sb.append(String.format("%02X", hardware[i]));
                }
                return sb.toString();
            }
        } catch (Exception e) {
            log.debug("[host] 枚举网卡失败: {}", e.getMessage());
        }
        return "";
    }
}
