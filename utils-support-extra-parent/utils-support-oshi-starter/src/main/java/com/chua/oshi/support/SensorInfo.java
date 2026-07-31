package com.chua.oshi.support;

import lombok.Data;

/**
 * 传感器内部信息类，封装单个传感器的读数。
 *
 * @author CH
 */
@Data
public class SensorInfo {

    private String name;
    private String type;
    private double currentTemperature;
    private double maxTemperature;
    private double currentFanSpeed;
    private double maxFanSpeed;
    private double currentVoltage;
}