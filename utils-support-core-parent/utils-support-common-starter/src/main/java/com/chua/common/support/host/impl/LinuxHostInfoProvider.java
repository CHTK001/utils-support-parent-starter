package com.chua.common.support.host.impl;

import com.chua.common.support.host.HostAnchor;
import com.chua.common.support.host.HostFacts;
import com.chua.common.support.host.HostInfoProvider;
import com.chua.common.support.lang.cmd.OsFamily;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Linux 主机不可变信息采集：直接读 sysfs 与 machine-id，不启动任何子进程。
 *
 * <p>数据来源：
 * <ul>
 *   <li>{@code /sys/class/dmi/id/product_uuid} —— 整机 UUID（SMBIOS Type 1）</li>
 *   <li>{@code /sys/class/dmi/id/board_serial} —— 主板序列号（SMBIOS Type 2）</li>
 *   <li>{@code /sys/class/dmi/id/product_serial} —— BIOS/整机序列号（SMBIOS Type 1）</li>
 *   <li>{@code /etc/machine-id} 或 {@code /var/lib/dbus/machine-id}</li>
 *   <li>{@code /sys/class/block/&lt;根盘&gt;/device/serial} —— 系统盘序列号</li>
 * </ul>
 *
 * <p>{@code product_uuid} / {@code board_serial} 需要 root 才能读（权限 0400）。
 * 普通用户执行时这两项为空，判定会退化成 machine-id + 磁盘序列号两项，
 * 仍是有效的一级信息，只是换硬盘时会同时丢掉一项，需要留意阈值。
 * 应用若跑在 root 或容器外的服务账号下，建议显式授予读权限。</p>
 *
 * <p><b>容器场景</b>：Docker 镜像里的 {@code /etc/machine-id} 往往是构建时写入的常量，
 * 同镜像派生的多个容器会拿到同一个值。容器部署请用
 * {@link com.chua.common.support.host.HostInfoRouter#override} 显式指定实例 ID。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Slf4j
@Spi(value = "linux-sysfs", order = 300)
public class LinuxHostInfoProvider implements HostInfoProvider {

    /**
     * SPI 名称
     */
    private static final String NAME = "linux-sysfs";

    /**
     * 默认权重：优先级 300
     */
    private static final int DEFAULT_PRIORITY = 300;

    /**
     * 输出 StringBuilder 默认容量
     */
    private static final int SB_DEFAULT_CAPACITY = 17;

    /**
     * DMI 标识目录
     */
    private static final String DMI_ROOT = "/sys/class/dmi/id/";

    /**
     * 整机 UUID 文件
     */
    private static final String DMI_PRODUCT_UUID = "product_uuid";

    /**
     * 主板序列号文件
     */
    private static final String DMI_BOARD_SERIAL = "board_serial";

    /**
     * 整机/BIOS 序列号文件
     */
    private static final String DMI_PRODUCT_SERIAL = "product_serial";

    /**
     * machine-id 候选路径
     */
    private static final String[] MACHINE_ID_PATHS = {
            "/etc/machine-id",
            "/var/lib/dbus/machine-id"};

    /**
     * 系统盘读取路径上限：mountinfo 行数很多，只需找到根挂载
     */
    private static final String MOUNT_INFO = "/proc/self/mountinfo";

    /**
     * 块设备符号链接根
     */
    private static final String SYS_DEV_BLOCK = "/sys/dev/block/";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports() {
        return OsFamily.current() == OsFamily.LINUX;
    }

    @Override
    public int priority() {
        return DEFAULT_PRIORITY;
    }

    @Override
    public HostFacts capture() {
        Map<HostAnchor, String> values = new EnumMap<>(HostAnchor.class);
        put(values, HostAnchor.SYSTEM_UUID, read(DMI_ROOT + DMI_PRODUCT_UUID));
        put(values, HostAnchor.BOARD_SERIAL, read(DMI_ROOT + DMI_BOARD_SERIAL));
        put(values, HostAnchor.BIOS_SERIAL, read(DMI_ROOT + DMI_PRODUCT_SERIAL));
        put(values, HostAnchor.MACHINE_ID, readFirst(MACHINE_ID_PATHS));
        put(values, HostAnchor.DISK_SERIAL, rootDiskSerial());
        put(values, HostAnchor.HOSTNAME, hostname());
        put(values, HostAnchor.PRIMARY_MAC, primaryMac());
        return new HostFacts(values, NAME);
    }

    /**
     * 只在取到非空值时才放入，避免用空串占位。
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
     * 读取单个文本文件，失败返回空串。
     *
     * @param path 路径
     * @return 内容（已 trim）；读不到返回空串
     */
    private static String read(String path) {
        return read(Paths.get(path));
    }

    /**
     * 读取单个文本文件，失败返回空串。
     *
     * @param path 路径
     * @return 内容（已 trim）；读不到返回空串
     */
    private static String read(Path path) {
        try {
            if (!Files.isReadable(path)) {
                return "";
            }
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 依次尝试多个路径，返回首个非空内容。
     *
     * @param paths 候选路径
     * @return 内容；全部失败返回空串
     */
    private static String readFirst(String[] paths) {
        for (String path : paths) {
            String value = read(path);
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    /**
     * 取系统盘序列号。
     *
     * <p>步骤：从 {@code /proc/self/mountinfo} 找到挂载点为 {@code /} 的设备的
     * {@code major:minor}，经 {@code /sys/dev/block/} 解析出内核块设备名，
     * 去掉分区后缀（sda1 → sda、nvme0n1p1 → nvme0n1），再读 {@code device/serial}。</p>
     *
     * <p>virtio / 云盘等虚拟块设备通常没有 serial 文件，此时返回空串，
     * 由其余锚点承担判定。</p>
     *
     * @return 序列号；取不到返回空串
     */
    private static String rootDiskSerial() {
        String device = rootDeviceToken();
        if (device.isEmpty()) {
            return "";
        }
        Path link = Paths.get(SYS_DEV_BLOCK, device);
        try {
            if (!Files.exists(link)) {
                return "";
            }
            // /sys/dev/block/8:1 -> ../../devices/.../block/sda/sda1
            String real = link.toRealPath().toString();
            String name = real.substring(real.lastIndexOf('/') + 1);
            String disk = stripPartitionSuffix(name);
            String serial = read(Paths.get("/sys/class/block", disk, "device", "serial"));
            if (!serial.isEmpty()) {
                return serial;
            }
            // 部分驱动把序列号放在 wwid 或 vpd_pg80（SCSI）
            String wwid = read(Paths.get("/sys/class/block", disk, "device", "wwid"));
            return wwid.isEmpty() ? "" : wwid;
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 从 mountinfo 中找出根挂载的 {@code major:minor}。
     *
     * @return 形如 {@code 8:1}；找不到返回空串
     */
    private static String rootDeviceToken() {
        try {
            List<String> lines = Files.readAllLines(Paths.get(MOUNT_INFO), StandardCharsets.UTF_8);
            for (String line : lines) {
                // 字段：id parent major:minor root mountPoint ...
                String[] parts = line.split(" ");
                if (parts.length < 5) {
                    continue;
                }
                if ("/".equals(parts[4])) {
                    return parts[2];
                }
            }
        } catch (IOException e) {
            log.debug("[host] 读取 {} 失败: {}", MOUNT_INFO, e.getMessage());
        }
        return "";
    }

    /**
     * 去掉内核设备名里的分区后缀。
     *
     * <p>{@code sda1 → sda}；{@code nvme0n1p1 → nvme0n1}；{@code mmcblk0p1 → mmcblk0}；
     * 已是非分区名时原样返回。</p>
     *
     * @param name 内核设备名
     * @return 整盘设备名
     */
    private static String stripPartitionSuffix(String name) {
        if (name.startsWith("nvme") || name.startsWith("mmcblk")) {
            int p = name.lastIndexOf('p');
            if (p > 0 && p < name.length() - 1 && Character.isDigit(name.charAt(p + 1))) {
                return name.substring(0, p);
            }
            return name;
        }
        int end = name.length();
        while (end > 1 && Character.isDigit(name.charAt(end - 1))) {
            end--;
        }
        return name.substring(0, end);
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
     * 取主网卡 MAC：第一个已启用、非回环、非虚拟且有硬件地址的网卡。
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
                StringBuilder sb = new StringBuilder(SB_DEFAULT_CAPACITY);
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
