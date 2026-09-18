package com.chua.common.support.image.gif;


import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* 动态GIF动画生成器，可生成一个或多个帧的GIF。
*
* <pre>
* Example:
*    AnimatedGifEncoder e = new AnimatedGifEncoder();
*    e.start(outputFileName);
*    e.setDelay(1000);
*    e.addFrame(image1);
*    e.addFrame(image2);
*    e.finish();
* </pre>
* <p>
* 来自：https:
*
* @author Kevin Weiner, FM Software
* @版本 1.03 November 2003
* @since 4.0.0.42
* @author CH
*/
public class GifEncoder {

    /** 宽度 */
    protected int width;
    /** 高度 */
    protected int height;
    /** Transparent */
    protected Color transparent = null;
    /** Transparentexactmatch */
    protected boolean transparentExactMatch = false;
    /** Background */
    protected Color background = null;
    /** Trans索引 */
    protected int transIndex;
    /** Repeat */
    protected int repeat = -1;
    /** 延迟 */
    protected int delay = 0;
    /** 启动 */
    protected boolean started = false;
    /** 出 */
    protected OutputStream out;
    /** 图片 */
    protected BufferedImage image;
    /** Pixels */
    protected byte[] pixels;
    /** Indexedpixels */
    protected byte[] indexedPixels;
    /** 颜色深度 */
    protected int colorDepth;
    /** 颜色TAB */
    protected byte[] colorTab;
    /** Usedentry */
    protected boolean[] usedEntry = new boolean[256];
    /** PAL尺寸 */
    protected int palSize = 7;
    /** Dispose */
    protected int dispose = -1;
    /** 关闭流 */
    protected boolean closeStream = false;
    /** 首个帧 */
    protected boolean firstFrame = true;
    /** 尺寸集合 */
    protected boolean sizeSet = false;
    /** 示例 */
    protected int sample = 10;

    /**
    * 设置每一帧的间隔时间
    * 设置 the 延迟 时间 between each 帧, 或 改变 it
    * for subsequent 帧 (applies 转为 最后一个 帧 添加).
    *
    * @param ms 间隔时间，单位毫秒
    */
    public void setDelay(int ms) {
        delay = Math.round(ms / 10.0f);
    }

    /**
    * 设置 the GIF 帧 disposal 编码 for the 最后一个 添加 帧
    * 和 任意 subsequent 帧.  默认hotp生成器 是否 0 if no transparent
    * color 是否包含 been 设置, otherwise 2.
    *
    * @param code int disposal 编码.
    */
    public void setDispose(int code) {
        if (code >= 0) {
            dispose = code;
        }
    }

    /**
    * 设置 the 数字 的 时间 the 设置 的 GIF 帧
    * should be played.  默认hotp生成器 是否 1; 0 means play
    * indefinitely.  Must be invoked 之前 the 第一个
    * 镜像 是否 添加.
    *
    * @param iter int 数字 的 iterations.
    */
    public void setRepeat(int iter) {
        if (iter >= 0) {
            repeat = iter;
        }
    }

    /**
    * 设置 the transparent color for the 最后一个 添加 帧
    * 和 任意 subsequent 帧.
    * 自 全部 colors are 主题 转为 修改
    * 入 the quantization 处理, the color 入 the 最终
    * palette for each 帧 closest 转为 the given color
    * becomes the transparent color for that 帧.
    * May be 设置 转为 空 转为 indicate no transparent color.
    *
    * @param c Color 转为 be treated as transparent on display.
    */
    public void setTransparent(Color c) {
        setTransparent(c, false);
    }

