package com.chua.common.support.image.png;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
* PNG图片处理常量接口
* 定义了PNG图片的颜色类型、过滤器类型以及APNG（Animated PNG）的处置和混合操作类型
*
* @author CH
* @since 4.0.0.42
*/
public interface PNG {

    /**
    * 灰度颜色类型
    * 表示图片仅使用灰度颜色，即单个灰度通道
    */
    int PNG_COLOR_GRAY = 0;

    /**
    * RGB颜色类型
    * 表示图片使用RGB颜色模型，即三个颜色通道（红、绿、蓝）
    */
    int PNG_COLOR_RGB = 2;

    /**
    * 调色板颜色类型
    * 表示图片使用索引颜色，颜色数量有限且预先定义
    */
    int PNG_COLOR_PALETTE = 3;

    /**
    * 带Alpha通道的灰度颜色类型
    * 表示图片使用灰度颜色并包含一个Alpha透明度通道
    */
    int PNG_COLOR_GRAY_ALPHA = 4;

    /**
    * 带Alpha通道的RGB颜色类型
    * 表示图片使用RGB颜色模型并包含一个Alpha透明度通道
    */
    int PNG_COLOR_RGB_ALPHA = 6;

    /**
    * 无过滤器类型
    * 表示在压缩前不对图片数据进行任何预过滤处理
    */
    int PNG_FILTER_NONE = 0;

    /**
    * SUB过滤器类型
    * 表示使用SUB算法对图片数据进行预过滤，以提高压缩效率
    */
    int PNG_FILTER_SUB = 1;

    /**
    * UP过滤器类型
    * 表示使用UP算法对图片数据进行预过滤，以提高压缩效率
    */
    int PNG_FILTER_UP = 2;

    /**
    * AVERAGE过滤器类型
    * 表示使用AVERAGE算法对图片数据进行预过滤，以提高压缩效率
    */
    int PNG_FILTER_AVERAGE = 3;

    /**
    * PAETH过滤器类型
    * 表示使用PAETH算法对图片数据进行预过滤，以提高压缩效率
    */
    int PNG_FILTER_PAETH = 4;

    /**
    * APNG无处置操作
    * 在APNG（Animated PNG）中，表示不处置当前帧，即保留当前帧的所有像素
    */
    int APNG_DISPOSE_OP_NONE = 0;

    /**
    * APNG背景处置操作
    * 在APNG（Animated PNG）中，表示将当前帧的区域恢复为背景色
    */
    int APNG_DISPOSE_OP_BACKGROUND = 1;

    /**
    * APNG前一帧处置操作
    * 在APNG（Animated PNG）中，表示将当前帧的区域恢复为前一帧的内容
    */
    int APNG_DISPOSE_OP_PREVIOUS = 2;

    /**
    * APNG源混合操作
    * 表示使用源混合模式，即新像素完全覆盖旧像素
    */
    int APNG_BLEND_OP_SOURCE = 0;

    /**
    * APNG过混合操作
    * 表示使用过混合模式，即新像素根据其Alpha通道与旧像素混合
    */
    int APNG_BLEND_OP_OVER = 1;

}
