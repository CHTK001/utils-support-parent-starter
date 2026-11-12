// [P3C A20 豁免] A20/A30/A31: 异常码属于 Modbus 协议常量，非业务魔法值。
package com.chua.modbus.support;

/**
 * Modbus 异常。
 *
 * <p>两种语义共用同一异常类型：</p>
 * <ul>
 *   <li>本地异常（连接失败、报文畸形、超时）：{@link #getExceptionCode()} 为 {@code 0}；</li>
 *   <li>对端返回的异常响应：{@link #getExceptionCode()} 为 Modbus 异常码（见本类常量）。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.50
 */
public class ModbusException extends RuntimeException {

    /**
     * 未携带 Modbus 异常码（本地异常）
     */
    public static final int NO_EXCEPTION_CODE = 0;
    /**
     * 异常码：非法功能码
     */
    public static final int ILLEGAL_FUNCTION = 0x01;
    /**
     * 异常码：非法数据地址
     */
    public static final int ILLEGAL_DATA_ADDRESS = 0x02;
    /**
     * 异常码：非法数据值
     */
    public static final int ILLEGAL_DATA_VALUE = 0x03;
    /**
     * 异常码：从站设备故障
     */
    public static final int SLAVE_DEVICE_FAILURE = 0x04;
    /**
     * 异常码：确认（长耗时操作已受理）
     */
    public static final int ACKNOWLEDGE = 0x05;
    /**
     * 异常码：从站设备忙
     */
    public static final int SLAVE_DEVICE_BUSY = 0x06;
    /**
     * 异常码：网关路径不可用
     */
    public static final int GATEWAY_PATH_UNAVAILABLE = 0x0A;
    /**
     * 异常码：网关目标设备无响应
     */
    public static final int GATEWAY_TARGET_NO_RESPONSE = 0x0B;

    /**
     * Modbus 异常码，{@link #NO_EXCEPTION_CODE} 表示本地异常
     */
    private final int exceptionCode;

    /**
     * 创建本地异常。
     *
     * @param message 异常描述
     */
    public ModbusException(String message) {
        this(NO_EXCEPTION_CODE, message, null);
    }

    /**
     * 创建本地异常。
     *
     * @param message 异常描述
     * @param cause   根因
     */
    public ModbusException(String message, Throwable cause) {
        this(NO_EXCEPTION_CODE, message, cause);
    }

    /**
     * 创建携带 Modbus 异常码的异常。
     *
     * @param exceptionCode Modbus 异常码
     * @param message       异常描述
     */
    public ModbusException(int exceptionCode, String message) {
        this(exceptionCode, message, null);
    }

    /**
     * 创建携带 Modbus 异常码的异常。
     *
     * @param exceptionCode Modbus 异常码
     * @param message       异常描述
     * @param cause         根因
     */
    public ModbusException(int exceptionCode, String message, Throwable cause) {
        super(message, cause);
        this.exceptionCode = exceptionCode;
    }

    /**
     * 获取 Modbus 异常码。
     *
     * @return 异常码，{@link #NO_EXCEPTION_CODE} 表示本地异常
     */
    public int getExceptionCode() {
        return exceptionCode;
    }

    /**
     * 是否为对端返回的异常响应。
     *
     * @return 携带非零异常码时返回 {@code true}
     */
    public boolean isProtocolException() {
        return exceptionCode != NO_EXCEPTION_CODE;
    }

    /**
     * 把异常响应转换为异常实例。
     *
     * @param functionCode  原始请求功能码
     * @param exceptionCode 对端返回的异常码
     * @return 异常实例
     */
    public static ModbusException ofFunction(int functionCode, int exceptionCode) {
        return new ModbusException(exceptionCode,
                "Modbus 异常响应: 功能码=0x" + Integer.toHexString(functionCode)
                        + ", 异常码=0x" + Integer.toHexString(exceptionCode)
                        + " (" + describe(exceptionCode) + ")");
    }

    /**
     * 创建异常响应：非法数据值。
     *
     * @param message 异常描述
     * @return 异常实例
     */
    public static ModbusException illegalDataValue(String message) {
        return new ModbusException(ILLEGAL_DATA_VALUE, message);
    }

    /**
     * 创建异常响应：非法数据地址。
     *
     * @param message 异常描述
     * @return 异常实例
     */
    public static ModbusException illegalDataAddress(String message) {
        return new ModbusException(ILLEGAL_DATA_ADDRESS, message);
    }

    /**
     * 把异常码翻译为中文说明。
     *
     * @param exceptionCode 异常码
     * @return 说明文本
     */
    public static String describe(int exceptionCode) {
        return switch (exceptionCode) {
            case ILLEGAL_FUNCTION -> "非法功能码";
            case ILLEGAL_DATA_ADDRESS -> "非法数据地址";
            case ILLEGAL_DATA_VALUE -> "非法数据值";
            case SLAVE_DEVICE_FAILURE -> "从站设备故障";
            case ACKNOWLEDGE -> "请求已受理";
            case SLAVE_DEVICE_BUSY -> "从站设备忙";
            case GATEWAY_PATH_UNAVAILABLE -> "网关路径不可用";
            case GATEWAY_TARGET_NO_RESPONSE -> "网关目标设备无响应";
            default -> "未知异常码";
        };
    }
}
