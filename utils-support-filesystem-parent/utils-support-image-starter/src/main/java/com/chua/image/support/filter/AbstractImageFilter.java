package com.chua.image.support.filter;

import com.chua.common.support.constant.ImageType;
import com.chua.common.support.image.filter.ImageFilter;
import com.chua.common.support.image.gif.GifDecoder;
import com.chua.common.support.image.gif.GifEncoder;
import com.chua.common.support.utils.StringUtils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import com.chua.common.support.utils.RgbOptions;


/**
 * 图像滤镜抽象基类
 *
 * 提供图像滤镜处理的基础实现，包含图像数据处理、颜色空间转换、
 * 像素操作等通用功能。所有具体的图像滤镜都应继承此类。
 *
 * 主要功能：
 * - 图像数据初始化和预处理
 * - RGB 和 HSL 颜色空间转换
 * - 像素级别的读写操作
 * - GIF 动画处理支持
 * - 图像格式识别和转换
 *
 * 技术特点：
 * - 支持多种图像格式（JPEG、PNG、GIF等）
 * - 提供高效的像素操作方法
 * - 内置颜色空间转换算法
 * - 支持动态图像处理
 *
 * <h3>继承本类的子类</h3>
 * <ul>
 *   <li>{@link AbstractImagePointFilter}：逐像素点滤镜基类（filterRgb 抽象方法）</li>
 *   <li>{@link AbstractImageClientFilter}：AI 客户端滤镜基类（注入 ImageClient）</li>
 *   <li>各具体滤镜：{@link ImageMosaicFilter}、{@link MosaicArtImageFilter}、
 *       {@link PixelStyleImageFilter}、{@link BscAdjustImageFilter} 等</li>
 * </ul>
 *
 * <h3>子类实现指南</h3>
 * <ol>
 *   <li>实现 {@link #filter(BufferedImage, BufferedImage)}：核心滤镜逻辑。
 *       src 为源图，dst 可能为 null（由本类创建兼容目标图），返回处理结果。</li>
 *   <li>可选重写 {@link #getImageFormat()}：决定流式输出的编码格式（默认 null=按 name）。</li>
 *   <li>可选重写 {@link #getImageFormat(String)}：按文件名推断格式（如 .jpg→jpeg）。</li>
 *   <li>使用受保护字段 {@code width/height/rArr/gArr/bArr}：由
 *       {@link #initial(BufferedImage)} 在 {@link #converter(BufferedImage)} 时填充。</li>
 * </ol>
 *
 * <h3>典型用法（调用方视角）</h3>
 * <pre>{@code
 * // 单图转换
 * BufferedImage out = filter.converter(srcBufferedImage);
 *
 * // 流式转换（自动识别 GIF 逐帧处理）
 * OutputStream out = filter.converter(imageInputStream);
 *
 * // 指定输出格式
 * filter.getImageFormat("a.jpg");  // 后续流式输出为 jpeg
 * }</pre>
 *
 * @author CH
 * @版本 1.0.0
 * @since 2021/6/11
 */
public abstract class AbstractImageFilter implements ImageFilter {

    /**
     * 图像宽度
     */
    protected int width;

    /**
     * 图像高度
     */
    protected int height;

    /**
     * 红色通道数据数组
     */
    protected byte[] rArr;

    /**
     * 绿色通道数据数组
     */
    protected byte[] gArr;

    /**
     * 蓝色通道数据数组
     */
    protected byte[] bArr;

    /**
     * 安全随机数生成器，需要随机效果的滤镜
     */
    protected SecureRandom randomNumbers = new SecureRandom();

    /**
     * 图像格式名称
     */
    private String name;

    /**
     * 常量：1/60，HSL颜色空间转换
     */
    public static final double CLO_60 = 1.0 / 60.0;

    /**
     * 常量：1/255，颜色值归一化
     */
    public static final double CLO_255 = 1.0 / 255.0;

    /**
     * 临时RGB颜色值，颜色空间转换
     */
    public int tr = 0, tg = 0, tb = 0;

    /**
     * 转换缓冲镜像图像
     *
     * @param image 需要处理的缓冲镜像对象
     * @return 处理后的BufferedImage对象
     * @throws IOException 处理过程中可能发生的IO异常
     */
    @Override
    public BufferedImage converter(BufferedImage image) throws IOException {
        initial(image);
        return filter(image, null);
    }

    /**
     * 初始化图像数据
     *
     * 从缓冲镜像中提取像素数据，分离RGB三个颜色通道，
     * 为后续的滤镜处理做准备。
     *
     * @param image 待处理的图像对象
     */
    protected void initial(BufferedImage image) {
        width = image.getWidth();
        height = image.getHeight();
        int[] input = com.chua.common.support.utils.BufferedImageUtils.getRgb(new com.chua.common.support.utils.RgbOptions(image, 0, 0, width, height, null));
        int size = width * height;
        rArr = new byte[size];
        gArr = new byte[size];
        bArr = new byte[size];
        backFillData(input);
    }

