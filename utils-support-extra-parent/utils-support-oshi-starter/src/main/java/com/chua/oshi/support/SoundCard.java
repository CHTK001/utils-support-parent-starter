package com.chua.oshi.support;

import lombok.Data;

/**
 * 声卡信息实体类。
 *
 * @author CH
 */
@Data
public class SoundCard {

    /**
     * 声卡名称。
     */
    private String name;

    /**
     * 声卡厂商。
     */
    private String vendor;

    /**
     * 驱动版本。
     */
    private String driverVersion;
}