    /**
    * 设置 the transparent color for the 最后一个 添加 帧
    * 和 任意 subsequent 帧.
    * 自 全部 colors are 主题 转为 修改
    * 入 the quantization 处理, the color 入 the 最终
    * palette for each 帧 closest 转为 the given color
    * becomes the transparent color for that 帧.
    * If exact匹配 是否 设置 转为 true, transparent color 索引
    * 是否 搜索 with exact 匹配, 和 not looking for the
    * closest one.
    * May be 设置 转为 空 转为 indicate no transparent color.
    *
    * @param c          Color 转为 be treated as transparent on display.
    * @param exactMatch If exact匹配 是否 设置 转为 true, transparent color 索引 是否 搜索 with exact 匹配
    */
    public void setTransparent(Color c, boolean exactMatch) {
        transparent = c;
        transparentExactMatch = exactMatch;
    }


    /**
    * 设置 the background color for the 最后一个 添加 帧
    * 和 任意 subsequent 帧.
    * 自 全部 colors are 主题 转为 修改
    * 入 the quantization 处理, the color 入 the 最终
    * palette for each 帧 closest 转为 the given color
    * becomes the background color for that 帧.
    * May be 设置 转为 空 转为 indicate no background color
    * which will 默认 转为 black.
    *
    * @param c Color 转为 be treated as background on display.
    */
    public void setBackground(Color c) {
        background = c;
    }

    /**
    * 添加 下一个 GIF 帧.  The 帧 是否 not written immediately, but 是否
    * actually deferred until the 下一个 帧 是否 接收 so that 时间
    * 数据 能否 be 插入.  Invoking {@code finish()} flushes 全部
    * 帧.  If {@code setSize} was not invoked, the 大小 的 the
    * 第一个 镜像 是否 used for 全部 subsequent 帧.
    *
    * @param im 缓冲镜像 containing 帧 转为 写入.
    * @return true if successful.
    */
    public boolean addFrame(BufferedImage im) {
        if ((im == null) || !started) {
            return false;
        }
        boolean ok = true;
        try {
            if (!sizeSet) {


                setSize(im.getWidth(), im.getHeight());
            }
            image = im;
            getImagePixels();

            analyzePixels();

            if (firstFrame) {
                writeLsd();

                writePalette();

                if (repeat >= 0) {


                    writeNetscapeExt();
                }
            }
            writeGraphicCtrlExt();

            writeImageDesc();

            if (!firstFrame) {
                writePalette();

            }
            writePixels();

            firstFrame = false;
        } catch (IOException e) {
            ok = false;
        }

        return ok;
    }

    /**
    * Flushes 任意 pending 数据 和 关闭 输出 文件.
    * If 写入 转为 an 输出流, the 流 是否 not
    * closed.
    *
    * @return is ok
    */
    public boolean finish() {
        if (!started) {
            return false;
        }
        boolean ok = true;
        started = false;
        try {
            out.write(0x3b);

            out.flush();
            if (closeStream) {
                out.close();
            }
        } catch (IOException e) {
            ok = false;
        }


        transIndex = 0;
        out = null;
        image = null;
        pixels = null;
        indexedPixels = null;
        colorTab = null;
        closeStream = false;
        firstFrame = true;

        return ok;
    }

    /**
    * 设置 帧 rate 入 帧 per second.  Equivalent 转为
    * {@code setDelay(1000/fps)}.
    *
    * @param fps float 帧 rate (帧 per second)
    */
    public void setFrameRate(float fps) {
        Float s0 = 0f;
        if (s0.equals(fps)) {
            delay = Math.round(100f / fps);
        }
    }

    /**
    * 设置 quality 的 color quantization (转换 的 镜像
    * 转为 the maximum 256 colors allowed by the GIF specification).
    * 降低 值 (minimum = 1) produce better colors, but slow
    * 处理 significantly.  10 是否 the 默认, 和 produces
    * good color mapping at ReasonML 速度.  值 greater
    * than 20 执行 not yield significant improvements 入 速度.
    *
    * @param quality int greater than 0.
    */
    public void setQuality(int quality) {
        if (quality < 1) {
            quality = 1;
        }
        sample = quality;
    }

