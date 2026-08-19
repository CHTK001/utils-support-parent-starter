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
 * 澶у皬婊ら暅
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@EqualsAndHashCode(callSuper = true)
@Data
@SpiIgnore
@Accessors(chain = true)
@NoArgsConstructor
public class ImageWidthFilter extends AbstractImageFilter {


    /** 瀹藉害 */
    private int width = 100;
    /** 楂樺害 */
    private int height = 100;

    public ImageWidthFilter(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        
        return zoomBySize(width, height, src);
    
    }


    /**
     * 鎸夋瘮渚嬪鍥剧墖杩涜缂╂斁. 妫€娴嬪浘鐗囨槸妯浘杩樻槸绔栧浘
     *
     * @param width  缂╂斁鍚庣殑瀹?
     * @param height 缂╂斁鍚庣殑楂?
     * @param img    BufferedImage
     */
    public static BufferedImage zoomBySize(int width, int height, BufferedImage img) {
        //妯悜鍥?
        if (img.getWidth() >= img.getHeight()) {
            double ratio = calculateZoomRatio(width, img.getWidth());
            //鑾峰彇鍘嬬缉瀵硅薄
            BufferedImage newbufferedImage = zoomByScale(ratio, img);
            //褰撳浘鐗囧ぇ浜庡浘鐗囧帇缂╅珮鏃?鍐嶆缂╂斁
            if (newbufferedImage.getHeight() > height) {
                ratio = calculateZoomRatio(height, newbufferedImage.getHeight());
                return zoomByScale(ratio, img);

            }
            return newbufferedImage;
        }


        //绾靛悜鍥?
        if (img.getWidth() < img.getHeight()) {
            double ratio = calculateZoomRatio(height, img.getHeight());
            //鑾峰彇鍘嬬缉瀵硅薄
            BufferedImage newbufferedImage = zoomByScale(ratio, img);
            //褰撳浘鐗囧澶т簬鍥剧墖鍘嬬缉瀹芥椂 鍐嶆缂╂斁
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
     * 缂╂斁姣旂巼璁＄畻
     *
     * @param divisor  divisor
     * @param dividend dividend
     */
    public static double calculateZoomRatio(int divisor, int dividend) {
        return BigDecimal.valueOf(divisor).divide(BigDecimal.valueOf(dividend), 6, RoundingMode.HALF_UP).doubleValue();
    }

}

