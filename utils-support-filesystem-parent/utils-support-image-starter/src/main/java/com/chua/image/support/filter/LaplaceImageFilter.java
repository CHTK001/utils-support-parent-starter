package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 鎷夋櫘鎷夋柉鍥惧儚閿愬寲婊ら暅
 *
 * 鍩轰簬鎷夋櫘鎷夋柉绠楀瓙鐨勫浘鍍忛攼鍖栨护闀滐紝鎻愪緵澶氱鍥惧儚澧炲己澶勭悊妯″紡銆?
 * 鎷夋櫘鎷夋柉绠楀瓙鏄竴绉嶄簩闃跺井鍒嗙畻瀛愶紝鑳藉妫€娴嬪浘鍍忎腑鐨勮竟缂樺苟杩涜閿愬寲澶勭悊銆?
 *
 * 鎶€鏈師鐞嗭細
 * - 鎷夋櫘鎷夋柉绠楀瓙锛氫簩闃跺井鍒嗙畻瀛愶紝瀵瑰浘鍍忚繘琛岃竟缂樻娴?
 * - 鍥惧儚閿愬寲锛氶€氳繃澧炲己杈圭紭鏉ユ彁楂樺浘鍍忔竻鏅板害
 * - 澶氱畻娉曡瀺鍚堬細缁撳悎鎷夋櫘鎷夋柉銆丼obel銆佸潎鍊兼护娉㈢瓑澶氱绠楁硶
 * - 浼介┈鏍℃锛氳皟鏁村浘鍍忕殑浜害鍜屽姣斿害
 *
 * 鎷夋櫘鎷夋柉绠楀瓙锛?x3锛夛細
 * [ 0 -1  0]
 * [-1  4 -1]
 * [ 0 -1  0]
 *
 * 澶勭悊妯″紡锛?
 * 1. 鍩虹鎷夋櫘鎷夋柉澶勭悊锛氱洿鎺ュ簲鐢ㄦ媺鏅媺鏂畻瀛?
 * 2. 鎷夋櫘鎷夋柉鍙犲姞澶勭悊锛氭媺鏅媺鏂粨鏋滀笌鍘熷浘鍙犲姞
 * 3. Sobel杈圭紭妫€娴嬶細浣跨敤Sobel绠楀瓙杩涜杈圭紭妫€娴?
 * 4. 鍧囧€兼护娉㈠鐞嗭細5x5鍧囧€兼护娉㈠钩婊戝鐞?
 * 5. 鏁板杩愮畻澶勭悊锛氬绉嶇畻娉曠粨鏋滅殑鏁板缁勫悎
 * 6. 浼介┈鏍℃澶勭悊锛氭渶缁堢殑浜害鍜屽姣斿害璋冩暣
 *
 * 绠楁硶鐗圭偣锛?
 * - 杈圭紭澧炲己锛氭湁鏁堝寮哄浘鍍忕殑杈圭紭鍜岀粏鑺?
 * - 鍣０鏁忔劅锛氬鍣０姣旇緝鏁忔劅锛屽彲鑳芥斁澶у櫔澹?
 * - 澶氱骇澶勭悊锛氭彁渚涗粠绠€鍗曞埌澶嶆潅鐨勫绉嶅鐞嗙骇鍒?
 * - 鑷€傚簲澶勭悊锛氬彲鏍规嵁鍥惧儚鐗圭偣閫夋嫨鍚堥€傜殑澶勭悊妯″紡
 *
 * 搴旂敤鍦烘櫙锛?
 * - 鍥惧儚閿愬寲锛氭彁楂樺浘鍍忕殑娓呮櫚搴﹀拰缁嗚妭
 * - 杈圭紭澧炲己锛氱獊鍑烘樉绀哄浘鍍忎腑鐨勮竟缂樹俊鎭?
 * - 鍖诲褰卞儚锛氬尰瀛﹀浘鍍忕殑杈圭紭澧炲己鍜岀粏鑺傛彁鍗?
 * - 宸ヤ笟妫€娴嬶細浜у搧琛ㄩ潰缂洪櫡妫€娴嬪拰杈圭紭鍒嗘瀽
 * - 鍥惧儚棰勫鐞嗭細涓哄悗缁鐞嗗噯澶囬珮璐ㄩ噺鍥惧儚
 * - 鍗板埛鍑虹増锛氭彁楂樺嵃鍒峰浘鍍忕殑娓呮櫚搴?
 *
 * 浣跨敤寤鸿锛?
 * - 瀵逛簬鍣０杈冨鐨勫浘鍍忥紝寤鸿鍏堣繘琛岄檷鍣鐞?
 * - 鍙牴鎹浘鍍忕壒鐐归€夋嫨鍚堥€傜殑澶勭悊寮哄害
 * - 寤鸿涓庡叾浠栨护闀滅粍鍚堜娇鐢ㄤ互鑾峰緱鏈€浣虫晥鏋?
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@Spi("laplace")
@SpiDescribe("鎷夋櫘鎷夋柉鍥惧儚閿愬寲婊ら暅")
public class LaplaceImageFilter extends AbstractImageFilter{
    /**
     * 鎵ц鎷夋櫘鎷夋柉婊ら暅澶勭悊
     *
     * 榛樿浣跨敤鎷夋櫘鎷夋柉鍙犲姞澶勭悊妯″紡锛屽皢鎷夋櫘鎷夋柉绠楀瓙鐨勭粨鏋滀笌鍘熷浘鍍忓彔鍔狅紝
     * 瀹炵幇鍥惧儚閿愬寲鏁堟灉銆?
     *
     * @param src 婧愬浘鍍?
     * @param dst 鐩爣鍥惧儚锛堟鍙傛暟鏈娇鐢級
     * @return 澶勭悊鍚庣殑鍥惧儚
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        
        return laplaceAddProcess(src);
    
    }

    /**
     * 鍩虹鎷夋櫘鎷夋柉澶勭悊
     *
     * 鐩存帴搴旂敤鎷夋櫘鎷夋柉绠楀瓙瀵瑰浘鍍忚繘琛岃竟缂樻娴嬪拰閿愬寲澶勭悊銆?
     * 浣跨敤鏍囧噯鐨?x3鎷夋櫘鎷夋柉鍗风Н鏍歌繘琛屽鐞嗐€?
     *
     * @param src 婧愬浘鍍?
     * @return 鎷夋櫘鎷夋柉澶勭悊鍚庣殑鍥惧儚
     */
    public BufferedImage laplaceProcess(BufferedImage src) {

        // 鎷夋櫘鎷夋柉绠楀瓙
        int[] LAPLACE = new int[] { 0, -1, 0, -1, 4, -1, 0, -1, 0 };

        int width = src.getWidth();
        int height = src.getHeight();

        int[] pixels = new int[width * height];
        int[] outPixels = new int[width * height];

        int type = src.getType();
        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            src.getRaster().getDataElements(0, 0, width, height, pixels);
        }
        src.getRGB(0, 0, width, height, pixels, 0, width);

