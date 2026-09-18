package com.chua.common.support.image.png;

import javax.imageio.ImageReadParam;
/**
* png镜像读取参数类是镜像读取参数的子类，专门用于处理PNG图像的读取参数
* 该类提供了一个特定的功能：强制读取IDAT（图像数据）块，即使它在文件的中间位置
*
* @author CH
* @since 4.0.0.42
*/
public final class PNGImageReadParam extends ImageReadParam {

    /**
    * 判断是否强制读取IDAT块
    *
    * @return boolean 表示是否强制读取IDAT块的布尔值
    */
    public boolean isForceReadIDAT() {
        return forceReadIDAT;
    }

    /**
    * 设置是否强制读取IDAT块
    *
    * @param forceReadIDAT 布尔值 表示是否强制读取IDAT块的布尔值
    */
    public void setForceReadIDAT(boolean forceReadIDAT) {
        this.forceReadIDAT = forceReadIDAT;
    }

    /**
    * force读取idat变量用于存储是否强制读取IDAT块的设置默认为false，即不强制读取
    */
    private boolean forceReadIDAT = false;

}
