package com.chua.network.support.tshark.restorer;

/**
* NTP 协议还原器。
*
* <p>解析 NTP 版本/模式/Stratum 信息。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class NtpProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    /** 获取协议名称 */
    public String getProtocolName() {
        return "ntp";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 90;
    }

    @Override
    /** 是否可以Restore */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 48) {
            return false;
        }
        int liVnMode = rawData[0] & 0xff;
        int version = (liVnMode >> 3) & 0x07;
        return version >= 1 && version <= 4;
    }

    @Override
    /** Restore */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 48) {
            return "[NTP] empty";
        }
        int liVnMode = rawData[0] & 0xff;
        int li = (liVnMode >> 6) & 0x03;
        int version = (liVnMode >> 3) & 0x07;
        int mode = liVnMode & 0x07;
        int stratum = rawData[1] & 0xff;
        int poll = rawData[2] & 0xff;
        int precision = rawData[3] & 0xff;
        long txTimeSec = ((long) (rawData[40] & 0xff) << 24)
                | ((long) (rawData[41] & 0xff) << 16)
                | ((long) (rawData[42] & 0xff) << 8)
                | (rawData[43] & 0xff);

        StringBuilder sb = new StringBuilder("[NTP] ");
        sb.append("version=").append(version);
        sb.append(", mode=").append(toModeName(mode));
        sb.append(", stratum=").append(stratum);
        sb.append(", poll=").append(poll);
        sb.append(", precision=").append(precision);
        sb.append(", leap=").append(toLeapName(li));
        sb.append(", txTimeSec=").append(txTimeSec);
        return sb.toString();
    }

    /**
    * 转为mode名称
    *
    * @param mode mode
    * @return 转为mode名称的结果
     */
    private static String toModeName(int mode) {
        return switch (mode) {
            case 0 -> "Reserved";
            case 1 -> "ActiveSymmetricActive";
            case 2 -> "PassiveSymmetricPassive";
            case 3 -> "Client";
            case 4 -> "Server";
            case 5 -> "Broadcast";
            case 6 -> "NtpControl";
            case 7 -> "PrivateUse";
            default -> "Mode" + mode;
        };
    }

    /**
    * 转为leap名称
    *
    * @param li li
    * @return 转为leap名称的结果
     */
    private static String toLeapName(int li) {
        return switch (li) {
            case 0 -> "NoWarning";
            case 1 -> "LastMinute61s";
            case 2 -> "LastMinute59s";
            case 3 -> "Alarm";
            default -> "Unknown" + li;
        };
    }
}