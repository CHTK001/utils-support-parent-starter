package com.chua.deeplearning.support.core.model;

/**
 * 3D 模型风格
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum Model3DStyle {

    /**
     * 原样（无风格化）
     */
    ORIGINAL("original", "原样"),

    /**
     * 低多边形风格
     */
    LOW_POLY("lowpoly", "低多边形"),

    /**
     * 体素风格
     */
    VOXEL("voxel", "体素"),

    /**
     * 砖块风格
     */
    BRICKS("bricks", "砖块"),

    /**
     * 开放网格风格
     */
    LATTICE("lattice", "开放网格"),

    /**
     * 卡通风格
     */
    CARTOON("cartoon", "卡通"),

    /**
     * 写实风格
     */
    PHOTOREALISTIC("photorealistic", "写实");

    /** 代码 */
    private final String code;
    /** 描述 */
    private final String description;

    Model3DStyle(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 获取风格代码
     *
     * @return 代码
     */
    public String getCode() {
        return code;
    }

    /**
     * 获取风格描述
     *
     * @return 描述
     */
    public String getDescription() {
        return description;
    }
}