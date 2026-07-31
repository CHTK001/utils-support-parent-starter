package com.chua.oshi.support;

import lombok.Data;

/**
 * 主板/Baseboard 信息实体类。
 *
 * @author CH
 */
@Data
public class Baseboard {

    /**
     * 主板制造商。
     */
    private String manufacturer;

    /**
     * 主板型号。
     */
    private String model;

    /**
     * 主板版本。
     */
    private String version;

    /**
     * 主板序列号。
     */
    private String serialNumber;
}