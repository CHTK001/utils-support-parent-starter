package com.chua.oshi.support;

import lombok.Data;

/**
 * 传感器信息实体类。
 * <p>
 * 封装系统传感器的温度、风扇转速和电压读数。
 *
 * @author CH
 */
@Data
public class Sensor {

    /**
     * 传感器名称（如 "CPU Temperature", "Fan 1"）。
     */
    private String name;

    /**
     * 当前温度（摄氏度），仅当 type == TEMPERATURE 时有效。
     */
    private double currentTemperature;

    /**
     * 最高温度（摄氏度），仅当 type == TEMPERATURE 时有效。
     */
    private double maxTemperature;

    /**
     * 当前风扇转速（RPM），仅当 type == FAN_SPEED 时有效。
     */
    private double currentFanSpeed;

    /**
     * 最高风扇转速（RPM），仅当 type == FAN_SPEED 时有效。
     */
    private double maxFanSpeed;

    /**
     * 当前电压（伏特），仅当 type == VOLTAGE 时有效。
     */
    private double currentVoltage;

    /**
     * 传感器类型（TEMPERATURE / FAN_SPEED / VOLTAGE）。
     */
    private String type;
}