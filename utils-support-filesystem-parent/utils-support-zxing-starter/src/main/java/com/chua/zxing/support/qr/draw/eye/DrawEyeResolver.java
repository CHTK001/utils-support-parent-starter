package com.chua.zxing.support.qr.draw.eye;

import com.github.hui.quick.plugin.qrcode.helper.QrCodeRenderHelper;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* draweye解析器接口定义了绘制眼睛图形的方法。
* @author CH
* @since 4.0.0.42
 */
public interface DrawEyeResolver {

    /**
    * 绘制图形的方法。没有参数，也没有返回值。
    * 实现的类需要提供具体的绘制眼睛图形的实现。
    * @param x 方法入参 x
    * @param y 方法入参 y
    * @param detectLocation 方法入参 detectLocation
    */
    void draw(int x, int y,QrCodeRenderHelper.DetectLocation detectLocation);


    /**
    * 结束操作的函数。
    */
    void finish();

}

