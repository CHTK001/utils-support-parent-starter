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
 * 按目标宽高自适应缩放滤镜
 *
 * 将图像缩放到"适配"指定目标宽高范围内：
 * <ul>
 *   <li>横图（宽 ≥ 高）：优先按宽适配，若高度仍超出目标高度则再按高度适配</li>
 *   <li>竖图（高 > 宽）：优先按高适配，若宽度仍超出目标宽度则再按宽度适配</li>
 * </ul>
 * 与 {@link ImageSizedFilter}（纯等比缩小）不同，本滤镜保证输出图
 * 的两个边都不超过目标宽高，适用于"上传缩略图""列表图适配"等场景。
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 缩放到 800x600 以内（保持比例）
 * BufferedImage fit = new ImageWidthFilter(800, 600).converter(src);
 *
 * // 链式设置
 * BufferedImage fit2 = new ImageWidthFilter()
 *         .setWidth(400).setHeight(300).converter(src);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>width</b>（默认 100）：目标宽度上限（像素）</li>
 *   <li><b>height</b>（默认 100）：目标高度上限（像素）</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_RGB，会丢失原图透明度。</li>
 *   <li>原图比目标尺寸更小时，仍会按目标宽高强行放大（SCALE_DEFAULT 插值）。</li>
 * </ul>
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
     * 按目标宽高自适应缩放
     *
     * 横图优先按宽适配、超出再按高适配；竖图反之。
     *
     * @param width  目标宽（像素）
     * @param height 目标高（像素）
     * @param img    缓冲镜像
     * @return 缩放后的 TYPE_INT_RGB 图像
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
     * 计算缩放比率
     *
     * 用 6 位小数精度计算 divisor ÷ dividend，
     * 用于把"目标尺寸"换算成"等比缩放系数"。
     *
     * @param divisor  除数（目标尺寸）
     * @param dividend 被除数（当前尺寸）
     * @return 缩放比率
     */
    public static double calculateZoomRatio(int divisor, int dividend) {
        return BigDecimal.valueOf(divisor).divide(BigDecimal.valueOf(dividend), 6, RoundingMode.HALF_UP).doubleValue();
    }

}

