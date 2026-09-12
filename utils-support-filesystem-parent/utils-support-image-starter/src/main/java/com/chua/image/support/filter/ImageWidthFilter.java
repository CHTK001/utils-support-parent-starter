package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.SpiIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.math.RoundingMode;

import static com.chua.image.support.filter.ImageSizedFilter.zoomByScale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;



/**
 * 大小滤镜
 *
 * @author CH
   * @版本 1.0.0
 * @since 2021/6/11
 */
@EqualsAndHashCode(callSuper = true)
@Data
@SpiIgnore
@Accessors(chain = true)
@NoArgsConstructor
public class ImageWidthFilter extends AbstractImageFilter {


    /** 目标宽度 */
    private int width = 100;
    /** 目标高度 */
    private int height = 100;

    /**
      * 创建 镜像width过滤器 实例
     * @param width width
     * @param height height
     */
    public ImageWidthFilter(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @Override
    /** 过滤 */
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        
        return zoomBySize(width, height, src);
    
    }


    /**
     * 按比例对图片进行缩放. 检测图片是横图还是竖图
     *
     * @param width  缩放后的宽
     * @param height 缩放后的高
     * @param img    缓冲镜像
     * @return zoomby大小的结果
     */
    public static BufferedImage zoomBySize(int width, int height, BufferedImage img) {
        //横向图
        if (img.getWidth() >= img.getHeight()) {
            double ratio = calculateZoomRatio(width, img.getWidth());
            //获取压缩对象
            BufferedImage newbufferedImage = zoomByScale(ratio, img);
            //当图片大于图片压缩高时 再次缩放
            if (newbufferedImage.getHeight() > height) {
                ratio = calculateZoomRatio(height, newbufferedImage.getHeight());
                return zoomByScale(ratio, img);

            }
            return newbufferedImage;
        }


        //纵向图
        if (img.getWidth() < img.getHeight()) {
            double ratio = calculateZoomRatio(height, img.getHeight());
            //获取压缩对象
            BufferedImage newbufferedImage = zoomByScale(ratio, img);
            //当图片宽大于图片压缩宽时 再次缩放
            if (newbufferedImage.getHeight() > height) {
                ratio = calculateZoomRatio(width, newbufferedImage.getWidth());
                return zoomByScale(ratio, img);
            }

            return newbufferedImage;
        }

        Image img1 = img.getScaledInstance(width, height, Image.SCALE_DEFAULT);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.drawImage(img1, 0, 0, null);

        graphics.dispose();
        return image;
    }

    /**
     * 缩放比率计算
     *
     * @param divisor  divisor
     * @param dividend dividend
     * @return calculateZoomRatio的结果
     */
    public static double calculateZoomRatio(int divisor, int dividend) {
        return BigDecimal.valueOf(divisor).divide(BigDecimal.valueOf(dividend), 6, RoundingMode.HALF_UP).doubleValue();
    }

}

