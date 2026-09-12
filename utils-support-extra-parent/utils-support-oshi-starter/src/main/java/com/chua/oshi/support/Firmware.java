package com.chua.oshi.support;

import lombok.Data;

/**
* BIOS/固件信息实体类。
*
* @author CH
* @since 4.0.0
 */
@Data
public class Firmware {

    /**
    * 固件供应商。
     */
    private String vendor;

    /**
    * 固件名称。
     */
    private String name;

    /**
    * 固件版本。
     */
    private String version;

    /**
    * 发布日期。
     */
    private String releaseDate;

    /**
    * BIOS 大小（字节）。
     */
    private long biosSize;
}