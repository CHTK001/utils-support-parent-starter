package com.chua.network.support.tshark.restorer;

/**
* SMB 协议还原器。
*
* <p>识别 SMB1/SMB2 命令头，包含 Magic 与 Command Code 解析。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class SmbProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    /** 获取协议名称 */
    public String getProtocolName() {
        return "smb";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 150;
    }

    @Override
    /** 是否可以Restore */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 4) {
            return false;
        }
        // NetBIOS Session Service header: type(1) + length(1) + data
        int type = rawData[0] & 0xff;
        if (type == 0x00 && rawData.length >= 4) {
            int magic = ((rawData[4] & 0xff) << 24) | ((rawData[5] & 0xff) << 16)
                    | ((rawData[6] & 0xff) << 8) | (rawData[7] & 0xff);
            return magic == 0x424d53ff || magic == 0x424d53fe;
        }
        return false;
    }

    @Override
    /** Restore */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 4) {
            return "[SMB] empty";
        }
        int type = rawData[0] & 0xff;
        int length = rawData[1] & 0xff;
        StringBuilder sb = new StringBuilder("[SMB] ");
        sb.append("nbType=0x").append(Integer.toHexString(type));
        sb.append(", length=").append(length);
        if (type == 0x00 && rawData.length >= 12) {
            int magic = ((rawData[4] & 0xff) << 24) | ((rawData[5] & 0xff) << 16)
                    | ((rawData[6] & 0xff) << 8) | (rawData[7] & 0xff);
            sb.append(", magic=0x").append(Integer.toHexString(magic));
            int command = rawData[11] & 0xff;
            sb.append(", command=0x").append(Integer.toHexString(command)).append(" (").append(toCommandName(magic, command)).append(')');
        }
        return sb.toString();
    }

    /**
    * 转为命令名称
    *
    * @param magic 魔法
    * @param command 命令
    * @return 转为命令名称的结果
     */
    private static String toCommandName(int magic, int command) {
        if (magic == 0x424d53fe) {
            return "SMB2_Command_" + command;
        }
        return switch (command) {
            case 0x04 -> "SMB_COM_CLOSE";
            case 0x06 -> "SMB_COM_DELETE";
            case 0x09 -> "SMB_COM_DELETE_DIRECTORY";
            case 0x0b -> "SMB_COM_QUERY_INFORMATION";
            case 0x0c -> "SMB_COM_SET_INFORMATION";
            case 0x11 -> "SMB_COM_OPEN_ANDX";
            case 0x25 -> "SMB_COM_TRANSACTION";
            case 0x2e -> "SMB_COM_SESSION_SETUP_ANDX";
            case 0x32 -> "SMB_COM_TRANSACTION2";
            case 0x70 -> "SMB_COM_TREE_CONNECT_ANDX";
            case 0x71 -> "SMB_COM_TRANSACTION_SECONDARY";
            case 0x73 -> "SMB_COM_TRANSACTION3";
            case 0x74 -> "SMB_COM_QUERY_FS_INFO";
            default -> "SMB_Command_" + command;
        };
    }
}