    /**
     * 填充RGB颜色通道数据
     *
     * 将ARGB格式的像素数据分离为独立的RGB三个颜色通道数组，
     * 便于后续的颜色处理和滤镜算法应用。
     *
     * @param input ARGB格式的像素数据数组
     */
    private void backFillData(int[] input) {
        int c = 0, r = 0, g = 0, b = 0;
        int length = input.length;
        for (int i = 0; i < length; i++) {
            c = input[i];
            r = (c >> 16) & 0xff;
            g = (c >> 8) & 0xff;
            b = c & 0xff;
            rArr[i] = (byte) r;
            gArr[i] = (byte) g;
            bArr[i] = (byte) b;
        }
    }


    /**
     * 转换输入流形式的图像数据
     *
     * 支持静态图像和GIF动画的处理。对于GIF格式，会逐帧处理并重新编码；
     * 对于其他格式，直接进行滤镜处理。
     *
     * @param image 输入流形式的图像数据
     * @return 处理后的图像数据输出流
     * @throws IOException 处理过程中可能发生的IO异常
     */
    @Override
    public OutputStream converter(InputStream image) throws IOException {
        try(InputStream is = image) {
            String imageFormat = getImageFormat();
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            // 处理GIF动画
            if (ImageType.GIF.name().equalsIgnoreCase(imageFormat) && !StringUtils.isNullOrEmpty(imageFormat)) {
                GifDecoder gifDecoder = new GifDecoder();
                GifEncoder gifEncoder = new GifEncoder();

                gifDecoder.read(image);

                gifEncoder.setRepeat(gifDecoder.getLoopCount());
                gifEncoder.start(out);

                int frameCount = gifDecoder.getFrameCount();
                for (int i = 0; i < frameCount; i++) {
                    BufferedImage frame = gifDecoder.getFrame(i);
                    gifEncoder.setDelay(gifDecoder.getDelay(i));
                    gifEncoder.addFrame(converter(frame));
                }
                gifEncoder.finish();
            } else {
                // 处理静态图像
                BufferedImage read = ImageIO.read(image);
                BufferedImage bufferedImage = converter(read);
                ImageIO.write(bufferedImage, getImageFormat(), out);
            }
            return out;
        }
    }

    /**
     * 设置并获取图像格式
     *
     * @param name 图像格式名称
     * @return 图像格式名称
     */
    @Override
    public String getImageFormat(String name) {
        this.name = name;
        return name;
    }

    /**
     * 获取当前设置的图像格式
     *
     * @return 图像格式名称
     */
    @Override
    public String getImageFormat() {
        
        return name;
    
    }

    /**
     * 创建兼容的目标图像
     *
     * 根据源图像和指定的颜色模型创建一个兼容的目标图像。
     * 如果未指定颜色模型，则使用源图像的颜色模型。
     *
     * @param src        源图像
     * @param colorModel 目标颜色模型，可以为空
     * @return 新创建的兼容图像
     */
    public BufferedImage createCompatibleDestImage(BufferedImage src, ColorModel colorModel) {
        if (colorModel == null) {
            colorModel = src.getColorModel();
        }
        return new BufferedImage(colorModel, colorModel.createCompatibleWritableRaster(src.getWidth(), src.getHeight()), colorModel.isAlphaPremultiplied(), null);
    }

    /**
     * 抽象滤镜处理方法
     *
     * 具体的滤镜效果由子类实现。此方法定义了滤镜处理的标准接口。
     *
     * @param src 源图像
     * @param dst 目标图像，可以为空
     * @return 处理后的图像
     */
    abstract public BufferedImage filter(BufferedImage src, BufferedImage dst);

    /**
     * 获取图像的边界矩形
     *
     * @param src 源图像
     * @return 图像的边界矩形
     */
    public Rectangle2D getBounds2D(BufferedImage src) {
        return new Rectangle(0, 0, src.getWidth(), src.getHeight());
    }

    /**
     * 获取变换后的点坐标
     *
     * 对于大多数滤镜，点的位置不会改变，直接复制坐标。
     * 某些几何变换滤镜可能会重写此方法。
     *
     * @param srcPt 源点坐标
     * @param dstPt 目标点坐标，可以为空
     * @return 变换后的点坐标
     */
    public Point2D getPoint2D(Point2D srcPt, Point2D dstPt) {
        if (dstPt == null) {
            dstPt = new Point2D.Double();
        }
        dstPt.setLocation(srcPt.getX(), srcPt.getY());
        return dstPt;
    }