        int k0 = 0, k1 = 0, k2 = 0;
        int k3 = 0, k4 = 0, k5 = 0;
        int k6 = 0, k7 = 0, k8 = 0;

        k0 = LAPLACE[0];
        k1 = LAPLACE[1];
        k2 = LAPLACE[2];
        k3 = LAPLACE[3];
        k4 = LAPLACE[4];
        k5 = LAPLACE[5];
        k6 = LAPLACE[6];
        k7 = LAPLACE[7];
        k8 = LAPLACE[8];
        int offset = 0;

        int sr = 0, sg = 0, sb = 0;
        int r = 0, g = 0, b = 0;
        for (int row = 1; row < height - 1; row++) {
            offset = row * width;
            for (int col = 1; col < width - 1; col++) {
                // red
                sr = k0 * ((pixels[offset - width + col - 1] >> 16) & 0xff)
                        + k1 * ((pixels[offset - width + col] >> 16) & 0xff)
                        + k2
                        * ((pixels[offset - width + col + 1] >> 16) & 0xff)
                        + k3 * ((pixels[offset + col - 1] >> 16) & 0xff) + k4
                        * ((pixels[offset + col] >> 16) & 0xff) + k5
                        * ((pixels[offset + col + 1] >> 16) & 0xff) + k6
                        * ((pixels[offset + width + col - 1] >> 16) & 0xff)
                        + k7 * ((pixels[offset + width + col] >> 16) & 0xff)
                        + k8
                        * ((pixels[offset + width + col + 1] >> 16) & 0xff);
                // green
                sg = k0 * ((pixels[offset - width + col - 1] >> 8) & 0xff) + k1
                        * ((pixels[offset - width + col] >> 8) & 0xff) + k2
                        * ((pixels[offset - width + col + 1] >> 8) & 0xff) + k3
                        * ((pixels[offset + col - 1] >> 8) & 0xff) + k4
                        * ((pixels[offset + col] >> 8) & 0xff) + k5
                        * ((pixels[offset + col + 1] >> 8) & 0xff) + k6
                        * ((pixels[offset + width + col - 1] >> 8) & 0xff) + k7
                        * ((pixels[offset + width + col] >> 8) & 0xff) + k8
                        * ((pixels[offset + width + col + 1] >> 8) & 0xff);
                // blue
                sb = k0 * (pixels[offset - width + col - 1] & 0xff) + k1
                        * (pixels[offset - width + col] & 0xff) + k2
                        * (pixels[offset - width + col + 1] & 0xff) + k3
                        * (pixels[offset + col - 1] & 0xff) + k4
                        * (pixels[offset + col] & 0xff) + k5
                        * (pixels[offset + col + 1] & 0xff) + k6
                        * (pixels[offset + width + col - 1] & 0xff) + k7
                        * (pixels[offset + width + col] & 0xff) + k8
                        * (pixels[offset + width + col + 1] & 0xff);
                r = sr;
                g = sg;
                b = sb;
                outPixels[offset + col] = (0xff << 24) | (clamp(r) << 16)
                        | (clamp(g) << 8) | clamp(b);
                sr = 0;
                sg = 0;
                sb = 0;
            }
        }

