package com.chua.zxing.support.qr.draw;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
   * draw解析器接口定义了绘图解析器的基本行为。
 * 作为一个契约，规定了实现此接口的类必须提供的方法，以便于在不同上下文中解析和处理绘图操作。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DrawResolver {


    /**
     * 绘制图形的方法。
     */
    void draw();
}
