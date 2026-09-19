package com.chua.oshi.support;

import lombok.Data;

/**
 * 传感器内部信息类，封装单个传感器的读数。
 *
 * @author CH
 * @since 4.0.0
 */
@Data
public class SensorInfo {

    /** 名称 */
    private String name;
    /** 类型 */
    private String type;
    /** 当前temperature */
    private double currentTemperature;
    /** 最大值temperature */
    private double maxTemperature;
    /** 当前fanspeed */
    private double currentFanSpeed;
    /** 最大值fanspeed */
    private double maxFanSpeed;
    /** 当前voltage */
    private double currentVoltage;
}