        BufferedImage dest = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);

        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            dest.getRaster().setDataElements(0, 0, width, height, outPixels);
        } else {
            dest.setRGB(0, 0, width, height, outPixels, 0, width);
        }

        return dest;
    }
    /**
     * 鎷夋櫘鎷夋柉鍙犲姞鍘熷浘鍍忓鐞?
     *
     * 灏嗘媺鏅媺鏂畻瀛愮殑澶勭悊缁撴灉涓庡師鍥惧儚杩涜鍙犲姞锛屽疄鐜板浘鍍忛攼鍖栨晥鏋溿€?
     * 杩欑鏂规硶鑳藉鍦ㄤ繚鎸佸師鍥惧儚淇℃伅鐨勫悓鏃跺寮鸿竟缂樺拰缁嗚妭銆?
     *
     * 澶勭悊娴佺▼锛?
     * 1. 瀵瑰浘鍍忓簲鐢ㄦ媺鏅媺鏂畻瀛?
     * 2. 灏嗘媺鏅媺鏂粨鏋滀笌鍘熷浘鍍忓儚绱犲€肩浉鍔?
     * 3. 闄愬埗缁撴灉鍦ㄦ湁鏁堥鑹茶寖鍥村唴
     *
     * @param src 婧愬浘鍍?
     * @return 鎷夋櫘鎷夋柉鍙犲姞澶勭悊鍚庣殑鍥惧儚
     */
    public BufferedImage laplaceAddProcess(BufferedImage src) {

        // 鎷夋櫘鎷夋柉绠楀瓙
        int[] LAPLACE = new int[] { 0, -1, 0, -1, 4, -1, 0, -1, 0 };

        int width = src.getWidth();
        int height = src.getHeight();

        int[] pixels = new int[width * height];
        int[] outPixels = new int[width * height];

        int type = src.getType();
        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            src.getRaster().getDataElements(0, 0, width, height, pixels);
        }
        src.getRGB(0, 0, width, height, pixels, 0, width);

        int k0 = 0, k1 = 0, k2 = 0;
        int k3 = 0, k4 = 0, k5 = 0;
        int k6 = 0, k7 = 0, k8 = 0;

        k0 = LAPLACE[0];
        k1 = LAPLACE[1];
        k2 = LAPLACE[2];
        k3 = LAPLACE[3];
        k4 = LAPLACE[4];
        k5 = LAPLACE[5];
        k6 = LAPLACE[6];
        k7 = LAPLACE[7];
        k8 = LAPLACE[8];
        int offset = 0;

        int sr = 0, sg = 0, sb = 0;
        int r = 0, g = 0, b = 0;
        for (int row = 1; row < height - 1; row++) {
            offset = row * width;
            for (int col = 1; col < width - 1; col++) {

                r = (pixels[offset + col] >> 16) & 0xff;
                g = (pixels[offset + col] >> 8) & 0xff;
                b = (pixels[offset + col]) & 0xff;
                // red
                sr = k0 * ((pixels[offset - width + col - 1] >> 16) & 0xff)
                        + k1 * ((pixels[offset - width + col] >> 16) & 0xff)
                        + k2
                        * ((pixels[offset - width + col + 1] >> 16) & 0xff)
                        + k3 * ((pixels[offset + col - 1] >> 16) & 0xff) + k4
                        * ((pixels[offset + col] >> 16) & 0xff) + k5
                        * ((pixels[offset + col + 1] >> 16) & 0xff) + k6
                        * ((pixels[offset + width + col - 1] >> 16) & 0xff)
                        + k7 * ((pixels[offset + width + col] >> 16) & 0xff)
                        + k8
                        * ((pixels[offset + width + col + 1] >> 16) & 0xff);
                // green
                sg = k0 * ((pixels[offset - width + col - 1] >> 8) & 0xff) + k1
                        * ((pixels[offset - width + col] >> 8) & 0xff) + k2
                        * ((pixels[offset - width + col + 1] >> 8) & 0xff) + k3
                        * ((pixels[offset + col - 1] >> 8) & 0xff) + k4
                        * ((pixels[offset + col] >> 8) & 0xff) + k5
                        * ((pixels[offset + col + 1] >> 8) & 0xff) + k6
                        * ((pixels[offset + width + col - 1] >> 8) & 0xff) + k7
                        * ((pixels[offset + width + col] >> 8) & 0xff) + k8
                        * ((pixels[offset + width + col + 1] >> 8) & 0xff);
                // blue
                sb = k0 * (pixels[offset - width + col - 1] & 0xff) + k1
                        * (pixels[offset - width + col] & 0xff) + k2
                        * (pixels[offset - width + col + 1] & 0xff) + k3
                        * (pixels[offset + col - 1] & 0xff) + k4
                        * (pixels[offset + col] & 0xff) + k5
                        * (pixels[offset + col + 1] & 0xff) + k6
                        * (pixels[offset + width + col - 1] & 0xff) + k7
                        * (pixels[offset + width + col] & 0xff) + k8
                        * (pixels[offset + width + col + 1] & 0xff);
                // 杩愮畻鍚庣殑鍍忕礌鍊煎拰鍘熷浘鍍忕礌鍙犲姞
                r += sr;
                g += sg;
                b += sb;
                outPixels[offset + col] = (0xff << 24) | (clamp(r) << 16)
                        | (clamp(g) << 8) | clamp(b);

                // next pixel
                r = 0;
                g = 0;
                b = 0;
            }
        }

        BufferedImage dest = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);

        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            dest.getRaster().setDataElements(0, 0, width, height, outPixels);
        } else {
            dest.setRGB(0, 0, width, height, outPixels, 0, width);
        }
        return dest;
    }
    public BufferedImage sobelProcess(BufferedImage src) {

        // Sobel绠楀瓙
        int[] sobel_y = new int[] { -1, -2, -1, 0, 0, 0, 1, 2, 1 };
        int[] sobel_x = new int[] { -1, 0, 1, -2, 0, 2, -1, 0, 1 };

        int width = src.getWidth();
        int height = src.getHeight();

        int[] pixels = new int[width * height];
        int[] outPixels = new int[width * height];

        int type = src.getType();
        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            src.getRaster().getDataElements(0, 0, width, height, pixels);
        }
        src.getRGB(0, 0, width, height, pixels, 0, width);

        int offset = 0;
        int x0 = sobel_x[0];
        int x1 = sobel_x[1];
        int x2 = sobel_x[2];
        int x3 = sobel_x[3];
        int x4 = sobel_x[4];
        int x5 = sobel_x[5];
        int x6 = sobel_x[6];
        int x7 = sobel_x[7];
        int x8 = sobel_x[8];

        int k0 = sobel_y[0];
        int k1 = sobel_y[1];
        int k2 = sobel_y[2];
        int k3 = sobel_y[3];
        int k4 = sobel_y[4];
        int k5 = sobel_y[5];
        int k6 = sobel_y[6];
        int k7 = sobel_y[7];
        int k8 = sobel_y[8];

        int yr = 0, yg = 0, yb = 0;
        int xr = 0, xg = 0, xb = 0;
        int r = 0, g = 0, b = 0;

        for (int row = 1; row < height - 1; row++) {
            offset = row * width;
            for (int col = 1; col < width - 1; col++) {

                // red
                yr = k0 * ((pixels[offset - width + col - 1] >> 16) & 0xff)
                        + k1 * ((pixels[offset - width + col] >> 16) & 0xff)
                        + k2
                        * ((pixels[offset - width + col + 1] >> 16) & 0xff)
                        + k3 * ((pixels[offset + col - 1] >> 16) & 0xff) + k4
                        * ((pixels[offset + col] >> 16) & 0xff) + k5
                        * ((pixels[offset + col + 1] >> 16) & 0xff) + k6
                        * ((pixels[offset + width + col - 1] >> 16) & 0xff)
                        + k7 * ((pixels[offset + width + col] >> 16) & 0xff)
                        + k8
                        * ((pixels[offset + width + col + 1] >> 16) & 0xff);

                xr = x0 * ((pixels[offset - width + col - 1] >> 16) & 0xff)
                        + x1 * ((pixels[offset - width + col] >> 16) & 0xff)
                        + x2
                        * ((pixels[offset - width + col + 1] >> 16) & 0xff)
                        + x3 * ((pixels[offset + col - 1] >> 16) & 0xff) + x4
                        * ((pixels[offset + col] >> 16) & 0xff) + x5
                        * ((pixels[offset + col + 1] >> 16) & 0xff) + x6
                        * ((pixels[offset + width + col - 1] >> 16) & 0xff)
                        + x7 * ((pixels[offset + width + col] >> 16) & 0xff)
                        + x8
                        * ((pixels[offset + width + col + 1] >> 16) & 0xff);

                // green
                yg = k0 * ((pixels[offset - width + col - 1] >> 8) & 0xff) + k1
                        * ((pixels[offset - width + col] >> 8) & 0xff) + k2
                        * ((pixels[offset - width + col + 1] >> 8) & 0xff) + k3
                        * ((pixels[offset + col - 1] >> 8) & 0xff) + k4
                        * ((pixels[offset + col] >> 8) & 0xff) + k5
                        * ((pixels[offset + col + 1] >> 8) & 0xff) + k6
                        * ((pixels[offset + width + col - 1] >> 8) & 0xff) + k7
                        * ((pixels[offset + width + col] >> 8) & 0xff) + k8
                        * ((pixels[offset + width + col + 1] >> 8) & 0xff);

                xg = x0 * ((pixels[offset - width + col - 1] >> 8) & 0xff) + x1
                        * ((pixels[offset - width + col] >> 8) & 0xff) + x2
                        * ((pixels[offset - width + col + 1] >> 8) & 0xff) + x3
                        * ((pixels[offset + col - 1] >> 8) & 0xff) + x4
                        * ((pixels[offset + col] >> 8) & 0xff) + x5
                        * ((pixels[offset + col + 1] >> 8) & 0xff) + x6
                        * ((pixels[offset + width + col - 1] >> 8) & 0xff) + x7
                        * ((pixels[offset + width + col] >> 8) & 0xff) + x8
                        * ((pixels[offset + width + col + 1] >> 8) & 0xff);
                // blue
                yb = k0 * (pixels[offset - width + col - 1] & 0xff) + k1
                        * (pixels[offset - width + col] & 0xff) + k2
                        * (pixels[offset - width + col + 1] & 0xff) + k3
                        * (pixels[offset + col - 1] & 0xff) + k4
                        * (pixels[offset + col] & 0xff) + k5
                        * (pixels[offset + col + 1] & 0xff) + k6
                        * (pixels[offset + width + col - 1] & 0xff) + k7
                        * (pixels[offset + width + col] & 0xff) + k8
                        * (pixels[offset + width + col + 1] & 0xff);

                xb = x0 * (pixels[offset - width + col - 1] & 0xff) + x1
                        * (pixels[offset - width + col] & 0xff) + x2
                        * (pixels[offset - width + col + 1] & 0xff) + x3
                        * (pixels[offset + col - 1] & 0xff) + x4
                        * (pixels[offset + col] & 0xff) + x5
                        * (pixels[offset + col + 1] & 0xff) + x6
                        * (pixels[offset + width + col - 1] & 0xff) + x7
                        * (pixels[offset + width + col] & 0xff) + x8
                        * (pixels[offset + width + col + 1] & 0xff);

                // 绱㈣礉灏旀搴?
                r = (int) Math.sqrt(yr * yr + xr * xr);
                g = (int) Math.sqrt(yg * yg + xg * xg);
                b = (int) Math.sqrt(yb * yb + xb * xb);

                outPixels[offset + col] = (0xff << 24) | (clamp(r) << 16)
                        | (clamp(g) << 8) | clamp(b);
            }
        }

        BufferedImage dest = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);

        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            dest.getRaster().setDataElements(0, 0, width, height, outPixels);
        } else {
            dest.setRGB(0, 0, width, height, outPixels, 0, width);
        }
        return dest;

    }
    /**
     * 鍧囧€兼护娉?*
     */
    public BufferedImage meanValueProcess(BufferedImage src) {

        // 宸茬粡绱㈣礉灏斿鐞嗙殑鍥惧儚
        BufferedImage image = this.sobelProcess(src);

        int width = image.getWidth();
        int height = image.getHeight();

        int[] pixels = new int[width * height];
        int[] outPixels = new int[width * height];

        int type = image.getType();
        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            image.getRaster().getDataElements(0, 0, width, height, pixels);
        }
        image.getRGB(0, 0, width, height, pixels, 0, width);

        // 鍧囧€兼护娉娇鐢ㄧ殑鍗风Н妯℃澘鍗婂緞锛岃繖閲屼娇鐢?*5鍧囧€硷紝鎵€浠ュ崐寰勪娇鐢?
        int radius = 2;
        int total = (2 * radius + 1) * (2 * radius + 1);

        int r = 0, g = 0, b = 0;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int sum = 0;
                for (int i = -radius; i <= radius; i++) {
                    int roffset = row + i;
                    roffset = (roffset < 0) ? 0
                            : (roffset >= height ? height - 1 : roffset);

                    for (int j = -radius; j <= radius; j++) {

                        int coffset = col + j;
                        coffset = (coffset < 0) ? 0
                                : (coffset >= width ? width - 1 : coffset);

                        int pixel = pixels[roffset * width + coffset];

                        r = (pixel >> 16) & 0XFF;

                        sum += r;
                    }
                }

                r = sum / total;
                g = sum / total;
                b = sum / total;

                outPixels[row * width + col] = (255 << 24) | (clamp(r) << 16)
                        | (clamp(g) << 8) | clamp(b);
            }
        }

        BufferedImage dest = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);

        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            dest.getRaster().setDataElements(0, 0, width, height, outPixels);
        } else {
            dest.setRGB(0, 0, width, height, outPixels, 0, width);
        }

        return dest;
    }
    /**
     * 鏁板杩愮畻
     */
    public BufferedImage mathProcess(BufferedImage src) {

        // 鑾峰彇缁忔媺鏅媺鏂繍绠楀悗涓庡師鍥惧彔鍔犵殑鍥剧墖
        BufferedImage lapsImage = this.laplaceAddProcess(src);

        // 鑾峰彇绱㈣礉灏?*5鍧囧€兼护娉㈠悗鐨勫浘鍍?
        BufferedImage meanImage = this.meanValueProcess(src);

        int type = src.getType();
        int width = src.getWidth();
        int height = src.getHeight();

        // 鍘熷鍥惧儚鐨勫儚绱犱俊鎭?
        int[] pixels = new int[width * height];
        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            src.getRaster().getDataElements(0, 0, width, height, pixels);
        }
        src.getRGB(0, 0, width, height, pixels, 0, width);

        // 鎷夋櫘鎷夋柉閿愬寲鍚庣殑鍍忕礌淇℃伅
        int[] lapsPixels = new int[width * height];
        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            lapsImage.getRaster().getDataElements(0, 0, width, height,
                    lapsPixels);
        }
        lapsImage.getRGB(0, 0, width, height, lapsPixels, 0, width);

        // Sobel鍜屽潎鍊兼护娉㈠悗鐨勫儚绱犱俊鎭?
        int[] meanPixels = new int[width * height];
        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            meanImage.getRaster().getDataElements(0, 0, width, height,
                    meanPixels);
        }
        meanImage.getRGB(0, 0, width, height, meanPixels, 0, width);

        int[] outPixels = new int[width * height];

        // 鍥惧儚鐩镐箻
        int lr = 0, lg = 0, lb = 0;
        int mr = 0, mg = 0, mb = 0;
        int or = 0, og = 0, ob = 0;
        int r = 0, g = 0, b = 0;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int lpixel = lapsPixels[row * width + col];
                int mpixel = meanPixels[row * width + col];

                // 鍘熷鍥惧儚
                int opixel = pixels[row * width + col];

                lr = (lpixel >> 16) & 0XFF;
                mr = (mpixel >> 16) & 0XFF;
                or = (opixel >> 16) & 0XFF;

                lg = (lpixel >> 8) & 0XFF;
                mg = (mpixel >> 8) & 0XFF;
                og = (opixel >> 8) & 0XFF;

                lb = (lpixel) & 0XFF;
                mb = (mpixel) & 0XFF;
                ob = (opixel) & 0XFF;

                /** 鍥惧儚鐩镐箻 鏍囧畾鍒?~255 */
                r = (lr * mr) / 255;
                g = (lg * mg) / 255;
                b = (lb * mb) / 255;

                // 鐩镐箻鍚庡浘鍍忎笌鍘熷浘鐩稿姞
                r = r + or;
                g = g + og;
                b = b + ob;

                outPixels[row * width + col] = (255 << 24) | (clamp(r) << 16)
                        | (clamp(g) << 8) | (clamp(b));
            }
        }

        BufferedImage dest = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);

        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            dest.getRaster().setDataElements(0, 0, width, height, outPixels);
        } else {
            dest.setRGB(0, 0, width, height, outPixels, 0, width);
        }


        return dest;
    }

    private int clamp(int value) {
        return value > 255 ? 255 : (value < 0 ? 0 : value);
    }
    /**
     * 浼介┈鍙樺寲
     */
    public BufferedImage gammaProcess(BufferedImage src) {

        BufferedImage image = this.mathProcess(src);

        // 骞傜骇鏁?
        // 浼介┈鍊?(gamma) = 0.5;
        double gamma = 0.5;

        int type = image.getType();
        int width = src.getWidth();
        int height = src.getHeight();

        // 缁忚繃鏁板鍙樻崲鍚庣殑鍍忕礌淇℃伅
        int[] pixels = new int[width * height];
        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            image.getRaster().getDataElements(0, 0, width, height, pixels);
        }
        image.getRGB(0, 0, width, height, pixels, 0, width);

        int[] outPixels = new int[width * height];

        // 寤虹珛LUT鏌ユ壘琛?
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {

            float f = (float) (i / 255.0);
            f = (float) Math.pow(f, gamma);

            lut[i] = (int) (f * 255.0);
        }

        int r = 0, g = 0, b = 0;
        int or = 0, og = 0, ob = 0;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {

                int pixel = pixels[row * width + col];

                r = (pixel >> 16) & 0XFF;
                g = (pixel >> 8) & 0XFF;
                b = (pixel) & 0XFF;

                or = lut[r];
                og = lut[g];
                ob = lut[b];

                outPixels[row * width + col] = (255 << 24) | (clamp(or) << 16)
                        | (clamp(og) << 8) | (clamp(ob));

            }
        }

        BufferedImage dest = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);
        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            dest.getRaster().setDataElements(0, 0, width, height, outPixels);
        } else {
            dest.setRGB(0, 0, width, height, outPixels, 0, width);
        }

        return dest;
    }
}

