package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiIgnore;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 大小滤镜
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@EqualsAndHashCode(callSuper = true)
@Data
@SpiDescribe("大小滤镜")
@Spi("size")
@Accessors(chain = true)
@NoArgsConstructor
@SpiIgnore
public class ImageSizedFilter extends AbstractImageFilter {


    /** 尺寸 */
    private double size = 0.5d;

    public ImageSizedFilter(double size) {
        this.size = size;
    }

    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        
        return zoomByScale(size, src);
    
    }


    /**
     * 按比例对图片进行缩放.
     *
     * @param scale 缩放比率
     * @param img   BufferedImage
     */
    public static BufferedImage zoomByScale(double scale, BufferedImage img) {
        //获取缩放后的长和宽
        int width = (int) (scale * img.getWidth());
        int height = (int) (scale * img.getHeight());
        //获取缩放后的Image对象
        Image img1 = img.getScaledInstance(width, height, Image.SCALE_DEFAULT);
        //新建一个和Image对象相同大小的画布
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        //获取画笔
        Graphics2D graphics = image.createGraphics();
        //将Image对象画在画布上,最后一个参数,ImageObserver:接收有关 Image 信息通知的异步更新接口,没用到直接传空
        graphics.drawImage(img1, 0, 0, null);
        //释放资源
        graphics.dispose();
        return image;
    }


}

