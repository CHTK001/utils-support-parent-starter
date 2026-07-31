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
 * @version 1.03 November 2003
 * @since 5.3.8
 * @author CH
*/
public class GifEncoder {

    protected int width;
    protected int height;
    protected Color transparent = null;
    protected boolean transparentExactMatch = false;
    protected Color background = null;
    protected int transIndex;
    protected int repeat = -1;
    protected int delay = 0;
    protected boolean started = false;
    protected OutputStream out;
    protected BufferedImage image;
    protected byte[] pixels;
    protected byte[] indexedPixels;
    protected int colorDepth;
    protected byte[] colorTab;
    protected boolean[] usedEntry = new boolean[256];
    protected int palSize = 7;
    protected int dispose = -1;
    protected boolean closeStream = false;
    protected boolean firstFrame = true;
    protected boolean sizeSet = false;
    protected int sample = 10;

    /**
     * 设置每一帧的间隔时间
     * Sets the delay time between each frame, or changes it
     * for subsequent frames (applies to last frame added).
     *
     * @param ms 间隔时间，单位毫秒
     */
    public void setDelay(int ms) {
        delay = Math.round(ms / 10.0f);
    }

    /**
     * Sets the GIF frame disposal code for the last added frame
     * and any subsequent frames.  DefaultHotpGenerator is 0 if no transparent
     * color has been set, otherwise 2.
     *
     * @param code int disposal code.
     */
    public void setDispose(int code) {
        if (code >= 0) {
            dispose = code;
        }
    }

    /**
     * Sets the number of times the set of GIF frames
     * should be played.  DefaultHotpGenerator is 1; 0 means play
     * indefinitely.  Must be invoked before the first
     * image is added.
     *
     * @param iter int number of iterations.
     */
    public void setRepeat(int iter) {
        if (iter >= 0) {
            repeat = iter;
        }
    }

    /**
     * Sets the transparent color for the last added frame
     * and any subsequent frames.
     * Since all colors are subject to modification
     * in the quantization process, the color in the final
     * palette for each frame closest to the given color
     * becomes the transparent color for that frame.
     * May be set to null to indicate no transparent color.
     *
     * @param c Color to be treated as transparent on display.
     */
    public void setTransparent(Color c) {
        setTransparent(c, false);
    }

    /**
     * Sets the transparent color for the last added frame
     * and any subsequent frames.
     * Since all colors are subject to modification
     * in the quantization process, the color in the final
     * palette for each frame closest to the given color
     * becomes the transparent color for that frame.
     * If exactMatch is set to true, transparent color index
     * is search with exact match, and not looking for the
     * closest one.
     * May be set to null to indicate no transparent color.
     *
     * @param c          Color to be treated as transparent on display.
     * @param exactMatch If exactMatch is set to true, transparent color index is search with exact match
     */
    public void setTransparent(Color c, boolean exactMatch) {
        transparent = c;
        transparentExactMatch = exactMatch;
    }


    /**
     * Sets the background color for the last added frame
     * and any subsequent frames.
     * Since all colors are subject to modification
     * in the quantization process, the color in the final
     * palette for each frame closest to the given color
     * becomes the background color for that frame.
     * May be set to null to indicate no background color
     * which will default to black.
     *
     * @param c Color to be treated as background on display.
     */
    public void setBackground(Color c) {
        background = c;
    }

    /**
     * Adds next GIF frame.  The frame is not written immediately, but is
     * actually deferred until the next frame is received so that timing
     * data can be inserted.  Invoking {@code finish()} flushes all
     * frames.  If {@code setSize} was not invoked, the size of the
     * first image is used for all subsequent frames.
     *
     * @param im BufferedImage containing frame to write.
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
     * Flushes any pending data and closes output file.
     * If writing to an OutputStream, the stream is not
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
     * Sets frame rate in frames per second.  Equivalent to
     * {@code setDelay(1000/fps)}.
     *
     * @param fps float frame rate (frames per second)
     */
    public void setFrameRate(float fps) {
        Float s0 = 0f;
        if (s0.equals(fps)) {
            delay = Math.round(100f / fps);
        }
    }

    /**
     * Sets quality of color quantization (conversion of images
     * to the maximum 256 colors allowed by the GIF specification).
     * Lower values (minimum = 1) produce better colors, but slow
     * processing significantly.  10 is the default, and produces
     * good color mapping at reasonable speeds.  Values greater
     * than 20 do not yield significant improvements in speed.
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
     * Sets the GIF frame size.  The default size is the
     * size of the first frame added if this method is
     * not invoked.
     *
     * @param w int frame width.
     * @param h int frame width.
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
     * Initiates GIF file creation on the given stream.  The stream
     * is not closed automatically.
     *
     * @param os OutputStream on which GIF images are written.
     * @return false if initial write failed.
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
     * Initiates writing of a GIF file with the specified name.
     *
     * @param file String containing output file name.
     * @return false if open or initial write failed.
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

    public boolean isStarted() {
        return started;
    }

    /**
     * Analyzes image colors and creates color map.
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
     * Returns index of palette color closest to c
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
     * Returns true if the exact matching color is existing, and used in the color palette, otherwise, return false.
     * This method has to be called before finishing the image,
     * because after finished the palette is destroyed and it will always return false.
     *
     * @param c 颜色
     * @return 颜色是否存在
     */
    boolean isColorUsed(Color c) {
        return findExact(c) != -1;
    }

    /**
     * Returns index of palette exactly matching to color c or -1 if there is no exact matching.
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
     * Extracts image pixels into byte array "pixels"
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
     * Writes Graphic Control Extension
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
     * Writes Image Descriptor
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
     * Writes Logical Screen Descriptor
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
     * Writes Netscape application extension to define
     * repeat count.
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
     * Writes color table
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
     * Encodes and writes pixel data
     *
     * @throws IOException IO异常
     */
    protected void writePixels() throws IOException {
        LzwEncoder encoder = new LzwEncoder(width, height, indexedPixels, colorDepth);
        encoder.encode(out);
    }

    /**
     * Write 16-bit value to output stream, LSB first
     *
     * @param value 16-bit value
     * @throws IOException IO异常
     */
    protected void writeShort(int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }

    /**
     * Writes string to output stream
     *
     * @param s String
     * @throws IOException IO异常
     */
    protected void writeString(String s) throws IOException {
        for (int i = 0; i < s.length(); i++) {
            out.write((byte) s.charAt(i));
        }
    }
}
