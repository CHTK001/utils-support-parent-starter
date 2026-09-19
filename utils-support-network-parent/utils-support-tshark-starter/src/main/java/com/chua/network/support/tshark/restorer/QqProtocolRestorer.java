package com.chua.network.support.tshark.restorer;

/**
 * QQ (OICQ) 协议还原器。
 *
 * <p>QQ 协议基于 UDP，默认端口 8000。
 * 识别 QQ 协议头：第 1 字节为 0x02 表示 OICQ 协议版本，
 * 后跟 2 字节 命令（如 0x00 0x06 = Login 请求，0x00 0x01 = Login Confirm）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class QqProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    /** 获取协议名称 */
    public String getProtocolName() {
        return "qq";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 120;
    }

    @Override
    /** 是否可以Restore */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 7) {
            return false;
        }
        int version = rawData[0] & 0xff;
        if (version != 0x02 && version != 0x03) {
            return false;
        }
        int command = ((rawData[1] & 0xff) << 8) | (rawData[2] & 0xff);
        return command >= 0x0001 && command <= 0x00ff;
    }

    @Override
    /** Restore */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 7) {
            return "[QQ] empty";
        }
        int version = rawData[0] & 0xff;
        int command = ((rawData[1] & 0xff) << 8) | (rawData[2] & 0xff);
        int seq = ((rawData[3] & 0xff) << 8) | (rawData[4] & 0xff);
        long qq = rawData.length >= 11
                ? (((long) (rawData[5] & 0xff) << 24)
                | ((long) (rawData[6] & 0xff) << 16)
                | ((long) (rawData[7] & 0xff) << 8)
                | (rawData[8] & 0xff))
                : 0L;

        StringBuilder sb = new StringBuilder("[QQ/OICQ] ");
        sb.append("version=").append(version);
        sb.append(", command=").append(command).append(" (").append(toCommandName(command)).append(')');
        sb.append(", seq=").append(seq);
        if (qq > 10000) {
            sb.append(", qq=").append(qq);
        }
        return sb.toString();
    }

    /**
     * 转为命令名称
     *
     * @param command 命令
     * @return 转为命令名称的结果
     */
    private static String toCommandName(int command) {
        return switch (command) {
            case 0x0001 -> "LoginConfirm";
            case 0x0002 -> "LoginReject";
            case 0x0006 -> "LoginRequest";
            case 0x0007 -> "Logout";
            case 0x0008 -> "KeepAlive";
            case 0x0009 -> "UpdateInfo";
            case 0x000a -> "SearchUser";
            case 0x000b -> "GetBuddyList";
            case 0x000c -> "GetOnlineBuddy";
            case 0x000d -> "SendMsg";
            case 0x000e -> "RecvMsg";
            case 0x000f -> "GetMsg";
            case 0x001a -> "ChangeStatus";
            case 0x001d -> "RequestKey";
            case 0x001e -> "LoginRequestEx";
            case 0x001f -> "LoginConfirmEx";
            case 0x0020 -> "ExSearchUser";
            default -> "UnknownCommand";
        };
    }
}
