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
 * 澶у皬婊ら暅
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@EqualsAndHashCode(callSuper = true)
@Data
@SpiDescribe("澶у皬婊ら暅")
@Spi("size")
@Accessors(chain = true)
@NoArgsConstructor
@SpiIgnore
public class ImageSizedFilter extends AbstractImageFilter {


    /** 灏哄 */
    private double size = 0.5d;

    public ImageSizedFilter(double size) {
        this.size = size;
    }

    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        
        return zoomByScale(size, src);
    
    }


    /**
     * 鎸夋瘮渚嬪鍥剧墖杩涜缂╂斁.
     *
     * @param scale 缂╂斁姣旂巼
     * @param img   BufferedImage
     */
    public static BufferedImage zoomByScale(double scale, BufferedImage img) {
        //鑾峰彇缂╂斁鍚庣殑闀垮拰瀹?
        int width = (int) (scale * img.getWidth());
        int height = (int) (scale * img.getHeight());
        //鑾峰彇缂╂斁鍚庣殑Image瀵硅薄
        Image img1 = img.getScaledInstance(width, height, Image.SCALE_DEFAULT);
        //鏂板缓涓€涓拰Image瀵硅薄鐩稿悓澶у皬鐨勭敾甯?
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        //鑾峰彇鐢荤瑪
        Graphics2D graphics = image.createGraphics();
        //灏咺mage瀵硅薄鐢诲湪鐢诲竷涓?鏈€鍚庝竴涓弬鏁?ImageObserver:鎺ユ敹鏈夊叧 Image 淇℃伅閫氱煡鐨勫紓姝ユ洿鏂版帴鍙?娌＄敤鍒扮洿鎺ヤ紶绌?
        graphics.drawImage(img1, 0, 0, null);
        //閲婃斁璧勬簮
        graphics.dispose();
        return image;
    }


}

