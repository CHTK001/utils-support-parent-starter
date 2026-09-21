package com.chua.oshi.support;

/**
 * 传感器内部信息类，封装单个传感器的读数。
 *
 * @param name 名称
 * @param type 类型
 * @param currentTemperature 当前temperature
 * @param maxTemperature 最大值temperature
 * @param currentFanSpeed 当前fanspeed
 * @param maxFanSpeed 最大值fanspeed
 * @param currentVoltage 当前voltage
 *
 * @author CH
 * @since 4.0.0
 */
public record SensorInfo(
        String name,
        String type,
        double currentTemperature,
        double maxTemperature,
        double currentFanSpeed,
        double maxFanSpeed,
        double currentVoltage
) {
}