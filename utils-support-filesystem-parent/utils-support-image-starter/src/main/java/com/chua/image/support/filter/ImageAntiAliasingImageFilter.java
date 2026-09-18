package com.chua.image.support.filter;


import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.utils.IoUtils;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 抗锯齿滤镜
 *
 * 通过开启 ANTIALIASING / TEXT_ANTIALIASING 渲染提示，
 * 用双线性平滑（Image.SCALE_SMOOTH）重采样整张图，
 * 柔化硬边缘与斜线，减轻像素锯齿感。
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * BufferedImage aa = new ImageAntiAliasingImageFilter().converter(src);
 * }</pre>
 *
 * <h3>效果说明</h3>
 * <ul>
 *   <li>适合"输出到屏幕显示"的场景：斜边/曲线更平滑</li>
 *   <li>会轻微模糊高频细节，<b>不适合</b>像素画/游戏素材
 *       （像素画应保留硬边，参考 {@link MosaicArtImageFilter} 的 smooth=false 模式）</li>
 *   <li>输出尺寸与原图一致，保留原图像素类型（TYPE）</li>
 * </ul>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li>无配置参数（行为固定）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("AntiAliasing")
@SpiDescribe("抗锯齿滤镜")
public class ImageAntiAliasingImageFilter extends AbstractImageFilter {
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        BufferedImage distImage = new BufferedImage(src.getWidth(), src.getHeight(), src.getType());
        Graphics2D graphics2d = null;
        try {
            graphics2d = distImage.createGraphics();
            graphics2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics2d.drawImage(src.getScaledInstance(src.getWidth(), src.getHeight(), Image.SCALE_SMOOTH), 0, 0, null);
        } finally {
            IoUtils.closeQuietly(graphics2d);

        }
        return distImage;
    }
}