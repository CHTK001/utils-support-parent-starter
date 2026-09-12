package com.chua.oshi.support;

import lombok.Data;

/**
* 系统信息实体类，用于封装计算机的基本信息。
*
* @author CH
* @since 4.0.0
 */
@Data
public class Sys {

    /**
    * 计算机的 IP 地址。
     */
    private String computerIp;

    /**
    * 操作系统的名称（如 窗口 11, Ubuntu 24.04 等）。
     */
    private String osName;

    /**
    * 操作系统的架构（如 x86_64, arm64 等）。
     */
    private String osArch;
}
