package com.chua.common.support.host;

import java.util.Locale;

/**
 * 主机身份锚点：参与机器指纹与唯一 ID 的特征项。
 *
 * <p>锚点分两级：
 * <ul>
 *   <li>{@link Tier#STRONG} —— 一级不可变信息。出厂或装机时固化，
 *       更换主板 / 硬盘 / CPU 之外不会变化，是判定「同一台机器」的主依据；</li>
 *   <li>{@link Tier#WEAK} —— 易变信息。改主机名、换网卡、重装系统都会变，
 *       只作为附带说明，不单独决定判定结果。</li>
 * </ul>
 *
 * <p>每个锚点的取值一律经过 {@link HostIdentity#normalize(String)} 归一：
 * 去空白、压内部空白、剔除 OEM 占位串（如 {@code To be filled by O.E.M.}）。
 * 占位串必须剔除，否则整批同型号机器会得到同一个「序列号」，把不同机器判成同一台。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public enum HostAnchor {

    /**
     * 整机 UUID：Windows 取 SMBIOS UUID（Win32_ComputerSystemProduct.UUID），
     * Linux 取 /sys/class/dmi/id/product_uuid，macOS 取 IOPlatformUUID。
     */
    SYSTEM_UUID("systemUuid", Tier.STRONG),

    /**
     * 主板序列号：Windows 取 Win32_BaseBoard.SerialNumber，
     * Linux 取 /sys/class/dmi/id/board_serial。
     */
    BOARD_SERIAL("boardSerial", Tier.STRONG),

    /**
     * BIOS 序列号：Windows 取 Win32_BIOS.SerialNumber，Linux 取 /sys/class/dmi/id/product_serial。
     */
    BIOS_SERIAL("biosSerial", Tier.STRONG),

    /**
     * 系统安装标识：Windows 取注册表 MachineGuid，Linux 取 /etc/machine-id。
     *
     * <p>重装系统会变，但在不重装的前提下它是同机最稳的一项，
     * 因此仍归入一级（判定时与其他一级项按比例共同生效）。</p>
     */
    MACHINE_ID("machineId", Tier.STRONG),

    /**
     * 系统盘物理序列号：Windows 取 Win32_DiskDrive.SerialNumber，
     * Linux 取 /sys/block/&lt;根盘&gt;/device/serial。
     */
    DISK_SERIAL("diskSerial", Tier.STRONG),

    /**
     * 处理器 ID：Windows 取 Win32_Processor.ProcessorId。
     *
     * <p>同型号 CPU 的 ProcessorId 可能相同，单靠它不能区分机器，
     * 只在多锚点共同判定时提供佐证。</p>
     */
    CPU_ID("cpuId", Tier.STRONG),

    /**
     * 卷序列号：格式化分区即变，属易变信息。
     */
    VOLUME_SERIAL("volumeSerial", Tier.WEAK),

    /**
     * 主网卡 MAC：换网卡或启用随机 MAC 即变，属易变信息。
     */
    PRIMARY_MAC("primaryMac", Tier.WEAK),

    /**
     * 主机名：用户可随意修改，属易变信息。
     */
    HOSTNAME("hostname", Tier.WEAK);

    /**
     * 锚点归属的层级。
     */
    public enum Tier {

        /**
         * 一级不可变信息，参与「同一台机器」判定。
         */
        STRONG,

        /**
         * 易变信息，仅作说明，不参与判定。
         */
        WEAK
    }

    /**
     * 采集字段名（与 {@link HostIdentity} 的字段键一致）。
     */
    private final String field;

    /**
     * 所属层级。
     */
    private final Tier tier;

    HostAnchor(String field, Tier tier) {
        this.field = field;
        this.tier = tier;
    }

    /**
     * 获取采集字段名。
     *
     * @return 字段名，小驼峰
     */
    public String field() {
        return field;
    }

    /**
     * 获取所属层级。
     *
     * @return 层级
     */
    public Tier tier() {
        return tier;
    }

    /**
     * 是否为一级不可变信息。
     *
     * @return 一级返回 true
     */
    public boolean strong() {
        return tier == Tier.STRONG;
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT) + "(" + field + ")";
    }
}
