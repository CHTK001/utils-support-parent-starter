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


/**
 * 鍥惧儚婊ら暅鎶借薄鍩虹被
 *
 * 鎻愪緵鍥惧儚婊ら暅澶勭悊鐨勫熀纭€瀹炵幇锛屽寘鍚浘鍍忔暟鎹鐞嗐€侀鑹茬┖闂磋浆鎹€?
 * 鍍忕礌鎿嶄綔绛夐€氱敤鍔熻兘銆傛墍鏈夊叿浣撶殑鍥惧儚婊ら暅閮藉簲缁ф壙姝ょ被銆?
 *
 * 涓昏鍔熻兘锛?
 * - 鍥惧儚鏁版嵁鍒濆鍖栧拰棰勫鐞?
 * - RGB 鍜?HSL 棰滆壊绌洪棿杞崲
 * - 鍍忕礌绾у埆鐨勮鍐欐搷浣?
 * - GIF 鍔ㄧ敾澶勭悊鏀寔
 * - 鍥惧儚鏍煎紡璇嗗埆鍜岃浆鎹?
 *
 * 鎶€鏈壒鐐癸細
 * - 鏀寔澶氱鍥惧儚鏍煎紡锛圝PEG銆丳NG銆丟IF绛夛級
 * - 鎻愪緵楂樻晥鐨勫儚绱犳搷浣滄柟娉?
 * - 鍐呯疆棰滆壊绌洪棿杞崲绠楁硶
 * - 鏀寔鍔ㄦ€佸浘鍍忓鐞?
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
public abstract class AbstractImageFilter implements ImageFilter {

    /**
     * 鍥惧儚瀹藉害
     */
    protected int width;

    /**
     * 鍥惧儚楂樺害
     */
    protected int height;

    /**
     * 绾㈣壊閫氶亾鏁版嵁鏁扮粍
     */
    protected byte[] rArr;

    /**
     * 缁胯壊閫氶亾鏁版嵁鏁扮粍
     */
    protected byte[] gArr;

    /**
     * 钃濊壊閫氶亾鏁版嵁鏁扮粍
     */
    protected byte[] bArr;

    /**
     * 瀹夊叏闅忔満鏁扮敓鎴愬櫒锛岄渶瑕侀殢鏈烘晥鏋滅殑婊ら暅
     */
    protected SecureRandom randomNumbers = new SecureRandom();

    /**
     * 鍥惧儚鏍煎紡鍚嶇О
     */
    private String name;

    /**
     * 甯搁噺锛?/60锛孒SL棰滆壊绌洪棿杞崲
     */
    public static final double CLO_60 = 1.0 / 60.0;

    /**
     * 甯搁噺锛?/255锛岄鑹插€煎綊涓€鍖?
     */
    public static final double CLO_255 = 1.0 / 255.0;

    /**
     * 涓存椂RGB棰滆壊鍊硷紝棰滆壊绌洪棿杞崲
     */
    public int tr = 0, tg = 0, tb = 0;

    /**
     * 杞崲BufferedImage鍥惧儚
     *
     * @param image 闇€瑕佸鐞嗙殑BufferedImage瀵硅薄
     * @return 澶勭悊鍚庣殑BufferedImage瀵硅薄
     * @throws IOException 澶勭悊杩囩▼涓彲鑳藉彂鐢熺殑IO寮傚父
     */
    @Override
    public BufferedImage converter(BufferedImage image) throws IOException {
        initial(image);
        return filter(image, null);
    }

    /**
     * 鍒濆鍖栧浘鍍忔暟鎹?
     *
     * 浠嶣ufferedImage涓彁鍙栧儚绱犳暟鎹紝鍒嗙RGB涓変釜棰滆壊閫氶亾锛?
     * 涓哄悗缁殑婊ら暅澶勭悊鍋氬噯澶囥€?
     *
     * @param image 寰呭鐞嗙殑鍥惧儚瀵硅薄
     */
    protected void initial(BufferedImage image) {
        width = image.getWidth();
        height = image.getHeight();
        int[] input = new int[width * height];
        com.chua.common.support.utils.BufferedImageUtils.getRgb(image, 0, 0, width, height, input);
        int size = width * height;
        rArr = new byte[size];
        gArr = new byte[size];
        bArr = new byte[size];
        backFillData(input);
    }

    /**
     * 濉厖RGB棰滆壊閫氶亾鏁版嵁
     *
     * 灏咥RGB鏍煎紡鐨勫儚绱犳暟鎹垎绂讳负鐙珛鐨凴GB涓変釜棰滆壊閫氶亾鏁扮粍锛?
     * 渚夸簬鍚庣画鐨勯鑹插鐞嗗拰婊ら暅绠楁硶搴旂敤銆?
     *
     * @param input ARGB鏍煎紡鐨勫儚绱犳暟鎹暟缁?
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
     * 杞崲杈撳叆娴佸舰寮忕殑鍥惧儚鏁版嵁
     *
     * 鏀寔闈欐€佸浘鍍忓拰GIF鍔ㄧ敾鐨勫鐞嗐€傚浜嶨IF鏍煎紡锛屼細閫愬抚澶勭悊骞堕噸鏂扮紪鐮侊紱
     * 瀵逛簬鍏朵粬鏍煎紡锛岀洿鎺ヨ繘琛屾护闀滃鐞嗐€?
     *
     * @param image 杈撳叆娴佸舰寮忕殑鍥惧儚鏁版嵁
     * @return 澶勭悊鍚庣殑鍥惧儚鏁版嵁杈撳嚭娴?
     * @throws IOException 澶勭悊杩囩▼涓彲鑳藉彂鐢熺殑IO寮傚父
     */
    @Override
    public OutputStream converter(InputStream image) throws IOException {
        try(InputStream is = image) {
            String imageFormat = getImageFormat();
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            // 澶勭悊GIF鍔ㄧ敾
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
                // 澶勭悊闈欐€佸浘鍍?
                BufferedImage read = ImageIO.read(image);
                BufferedImage bufferedImage = converter(read);
                ImageIO.write(bufferedImage, getImageFormat(), out);
            }
            return out;
        }
    }

    /**
     * 璁剧疆骞惰幏鍙栧浘鍍忔牸寮?
     *
     * @param name 鍥惧儚鏍煎紡鍚嶇О
     * @return 鍥惧儚鏍煎紡鍚嶇О
     */
    @Override
    public String getImageFormat(String name) {
        this.name = name;
        return name;
    }

    /**
     * 鑾峰彇褰撳墠璁剧疆鐨勫浘鍍忔牸寮?
     *
     * @return 鍥惧儚鏍煎紡鍚嶇О
     */
    @Override
    public String getImageFormat() {
        
        return name;
    
    }

    /**
     * 鍒涘缓鍏煎鐨勭洰鏍囧浘鍍?
     *
     * 鏍规嵁婧愬浘鍍忓拰鎸囧畾鐨勯鑹叉ā鍨嬪垱寤轰竴涓吋瀹圭殑鐩爣鍥惧儚銆?
     * 濡傛灉鏈寚瀹氶鑹叉ā鍨嬶紝鍒欎娇鐢ㄦ簮鍥惧儚鐨勯鑹叉ā鍨嬨€?
     *
     * @param src        婧愬浘鍍?
     * @param colorModel 鐩爣棰滆壊妯″瀷锛屽彲浠ヤ负null
     * @return 鏂板垱寤虹殑鍏煎鍥惧儚
     */
    public BufferedImage createCompatibleDestImage(BufferedImage src, ColorModel colorModel) {
        if (colorModel == null) {
            colorModel = src.getColorModel();
        }
        return new BufferedImage(colorModel, colorModel.createCompatibleWritableRaster(src.getWidth(), src.getHeight()), colorModel.isAlphaPremultiplied(), null);
    }

    /**
     * 鎶借薄婊ら暅澶勭悊鏂规硶
     *
     * 鍏蜂綋鐨勬护闀滄晥鏋滅敱瀛愮被瀹炵幇銆傛鏂规硶瀹氫箟浜嗘护闀滃鐞嗙殑鏍囧噯鎺ュ彛銆?
     *
     * @param src 婧愬浘鍍?
     * @param dst 鐩爣鍥惧儚锛屽彲浠ヤ负null
     * @return 澶勭悊鍚庣殑鍥惧儚
     */
    abstract public BufferedImage filter(BufferedImage src, BufferedImage dst);

    /**
     * 鑾峰彇鍥惧儚鐨勮竟鐣岀煩褰?
     *
     * @param src 婧愬浘鍍?
     * @return 鍥惧儚鐨勮竟鐣岀煩褰?
     */
    public Rectangle2D getBounds2D(BufferedImage src) {
        return new Rectangle(0, 0, src.getWidth(), src.getHeight());
    }

    /**
     * 鑾峰彇鍙樻崲鍚庣殑鐐瑰潗鏍?
     *
     * 瀵逛簬澶у鏁版护闀滐紝鐐圭殑浣嶇疆涓嶄細鏀瑰彉锛岀洿鎺ュ鍒跺潗鏍囥€?
     * 鏌愪簺鍑犱綍鍙樻崲婊ら暅鍙兘浼氶噸鍐欐鏂规硶銆?
     *
     * @param srcPt 婧愮偣鍧愭爣
     * @param dstPt 鐩爣鐐瑰潗鏍囷紝鍙互涓簄ull
     * @return 鍙樻崲鍚庣殑鐐瑰潗鏍?
     */
    public Point2D getPoint2D(Point2D srcPt, Point2D dstPt) {
        if (dstPt == null) {
            dstPt = new Point2D.Double();
        }
        dstPt.setLocation(srcPt.getX(), srcPt.getY());
        return dstPt;
    }

    /**
     * 楂樻晥鑾峰彇鍥惧儚ARGB鍍忕礌鏁版嵁
     *
     * 杩欐槸涓€涓紭鍖栫殑鍍忕礌鑾峰彇鏂规硶锛屽浜嶪NT_ARGB鍜孖NT_RGB绫诲瀷鐨勫浘鍍忥紝
     * 鐩存帴浠庡厜鏍呮暟鎹幏鍙栵紝閬垮厤BufferedImage.getRGB鐨勬€ц兘鎹熷け銆?
     *
     * @param image  BufferedImage瀵硅薄
     * @param x      鍍忕礌鍖哄煙鐨勫乏涓婅X鍧愭爣
     * @param y      鍍忕礌鍖哄煙鐨勫乏涓婅Y鍧愭爣
     * @param width  鍍忕礌鍖哄煙鐨勫搴?
     * @param height 鍍忕礌鍖哄煙鐨勯珮搴?
     * @param pixels 瀛樺偍鍍忕礌鏁版嵁鐨勬暟缁勶紝鍙互涓簄ull
     * @return ARGB鏍煎紡鐨勫儚绱犳暟鎹暟缁?
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
     * 楂樻晥璁剧疆鍥惧儚ARGB鍍忕礌鏁版嵁
     *
     * 杩欐槸涓€涓紭鍖栫殑鍍忕礌璁剧疆鏂规硶锛屽浜嶪NT_ARGB鍜孖NT_RGB绫诲瀷鐨勫浘鍍忥紝
     * 鐩存帴璁剧疆鍏夋爡鏁版嵁锛岄伩鍏岯ufferedImage.setRGB鐨勬€ц兘鎹熷け銆?
     *
     * @param image  BufferedImage瀵硅薄
     * @param x      鍍忕礌鍖哄煙鐨勫乏涓婅X鍧愭爣
     * @param y      鍍忕礌鍖哄煙鐨勫乏涓婅Y鍧愭爣
     * @param width  鍍忕礌鍖哄煙鐨勫搴?
     * @param height 鍍忕礌鍖哄煙鐨勯珮搴?
     * @param pixels ARGB鏍煎紡鐨勫儚绱犳暟鎹暟缁?
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
     * 鏍规嵁绱㈠紩鑾峰彇棰滆壊閫氶亾瀛楄妭鏁扮粍
     *
     * @param index 棰滆壊閫氶亾绱㈠紩锛?-绾㈣壊锛?-缁胯壊锛?-钃濊壊
     * @return 瀵瑰簲棰滆壊閫氶亾鐨勫瓧鑺傛暟缁?
     * @throws IllegalArgumentException 褰撶储寮曞€兼棤鏁堟椂鎶涘嚭寮傚父
     */
    public byte[] toColorByte(int index) {
        if (index == 0) {
            return rArr;
        } else if (index == 1) {
            return gArr;
        } else if (index == 2) {
            return bArr;
        } else {
            throw new IllegalArgumentException("鏃犳晥鐨勯鑹查€氶亾绱㈠紩: " + index + "锛屾湁鏁堝€间负0(绾㈣壊)銆?(缁胯壊)銆?(钃濊壊)");
        }
    }

    /**
     * 灏哛GB棰滆壊閫氶亾鏁版嵁杞崲涓築ufferedImage
     *
     * @return 鏍规嵁褰撳墠RGB鏁版嵁鍒涘缓鐨凚ufferedImage瀵硅薄
     */
    public BufferedImage toBitmap() {
        int[] pixels = new int[width * height];
        BufferedImage bitmap = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        setRgb(width, height, pixels, toColorByte(0), toColorByte(1), toColorByte(2));
        setRgb(bitmap, 0, 0, width, height, pixels);
        return bitmap;
    }

    /**
     * 璁剧疆RGB棰滆壊閫氶亾鏁版嵁
     *
     * @param red   绾㈣壊閫氶亾鏁版嵁
     * @param green 缁胯壊閫氶亾鏁版嵁
     * @param blue  钃濊壊閫氶亾鏁版嵁
     */
    public void putRgb(byte[] red, byte[] green, byte[] blue) {
        System.arraycopy(red, 0, rArr, 0, red.length);
        System.arraycopy(green, 0, gArr, 0, green.length);
        System.arraycopy(blue, 0, bArr, 0, blue.length);
    }

    /**
     * 灏哛GB瀛楄妭鏁扮粍鍚堝苟涓篈RGB鍍忕礌鏁扮粍
     *
     * @param width  鍥惧儚瀹藉害
     * @param height 鍥惧儚楂樺害
     * @param pixels 杈撳嚭鐨勫儚绱犳暟缁?
     * @param r      绾㈣壊閫氶亾鏁版嵁
     * @param g      缁胯壊閫氶亾鏁版嵁
     * @param b      钃濊壊閫氶亾鏁版嵁
     */
    public void setRgb(int width, int height, int[] pixels, byte[] r, byte[] g, byte[] b) {
        for (int i = 0; i < width * height; i++) {
            pixels[i] = 0xff000000 | ((r[i] & 0xff) << 16) | ((g[i] & 0xff) << 8) | b[i] & 0xff;
        }
    }


    /**
     * RGB鑹插僵绌洪棿杞崲涓篐SL鑹插僵绌洪棿
     *
     * 灏哛GB棰滆壊鍊艰浆鎹负HSL锛堣壊鐩搞€侀ケ鍜屽害銆佷寒搴︼級棰滆壊绌洪棿銆?
     * HSL棰滆壊绌洪棿鏇撮€傚悎杩涜棰滆壊璋冩暣鍜屾护闀滄晥鏋滃鐞嗐€?
     *
     * @param hsl RGB棰滆壊鍊兼暟缁勶紝鏍煎紡涓篬R, G, B]锛屽彇鍊艰寖鍥?-255
     * @return HSL棰滆壊鍊兼暟缁勶紝鏍煎紡涓篬H, S, L]锛屽叾涓璈鍙栧€?-360锛孲鍜孡鍙栧€?-255
     */
    public double[] rgb2Hsl(int[] hsl) {
        double min, max, dif, sum;
        double f1, f2;
        double h, s, l;
        double[] hsl1 = {0.0, 0.0, 0.0};

        // 鑾峰彇RGB鍒嗛噺
        tr = hsl[0];
        tg = hsl[1];
        tb = hsl[2];

        // 鎵惧埌鏈€灏忓€?
        min = tr;
        if (tg < min) {
            min = tg;
        }
        if (tb < min) {
            min = tb;
        }

        // 鎵惧埌鏈€澶у€煎苟纭畾涓昏壊璋?
        max = tr;
        f1 = 0.0;
        f2 = tg - tb;
        if (tg > max) {
            max = tg;
            // 缁胯壊涓诲
            // = 120.0;
            f1 = 120.0;
            f2 = tb - tr;
        }
        if (tb > max) {
            max = tb;
            // 钃濊壊涓诲
            // = 240.0;
            f1 = 240.0;
            f2 = tr - tg;
        }

        dif = max - min;
        sum = max + min;
        l = sum / 2.0;

        double f127 = 127.5D;
        if (dif == 0) {
            // 鐏拌壊锛屾棤鑹茬浉鍜岄ケ鍜屽害
            h = 0.0;
            s = 0.0;
        } else if (l < f127) {
            s = 255.0 * dif / sum;
        } else {
            s = 255.0 * dif / (510.0 - sum);
        }

        // 璁＄畻鑹茬浉
        h = (f1 + 60.0 * f2) / dif;
        if (h < 0.0) {
            h += 360.0;
        }
        double f360 = 360.0D;
        if (h > f360) {
            h -= 360.0;
        }

        // 鑹茬浉 (0-360)
        hsl1[0] = h;
        // 楗卞拰搴?(0-255)
        hsl1[1] = s;
        // 浜害 (0-255)
        hsl1[2] = l;
        return hsl1;
    }

    /**
     * HSL鑹插僵绌洪棿杞崲涓篟GB鑹插僵绌洪棿
     *
     * 灏咹SL锛堣壊鐩搞€侀ケ鍜屽害銆佷寒搴︼級棰滆壊鍊艰浆鎹㈠洖RGB棰滆壊绌洪棿銆?
     * 杩欐槸rgb2Hsl鏂规硶鐨勯€嗗悜杞崲銆?
     *
     * @param hsl HSL棰滆壊鍊兼暟缁勶紝鏍煎紡涓篬H, S, L]锛屽叾涓璈鍙栧€?-360锛孲鍜孡鍙栧€?-255
     * @return RGB棰滆壊鍊兼暟缁勶紝鏍煎紡涓篬R, G, B]锛屽彇鍊艰寖鍥?-255
     */
    public int[] hsl2Rgb(double[] hsl) {
        double h, s, l;
        // 鑹茬浉
        // [0];
        h = hsl[0];
        // 楗卞拰搴?(0-255)
        s = hsl[1];
        // 浜害 (0-255)
        l = hsl[2];
        int[] rgb1 = {0, 0, 0};
        double v1, v2, v3, h1;

        // HSL 杞崲涓?RGB
        if (s == 0) {
            // 鏃犻ケ鍜屽害锛屼负鐏拌壊
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

            // 璁＄畻绾㈣壊鍒嗛噺
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

            // 璁＄畻缁胯壊鍒嗛噺
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

            // 璁＄畻钃濊壊鍒嗛噺
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

        // 绾㈣壊鍒嗛噺
        // tr;
        rgb1[0] = tr;
        // 缁胯壊鍒嗛噺
        // tg;
        rgb1[1] = tg;
        // 钃濊壊鍒嗛噺
        // tb;
        rgb1[2] = tb;
        return rgb1;
    }

    /**
     * 鍒涘缓鍏煎鐨勭洰鏍囧浘鍍?
     *
     * 鏍规嵁婧愬浘鍍忕殑灏哄鍒涘缓涓€涓柊鐨凴GB鏍煎紡鍥惧儚銆?
     *
     * @param src  婧愬浘鍍?
     * @param dest 鐩爣鍥惧儚锛堟鍙傛暟鏈娇鐢級
     * @return 鏂板垱寤虹殑RGB鏍煎紡鍥惧儚
     */
    public BufferedImage creatCompatibleDestImage(BufferedImage src, BufferedImage dest) {
        return new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
    }
}

