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
 * 按比例缩放滤镜
 *
 * 将图像等比缩小到指定比例，输出尺寸为原图宽高乘以缩放系数。
 * 使用 {@link Image#SCALED_INSTANCE} 缩放算法（双线性插值），
 * 适合图片压缩/缩略图生成等场景。
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 缩小到 50%
 * BufferedImage thumb = new ImageSizedFilter().setSize(0.5).converter(src);
 *
 * // 缩小到 25% 并导出
 * OutputStream out = new ImageSizedFilter(0.25).converter(inputStream);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>size</b>（默认 0.5）：缩放比例。0.5 表示缩小一半；
 *       1.0 表示原尺寸；大于 1.0 表示放大（放大效果会变模糊，不推荐）。</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>等比缩放，宽高同时按 size 缩小。</li>
 *   <li>输出为 TYPE_INT_RGB，会丢失原图的透明度（Alpha 通道）。</li>
 *   <li>若需要"按宽度/高度适配缩放"，请使用 {@link ImageWidthFilter}。</li>
 * </ul>
 *
 * @author CH
 * @since 2021/6/11
 */
@EqualsAndHashCode(callSuper = true)
@Data
@SpiDescribe("大小滤镜")
@Spi("size")
@Accessors(chain = true)
@NoArgsConstructor
@SpiIgnore
public class ImageSizedFilter extends AbstractImageFilter {


    /**
     * 缩放比例
    */
    private double size = 0.5d;

    /**
     * 创建 镜像大小过滤器 实例
     * @param size 大小
     */
    public ImageSizedFilter(double size) {
        this.size = size;
    }

    @Override
    /**
     * 过滤
    */
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        
        return zoomByScale(size, src);
    
    }


    /**
     * 按比例缩放
     *
     * 将图像宽高同时乘以缩放系数，输出新尺寸图像。
     * 使用 Image.SCALE_DEFAULT（双线性插值）绘制。
     *
     * @param scale 缩放比率（0.5 = 缩小一半）
     * @param img   缓冲镜像
     * @return 缩放后的 TYPE_INT_RGB 图像
     */
    public static BufferedImage zoomByScale(double scale, BufferedImage img) {
        //获取缩放后的长和宽
        int width = (int) (scale * img.getWidth());
        int height = (int) (scale * img.getHeight());
 // 获取缩放后的镜像对象
        Image img1 = img.getScaledInstance(width, height, Image.SCALE_DEFAULT);
 // 新建一个和镜像对象相同大小的画布
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        //获取画笔
        Graphics2D graphics = image.createGraphics();
 // 将镜像对象画在画布上,最后一个参数,镜像observer:接收有关 镜像 信息通知的异步更新接口,没用到直接传空
        graphics.drawImage(img1, 0, 0, null);
        //释放资源
        graphics.dispose();
        return image;
    }


}

