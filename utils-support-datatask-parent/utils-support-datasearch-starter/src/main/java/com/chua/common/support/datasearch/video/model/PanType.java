package com.chua.common.support.datasearch.video.model;

/**
 * 网盘类型ö举
 * ֧持的网盘类型定?
 *
 * @author CH
 * @version 1.0
 * @since 4.0.0.42
 */
public enum PanType {

    BAIDU("baidu", "百度网盘"),
    ALIYUN("aliyun", "阿里云盘"),
    QUARK("quark", "夸克网盘"),
    TIANYI("tianyi", "天翼云盘"),
    UC("uc", "UC网盘"),
    MOBILE("mobile", "移动云盘"),
    PAN_115("115", "115网盘"),
    PIKPAK("pikpak", "PikPak"),
    XUNLEI("xunlei", "Ѹ雷网盘"),
    PAN_123("123", "123网盘"),
    MAGNET("magnet", "磁力链接"),
    ED2K("ed2k", "电¿链接"),
    OTHERS("others", "其他");

    /** 代码 */
    private final String code;
    /** 名称 */
    private final String name;

    PanType(String code, String name) {
        this.code = code;
        this.name = name;
    }

    /** FromCode */
    public static PanType fromCode(String code) {
        if (code == null) {
            return OTHERS;
        }
        for (PanType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return OTHERS;
    }

    /** 获取Code */
    public String getCode() {
        return code;
    }

    /** 获取Name */
    public String getName() {
        return name;
    }

    /** 是否MagnetType */
    public boolean isMagnetType() {
        return this == MAGNET || this == ED2K;
    }

    /** 是否PanType */
    public boolean isPanType() {
        return !isMagnetType() && this != OTHERS;
    }
}

