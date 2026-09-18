package com.chua.common.support.image;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.awt.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* 图像
*
* @author CH
* @since 4.0.0.42
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ImagePoint extends Point {

    /** 串行版本UID */
    private static final long serialVersionUID = 1L;
    /** 比率 */
    private double rate;
    /** 宽度 */
    private int width;
    /** 高度 */
    private int height;

    /**
    * 创建 镜像point 实例
    * @param rate rate
    */
    public ImagePoint(double rate) {
        this.rate = rate;
    }

    /**
    * 创建 镜像point 实例
    * @param width width
    * @param height height
    */
    public ImagePoint(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /**
    * 创建 镜像point 实例
    * @param p p
    * @param width width
    * @param height height
    */
    public ImagePoint(Point p, int width, int height) {
        super(p);
        this.width = width;
        this.height = height;
    }

    /**
    * 创建 镜像point 实例
    * @param x x
    * @param y y
    * @param width width
    * @param height height
    */
    public ImagePoint(int x, int y, int width, int height) {
        super(x, y);
        this.width = width;
        this.height = height;
    }
}