    /**
    * 设置 the GIF 帧 大小.  The 默认 大小 是否 the
    * 大小 的 the 第一个 帧 添加 if this 方法 是否
    * not invoked.
    *
    * @param w int 帧 width.
    * @param h int 帧 width.
    */
    public void setSize(int w, int h) {
        if (started && !firstFrame) {
            return;
        }
        width = w;
        height = h;
        if (width < 1) {
            width = 320;
        }
        if (height < 1) {
            height = 240;
        }
        sizeSet = true;
    }

    /**
    * Initiates GIF 文件 创建 on the given 流.  The 流
    * 是否 not 关闭 automatically.
    *
    * @param os 输出流 on which GIF 镜像 are written.
    * @return false if initial 写入 失败.
    */
    public boolean start(OutputStream os) {
        if (os == null) {
            return false;
        }
        boolean ok = true;
        closeStream = false;
        out = os;
        try {
            writeString("GIF89a");

        } catch (IOException e) {
            ok = false;
        }
        return started = ok;
    }

    /**
    * Initiates 写入 的 a GIF 文件 with the specified 名称.
    *
    * @param file 字符串 containing 输出 文件 名称.
    * @return false if 打开 或 initial 写入 失败.
    */
    public boolean start(String file) {
        boolean ok;
        try {
            out = new BufferedOutputStream(new FileOutputStream(file));
            ok = start(out);
            closeStream = true;
        } catch (IOException e) {
            ok = false;
        }
        return started = ok;
    }

    /**
    * 是否启动
    *
    * @return 是否启动的结果
    */
    public boolean isStarted() {
        return started;
    }

    /**
    * 分析 镜像 colors 和 创建 color 映射.
    */
    protected void analyzePixels() {
        int len = pixels.length;
        int nPix = len / 3;
        indexedPixels = new byte[nPix];
        NeuQuant nq = new NeuQuant(pixels, len, sample);
        colorTab = nq.process();
        int s3 = 3;
        for (int i = 0; i < colorTab.length; i += s3) {
            byte temp = colorTab[i];
            colorTab[i] = colorTab[i + 2];
            colorTab[i + 2] = temp;
            usedEntry[i / 3] = false;
        }
        int k = 0;
        for (int i = 0; i < nPix; i++) {
            int index =
                    nq.map(pixels[k++] & 0xff,
                            pixels[k++] & 0xff,
                            pixels[k++] & 0xff);
            usedEntry[index] = true;
            indexedPixels[i] = (byte) index;
        }
        pixels = null;
        colorDepth = 8;
        palSize = 7;


        if (transparent != null) {
            transIndex = transparentExactMatch ? findExact(transparent) : findClosest(transparent);
        }
    }

    /**
    * 返回 索引 的 palette color closest 转为 C
    *
    * @param c Color
    * @return index
    */
    protected int findClosest(Color c) {
        if (colorTab == null) {
            return -1;
        }
        int r = c.getRed();
        int g = c.getGreen();
        int b = c.getBlue();
        int minpos = 0;
        int dmin = 256 * 256 * 256;
        int len = colorTab.length;
        for (int i = 0; i < len; ) {
            int dr = r - (colorTab[i++] & 0xff);
            int dg = g - (colorTab[i++] & 0xff);
            int db = b - (colorTab[i] & 0xff);
            int d = dr * dr + dg * dg + db * db;
            int index = i / 3;
            if (usedEntry[index] && (d < dmin)) {
                dmin = d;
                minpos = index;
            }
            i++;
        }
        return minpos;
    }

    /**
    * 返回 true if the exact 匹配 color 是否 existing, 和 used 入 the color palette, otherwise, 返回 false.
    * This 方法 是否包含 转为 be called 之前 饰面 the 镜像,
    * because 之后 饰面 the palette 是否 销毁 和 it will always 返回 false.
    *
    * @param c 颜色
    * @return 颜色是否存在
    */
    boolean isColorUsed(Color c) {
        return findExact(c) != -1;
    }

