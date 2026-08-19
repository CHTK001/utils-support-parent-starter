package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 九宫格图像滤镜
 *
 * 将图像分割成九个等大的区域，并在每个区域之间添加分割线，
 * 创建类似九宫格或网格的视觉效果。常用于图像布局设计和艺术创作。
 *
 * 技术原理：
 * - 将图像按3x3网格进行分割
 * - 在网格线位置绘制分割线
 * - 保持原图像内容，仅添加网格线条
 * - 支持自定义线条粗细
 *
 * 算法流程：
 * 1. 计算图像的三等分点位置
 * 2. 在水平和垂直方向绘制分割线
 * 3. 使用指定粗细的线条
 * 4. 保持原图像的其他部分不变
 *
 * 视觉效果：
 * - 创建规整的九宫格布局
 * - 增强图像的结构感
 * - 适合摄影构图参考线
 * - 可用于艺术设计效果
 *
 * 应用场景：
 * - 摄影构图：九宫格构图法参考线
 * - 图像设计：创建网格布局效果
 * - 艺术创作：现代艺术风格处理
 * - 教学演示：构图原理展示
 * - 图像分析：区域划分和标记
 *
 * 参数说明：
 * - solid: 分割线的粗细，单位为像素
 *   - 1-3: 细线条，适合精细效果
 *   - 4-8: 中等线条，适合一般使用
 *   - 9+: 粗线条，适合强调效果
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@Spi("Sudoku")
@SpiDescribe("九宫格网格滤镜")
public class SudokuImageFilter extends AbstractImageFilter{

    /**
     * 分割线的粗细（像素）
     */
    private final int solid;

    /**
     * 默认构造函数
     *
     * 使用默认的线条粗细（5像素）创建九宫格滤镜。
     */
    public SudokuImageFilter() {
        this(5);
    }

    /**
     * 带参数的构造函数
     *
     * @param solid 分割线的粗细，单位为像素，建议范围1-20
     */
    public SudokuImageFilter(int solid) {
        this.solid = solid;
    }

    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int smallWidth = width / 3 ;
        int smallHeight = height / 3 ;
        BufferedImage newBufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics graphics = newBufferedImage.getGraphics();
        graphics.setColor(new Color(255, 255, 255));
        graphics.fillRect(0, 0, width, height);
        for(int i = 0 ; i < 3; i++) {
            for(int j = 0 ; j < 3; j++) {
                int startX = j * smallWidth;
                int startY = i * smallHeight;
                BufferedImage subimage = src.getSubimage(startX, startY, smallWidth - solid, smallHeight - solid);
                graphics.drawImage(subimage, startX, startY, null);
            }
        }
        graphics.dispose();
        return newBufferedImage;
    }
}