    /**
     * 高效获取图像ARGB像素数据
     *
     * 这是一个优化的像素获取方法，对于INT_ARGB和INT_RGB类型的图像，
     * 直接从光栅数据获取，避免缓冲镜像.获取rgb的性能损失。
     *
     * @param image  缓冲镜像对象
     * @param x      像素区域的左上角X坐标
     * @param y      像素区域的左上角Y坐标
     * @param width  像素区域的宽度
     * @param height 像素区域的高度
     * @param pixels 存储像素数据的数组，可以为空
     * @return ARGB格式的像素数据数组
     * @see #setRgb
     */
    public int[] getRgb(BufferedImage image, int x, int y, int width, int height, int[] pixels) {
        int type = image.getType();
        if (type == BufferedImage.TYPE_INT_ARGB || type == BufferedImage.TYPE_INT_RGB) {
            return (int[]) image.getRaster().getDataElements(x, y, width, height, pixels);
        }
        return image.getRGB(x, y, width, height, pixels, 0, width);
    }

    /**
     * 高效设置图像ARGB像素数据
     *
     * 这是一个优化的像素设置方法，对于INT_ARGB和INT_RGB类型的图像，
     * 直接设置光栅数据，避免缓冲镜像.设置rgb的性能损失。
     *
     * @param image  缓冲镜像对象
     * @param x      像素区域的左上角X坐标
     * @param y      像素区域的左上角Y坐标
     * @param width  像素区域的宽度
     * @param height 像素区域的高度
     * @param pixels ARGB格式的像素数据数组
     * @see #getRgb
     */
    public void setRgb(BufferedImage image, int x, int y, int width, int height, int[] pixels) {
        int type = image.getType();
        if (type == BufferedImage.TYPE_INT_ARGB || type == BufferedImage.TYPE_INT_RGB) {
            image.getRaster().setDataElements(x, y, width, height, pixels);
        } else {
            image.setRGB(x, y, width, height, pixels, 0, width);
        }
    }

    /**
     * 根据索引获取颜色通道字节数组
     *
     * @param index 颜色通道索引：0-红色，1-绿色，2-蓝色
     * @return 对应颜色通道的字节数组
     * @throws IllegalArgumentException 当索引值无效时抛出异常
     */
    public byte[] toColorByte(int index) {
        if (index == 0) {
            return rArr;
        } else if (index == 1) {
            return gArr;
        } else if (index == 2) {
            return bArr;
        } else {
            throw new IllegalArgumentException("无效的颜色通道索引: " + index + "，有效值为0(红色)、1(绿色)、2(蓝色)");
        }
    }

    /**
     * 将RGB颜色通道数据转换为缓冲镜像
     *
     * @return 根据当前RGB数据创建的BufferedImage对象
     */
    public BufferedImage toBitmap() {
        int[] pixels = new int[width * height];
        BufferedImage bitmap = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        setRgb(width, height, pixels, toColorByte(0), toColorByte(1), toColorByte(2));
        setRgb(bitmap, 0, 0, width, height, pixels);
        return bitmap;
    }

    /**
     * 设置RGB颜色通道数据
     *
     * @param red   红色通道数据
     * @param green 绿色通道数据
     * @param blue  蓝色通道数据
     */
    public void putRgb(byte[] red, byte[] green, byte[] blue) {
        System.arraycopy(red, 0, rArr, 0, red.length);
        System.arraycopy(green, 0, gArr, 0, green.length);
        System.arraycopy(blue, 0, bArr, 0, blue.length);
    }

    /**
     * 将RGB字节数组合并为ARGB像素数组
     *
     * @param width  图像宽度
     * @param height 图像高度
     * @param pixels 输出的像素数组
     * @param r      红色通道数据
     * @param g      绿色通道数据
     * @param b      蓝色通道数据
     */
    public void setRgb(int width, int height, int[] pixels, byte[] r, byte[] g, byte[] b) {
        for (int i = 0; i < width * height; i++) {
            pixels[i] = 0xff000000 | ((r[i] & 0xff) << 16) | ((g[i] & 0xff) << 8) | b[i] & 0xff;
        }
    }


