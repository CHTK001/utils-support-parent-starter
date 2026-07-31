package com.chua.zxing.support.qr.draw.eye;

import com.github.hui.quick.plugin.qrcode.helper.QrCodeRenderHelper;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * DrawEyeResolver接口定义了绘制眼睛图形的方法。
 * @author CH
 * @since 2024/5/30 表示是从2024年5月30日开始提供的。
 */
public interface DrawEyeResolver {

    /**
     * 绘制图形的方法。没有参数，也没有返回值。
     * 实现的类需要提供具体的绘制眼睛图形的实现。
     */
    void draw(int x, int y,QrCodeRenderHelper.DetectLocation detectLocation);


    /**
     * 结束操作的函数。
     */
    void finish();

}

