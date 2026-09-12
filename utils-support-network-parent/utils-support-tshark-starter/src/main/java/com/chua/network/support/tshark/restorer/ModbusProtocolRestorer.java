package com.chua.network.support.tshark.restorer;

/**
 * Modbus TCP 协议还原器。
 *
 * <p>Modbus Application Protocol over TCP/IP：
 * <ul>
 *   <li>MBAP Header (7 bytes): transactionId(2) + protocolId(2, 0x0000) + length(2) + unitId(1)</li>
 *   <li>Function Code (1 byte): 0x01=ReadCoils, 0x03=ReadHoldingRegisters 等</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ModbusProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    /** 获取协议名称 */
    public String getProtocolName() {
        return "modbus";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 260;
    }

    @Override
    /** 是否可以Restore */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 8) {
            return false;
        }
        int protoId = ((rawData[2] & 0xff) << 8) | (rawData[3] & 0xff);
        int length = ((rawData[4] & 0xff) << 8) | (rawData[5] & 0xff);
        if (protoId != 0) {
            return false;
        }
        int fc = rawData[7] & 0xff;
        return fc >= 1 && fc <= 127 && length == rawData.length - 6;
    }

    @Override
    /** Restore */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 8) {
            return "[Modbus] empty";
        }
        int txnId = ((rawData[0] & 0xff) << 8) | (rawData[1] & 0xff);
        int length = ((rawData[4] & 0xff) << 8) | (rawData[5] & 0xff);
        int unitId = rawData[6] & 0xff;
        int fc = rawData[7] & 0xff;

        StringBuilder sb = new StringBuilder("[Modbus] ");
        sb.append("txnId=").append(txnId);
        sb.append(", unitId=").append(unitId);
        sb.append(", func=").append(String.format("0x%02x", fc)).append(" (").append(toFunctionName(fc)).append(')');
        sb.append(", length=").append(length);
        return sb.toString();
    }

    /**
     * 转为function名称
     *
     * @param fc 函数计算
     * @return 转为function名称的结果
     */
    private static String toFunctionName(int fc) {
        return switch (fc) {
            case 0x01 -> "ReadCoils";
            case 0x02 -> "ReadDiscreteInputs";
            case 0x03 -> "ReadHoldingRegisters";
            case 0x04 -> "ReadInputRegisters";
            case 0x05 -> "WriteSingleCoil";
            case 0x06 -> "WriteSingleRegister";
            case 0x0f -> "WriteMultipleCoils";
            case 0x10 -> "WriteMultipleRegisters";
            case 0x16 -> "MaskWriteRegister";
            case 0x17 -> "ReadWriteMultipleRegisters";
            default -> "Func_0x" + Integer.toHexString(fc);
        };
    }
}