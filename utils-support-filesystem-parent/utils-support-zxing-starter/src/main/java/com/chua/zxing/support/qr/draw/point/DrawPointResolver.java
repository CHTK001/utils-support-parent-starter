package com.chua.zxing.support.qr.draw.point;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* drawpoint解析器接口用于定义绘制点的操作。
* 规定了一个绘制图形的方法，需要由实现类具体实现绘制的逻辑。
*
* @author CH
* @since 4.0.0.42
*/
public interface DrawPointResolver {

    /**
    * 绘制QR码。
    * 使用提供的QR码配置、图形上下文、位矩阵以及边距信息，在指定的位置绘制QR码。
    *
    * @param x 绘制QR码的起始x坐标
    * @param y 绘制QR码的起始y坐标
     */
    void draw(int x, int y);

    /**
    * 绘制结束钩子：用于需要二阶段合并/后处理的码点实现。
    * 默认空实现，避免对现有实现产生影响。
     */
    default void finish() {
    }
}
