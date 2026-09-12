package com.chua.oshi.support;

import lombok.Data;

/**
* 整机系统信息实体类。
* <p>
* 描述计算机整机的制造商、型号、序列号等资产信息。
*
* @author CH
* @since 4.0.0
 */
@Data
public class ComputerSystem {

    /**
    * 系统制造商（如 Dell, Lenovo, Apple）。
     */
    private String manufacturer;

    /**
    * 系统型号（如 XPS 15 9520）。
     */
    private String model;

    /**
    * 系统 UUID。
     */
    private String uuid;

    /**
    * 系统序列号。
     */
    private String serialNumber;
}