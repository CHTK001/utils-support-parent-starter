package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.*;
import java.awt.image.BufferedImage;

import static java.awt.image.BufferedImage.TYPE_INT_RGB;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 马赛克图像滤镜
 *
 * 实现马赛克效果的图像滤镜，将图像分割成规则的矩形块，
 * 每个块使用其中心像素的颜色进行填充，产生像素化的视觉效果。
 *
 * 技术原理：
 * - 将图像按指定大小分割成矩形网格
 * - 计算每个网格的中心像素位置
 * - 使用中心像素的颜色填充整个网格
 * - 处理边界网格的特殊情况
 *
 * 视觉效果：
 * - 降低图像分辨率和细节
 * - 产生像素化的艺术效果
 * - 可调节的马赛克块大小
 * - 保持图像的整体色彩和构图
 *
 * 应用场景：
 * - 隐私保护：模糊敏感信息
 * - 艺术效果：创建像素艺术风格
 * - 游戏开发：复古像素游戏风格
 * - 图像压缩：极度压缩的预览效果
 * - 创意设计：现代数字艺术效果
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认 8x8 马赛克
 * BufferedImage mosaic = new ImageMosaicFilter().converter(src);
 *
 * // 指定块大小 16
 * BufferedImage mosaic = new ImageMosaicFilter(16).converter(src);
 *
 * // 链式
 * BufferedImage mosaic = new ImageMosaicFilter().setSize(32).converter(src);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>size</b>（默认 8）：马赛克块的边长（像素）。值越大块越粗；
 *       建议 2-64。输出图与原图同尺寸。</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 *   <li>若想用"目录里的图片"替代马赛克块（贴图马赛克艺术），
 *       请使用 {@link MosaicArtImageFilter}；
 *       若想要"像素游戏风"（低色数+复古调色板），请使用 {@link PixelStyleImageFilter}。</li>
 * </ul>
 *
 * @author CH
 * @版本 1.0.0
 * @since 2021/6/11
 */
@EqualsAndHashCode(callSuper = true)
@Data
@SpiDescribe("马赛克像素化滤镜")
@Spi("mosaic")
@Accessors(chain = true)
public class ImageMosaicFilter extends AbstractImageFilter {

    /**
    * 马赛克块的大小（像素），默认为8x8像素
    */
    private int size = 8;

    /**
    * 默认构造函数，使用默认的马赛克块大小（8像素）
    */
    public ImageMosaicFilter() {
    }

    /**
    * 构造函数，指定马赛克块大小
    *
    * @param size 马赛克块的大小（像素），必须大于0
    */
    public ImageMosaicFilter(int size) {
        this.size = size;
    }

    /**
    * 执行马赛克滤镜处理
    *
    * 将输入图像分割成规则的矩形网格，每个网格使用其中心像素的颜色
    * 进行填充，从而产生马赛克效果。处理边界网格的特殊情况。
    *
    * @param src    源图像
    * @param image1 目标图像（此参数未使用）
    * @return 应用马赛克效果后的图像，如果参数无效则返回原图像
    */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage image1) {
        BufferedImage mosaicImage = new BufferedImage(src.getWidth(), src.getHeight(), TYPE_INT_RGB);

        // 验证马赛克块大小的有效性
        if (src.getWidth() < size || src.getHeight() < size || size <= 0) {
            // turn src; // 参数无效时返回原图像
            
        }

        // 计算水平方向的网格数量
        int xCount = 0;
        // 计算垂直方向的网格数量
        int yCount = 0;

        if (src.getWidth() % size == 0) {
            xCount = src.getWidth() / size;
        } else {
            // 处理不能整除的情况
            xCount = src.getWidth() / size + 1;
        }

        if (src.getHeight() % size == 0) {
            yCount = src.getHeight() / size;
        } else {
            // 处理不能整除的情况
            yCount = src.getHeight() / size + 1;
        }

        // 当前绘制位置坐标
        int x = 0;
        int y = 0;

        // 获取图形上下文进行绘制
        Graphics graphics = mosaicImage.getGraphics();

        // 遍历所有网格进行马赛克处理
        for (int i = 0; i < xCount; i++) {
            for (int j = 0; j < yCount; j++) {
                // 当前马赛克块的实际大小
                int blockWidth = size;
                int blockHeight = size;

 // 处理边界块：最后一行或最后一列可能不足一个完整的大小
                if (i == xCount - 1) {
                    blockWidth = src.getWidth() - x;
                }
                if (j == yCount - 1) {
                    blockHeight = src.getHeight() - y;
                }

                // 计算当前块的中心像素坐标
                int centerX = x;
                int centerY = y;

                if (blockWidth % 2 == 0) {
                    centerX += blockWidth / 2;
                } else {
                    centerX += (blockWidth - 1) / 2;
                }

                if (blockHeight % 2 == 0) {
                    centerY += blockHeight / 2;
                } else {
                    centerY += (blockHeight - 1) / 2;
                }

                // 获取中心像素的颜色并填充整个块
                Color centerColor = new Color(src.getRGB(centerX, centerY));
                graphics.setColor(centerColor);
                graphics.fillRect(x, y, blockWidth, blockHeight);

                // 移动到下一个垂直位置
                y = y + size;
            }

            // 重置垂直坐标，移动到下一个水平位置
            y = 0;
            x = x + size;
        }

        // 释放图形资源
        graphics.dispose();
        return mosaicImage;
    }

}