    /**
     * RGB色彩空间转换为HSL色彩空间
     *
     * 将RGB颜色值转换为HSL（色相、饱和度、亮度）颜色空间。
     * HSL颜色空间更适合进行颜色调整和滤镜效果处理。
     *
     * @param hsl RGB颜色值数组，格式为[R, G, B]，取值范围0-255
     * @return HSL颜色值数组，格式为[H, S, L]，其中H取值0-360，S和L取值0-255
     */
    public double[] rgb2Hsl(int[] hsl) {
        double min, max, dif, sum;
        double f1, f2;
        double h, s, l;
        double[] hsl1 = {0.0, 0.0, 0.0};

        // 获取RGB分量
        tr = hsl[0];
        tg = hsl[1];
        tb = hsl[2];

        // 找到最小值
        min = tr;
        if (tg < min) {
            min = tg;
        }
        if (tb < min) {
            min = tb;
        }

        // 找到最大值并确定主色调
        max = tr;
        f1 = 0.0;
        f2 = tg - tb;
        if (tg > max) {
            max = tg;
            // = 120.0;  // 绿色主导
            f1 = 120.0;
            f2 = tb - tr;
        }
        if (tb > max) {
            max = tb;
            // = 240.0;  // 蓝色主导
            f1 = 240.0;
            f2 = tr - tg;
        }

        dif = max - min;
        sum = max + min;
        l = sum / 2.0;

        double f127 = 127.5D;
        if (dif == 0) {
            // 灰色，无色相和饱和度
            h = 0.0;
            s = 0.0;
        } else if (l < f127) {
            s = 255.0 * dif / sum;
        } else {
            s = 255.0 * dif / (510.0 - sum);
        }

        // 计算色相
        h = (f1 + 60.0 * f2) / dif;
        if (h < 0.0) {
            h += 360.0;
        }
        double f360 = 360.0D;
        if (h > f360) {
            h -= 360.0;
        }

        // 色相 (0-360)
        hsl1[0] = h;
        // 饱和度 (0-255)
        hsl1[1] = s;
        // 亮度 (0-255)
        hsl1[2] = l;
        return hsl1;
    }

    /**
     * HSL色彩空间转换为RGB色彩空间
     *
     * 将HSL（色相、饱和度、亮度）颜色值转换回RGB颜色空间。
     * 这是rgb2Hsl方法的逆向转换。
     *
     * @param hsl HSL颜色值数组，格式为[H, S, L]，其中H取值0-360，S和L取值0-255
     * @return RGB颜色值数组，格式为[R, G, B]，取值范围0-255
     */
    public int[] hsl2Rgb(double[] hsl) {
        double h, s, l;
        // [0];  // 色相
        h = hsl[0];
        // [1];  // 饱和度
        // 饱和度 (0-255)
        s = hsl[1];
        // 亮度 (0-255)
        l = hsl[2];
        int[] rgb1 = {0, 0, 0};
        double v1, v2, v3, h1;

        // HSL 转换为 RGB
        if (s == 0) {
            // 无饱和度，为灰色
            tr = (int) l;
            tg = (int) l;
            tb = (int) l;
        } else {
            double f127 = 127.5D;
            if (l < f127) {
                v2 = CLO_255 / (255 + s);
            } else {
                v2 = l + s - CLO_255 * s * l;
            }
            v1 = 2 * l - v2;
            v3 = v2 - v1;

            // 计算红色分量
            h1 = h + 120.0;
            double f360 = 360.0D;
            if (h1 >= f360) {
                h1 -= 360.0;
            }
            double f60 = 60.0D, f180 = 180.0D, f240 = 240.0D;
            if (h1 < f60) {
                tr = (int) (v1 + v3 * h1 * CLO_60);
            } else if (h1 < f180) {
                tr = (int) v2;
            } else if (h1 < f240) {
                tr = (int) (v1 + v3 * (4 - h1 * CLO_60));
            } else {
                tr = (int) v1;
            }

            // 计算绿色分量
            h1 = h;
            if (h1 < f60) {
                tg = (int) (v1 + v3 * h1 * CLO_60);
            } else if (h1 < f180) {
                tg = (int) v2;
            } else if (h1 < f240) {
                tg = (int) (v1 + v3 * (4 - h1 * CLO_60));
            } else {
                tg = (int) v1;
            }

            // 计算蓝色分量
            h1 = h - 120.0;
            if (h1 < 0.0) {
                h1 += 360.0;
            }
            if (h1 < f60) {
                tb = (int) (v1 + v3 * h1 * CLO_60);
            } else if (h1 < f180) {
                tb = (int) v2;
            } else if (h1 < f240) {
                tb = (int) (v1 + v3 * (4 - h1 * CLO_60));
            } else {
                tb = (int) v1;
            }
        }

        // tr;  // 红色分量
        rgb1[0] = tr;
        // tg;  // 绿色分量
        rgb1[1] = tg;
        // tb;  // 蓝色分量
        rgb1[2] = tb;
        return rgb1;
    }

    /**
     * 创建兼容的目标图像
     *
     * 根据源图像的尺寸创建一个新的RGB格式图像。
     *
     * @param src  源图像
     * @param dest 目标图像（此参数未使用）
     * @return 新创建的RGB格式图像
     */
    public BufferedImage creatCompatibleDestImage(BufferedImage src, BufferedImage dest) {
        return new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
    }
}

