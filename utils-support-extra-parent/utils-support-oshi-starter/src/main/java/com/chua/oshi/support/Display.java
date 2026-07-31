package com.chua.oshi.support;

import lombok.Data;

/**
 * 显示器信息实体类。
 *
 * @author CH
 */
@Data
public class Display {

    /**
     * 显示器名称。
     */
    private String name;

    /**
     * 显示器厂商。
     */
    private String vendor;

    /**
     * 显示器序列号。
     */
    private String serialNumber;

    /**
     * 当前分辨率宽度（像素）。
     */
    private int width;

    /**
     * 当前分辨率高度（像素）。
     */
    private int height;

    /**
     * 当前位深（如 32）。
     */
    private int bitDepth;

    /**
     * 刷新率（Hz）。
     */
    private int refreshRate;
}