    /**
    * 返回 索引 的 palette exactly 匹配 转为 color C 或 -1 if there 是否 no exact 匹配.
    *
    * @param c Color
    * @return index
    */
    protected int findExact(Color c) {
        if (colorTab == null) {
            return -1;
        }

        int r = c.getRed();
        int g = c.getGreen();
        int b = c.getBlue();
        int len = colorTab.length / 3;
        for (int index = 0; index < len; ++index) {
            int i = index * 3;


            if (usedEntry[index] && r == (colorTab[i] & 0xff) && g == (colorTab[i + 1] & 0xff) && b == (colorTab[i + 2] & 0xff)) {
                return index;
            }
        }
        return -1;
    }

    /**
    * Extracts 镜像 pixels into byte array "pixels"
    */
    protected void getImagePixels() {
        int w = image.getWidth();
        int h = image.getHeight();
        int type = image.getType();
        if ((w != width)
                || (h != height)
                || (type != BufferedImage.TYPE_3BYTE_BGR)) {


            BufferedImage temp =
                    new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = temp.createGraphics();
            g.setColor(background);
            g.fillRect(0, 0, width, height);
            g.drawImage(image, 0, 0, null);
            image = temp;
        }
        pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
    }

    /**
    * 写入 Graphic Control 延伸
    *
    * @throws IOException IO异常
    */
    protected void writeGraphicCtrlExt() throws IOException {
        out.write(0x21);

        out.write(0xf9);

        out.write(4);

        int transp, disp;
        if (transparent == null) {
            transp = 0;
            disp = 0;

        } else {
            transp = 1;
            disp = 2;

        }
        if (dispose >= 0) {
            disp = dispose & 7;

        }
        disp <<= 2;


        out.write(0 |

                disp |

                0 |

                transp);


        writeShort(delay);

        out.write(transIndex);

        out.write(0);

    }

    /**
    * 写入 镜像 Descriptor
    *
    * @throws IOException IO异常
    */
    protected void writeImageDesc() throws IOException {
        out.write(0x2c);

        writeShort(0);

        writeShort(0);
        writeShort(width);

        writeShort(height);


        if (firstFrame) {


            out.write(0);
        } else {


            out.write(0x80 |

                    0 |

                    0 |

                    0 |

                    palSize);

        }
    }

    /**
    * 写入 逻辑 屏幕 Descriptor
    *
    * @throws IOException IO异常
    */
    protected void writeLsd() throws IOException {
        writeShort(width);
        writeShort(height);
        out.write((0x80 | 0x70 | palSize));
        out.write(0);
        out.write(0);
    }

    /**
    * 写入 Netscape application 延伸 转为 define
    * repeat 数量.
    *
    * @throws IOException IO异常
    */
    protected void writeNetscapeExt() throws IOException {
        out.write(0x21);

        out.write(0xff);

        out.write(11);

        writeString("NETSCAPE" + "2.0");

        out.write(3);

        out.write(1);

        writeShort(repeat);

        out.write(0);

    }

    /**
    * 写入 color table
    *
    * @throws IOException IO异常
    */
    protected void writePalette() throws IOException {
        out.write(colorTab, 0, colorTab.length);
        int n = (3 * 256) - colorTab.length;
        for (int i = 0; i < n; i++) {
            out.write(0);
        }
    }

    /**
    * Encodes 和 写入 pixel 数据
    *
    * @throws IOException IO异常
    */
    protected void writePixels() throws IOException {
        LzwEncoder encoder = new LzwEncoder(width, height, indexedPixels, colorDepth);
        encoder.encode(out);
    }

    /**
    * 将 16 位值写入输出流，LSB 在前
    *
    * @param value 16 位值
    * @throws IOException IO异常
    */
    protected void writeShort(int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }

    /**
    * 写入 字符串 转为 输出 流
    *
    * @param s 字符串
    * @throws IOException IO异常
    */
    protected void writeString(String s) throws IOException {
        for (int i = 0; i < s.length(); i++) {
            out.write((byte) s.charAt(i));
        }
    }
}
