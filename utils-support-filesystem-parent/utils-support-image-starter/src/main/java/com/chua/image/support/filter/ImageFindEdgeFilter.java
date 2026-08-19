package com.chua.image.support.filter;


import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.utils.BufferedImageUtils;

import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 缁煎悎杈圭紭妫€娴嬪浘鍍忔护闀?
 *
 * 鍩轰簬 Sobel 绠楀瓙鐨勫弻鍚戣竟缂樻娴嬫护闀滐紝鍚屾椂璁＄畻姘村钩鍜屽瀭鐩存柟鍚戠殑姊害锛?
 * 閫氳繃姊害骞呭€兼潵妫€娴嬪浘鍍忎腑鐨勮竟缂樸€傜浉姣斿崟鍚?Sobel 婊ら暅锛岃兘澶熸娴?
 * 浠绘剰鏂瑰悜鐨勮竟缂橈紝鎻愪緵鏇村畬鏁寸殑杈圭紭淇℃伅銆?
 *
 * 鎶€鏈師鐞嗭細
 * - 鍚屾椂搴旂敤姘村钩鍜屽瀭鐩?Sobel 绠楀瓙
 * - 璁＄畻涓や釜鏂瑰悜鐨勬搴﹀垎閲?Gx 鍜?Gy
 * - 閫氳繃姊害骞呭€?|G| = 鈭?Gx虏 + Gy虏) 纭畾杈圭紭寮哄害
 * - 鍙€夋嫨鎬ц绠楁搴︽柟鍚?胃 = arctan(Gy/Gx)
 *
 * Sobel 绠楀瓙锛?
 * 姘村钩鏂瑰悜锛堟娴嬪瀭鐩磋竟缂橈級锛?   鍨傜洿鏂瑰悜锛堟娴嬫按骞宠竟缂橈級锛?
 * [-1 -2 -1]                    [-1  0  1]
 * [ 0  0  0]                    [-2  0  2]
 * [ 1  2  1]                    [-1  0  1]
 *
 * 绠楁硶浼樺娍锛?
 * - 鍏ㄦ柟鍚戣竟缂樻娴嬶細鑳芥娴嬩换鎰忔柟鍚戠殑杈圭紭
 * - 杈圭紭寮哄害閲忓寲锛氭彁渚涜竟缂樼殑寮哄害淇℃伅
 * - 鍣０鎶戝埗锛歋obel 绠楀瓙鍏锋湁涓€瀹氱殑骞虫粦鏁堟灉
 * - 璁＄畻鏁堢巼锛氫娇鐢ㄦ暣鏁拌繍绠楋紝璁＄畻閫熷害蹇?
 *
 * 搴旂敤鍦烘櫙锛?
 * - 鍥惧儚鍒嗘瀽锛氭彁鍙栧浘鍍忕殑缁撴瀯淇℃伅
 * - 鐗瑰緛妫€娴嬶細涓哄悗缁鐞嗘彁渚涜竟缂樼壒寰?
 * - 鍥惧儚鍒嗗壊锛氬熀浜庤竟缂樹俊鎭繘琛屽尯鍩熷垎鍓?
 * - 鐩爣璇嗗埆锛氱墿浣撹疆寤撴彁鍙栧拰璇嗗埆
 * - 鍖诲褰卞儚锛氬尰瀛﹀浘鍍忕殑杈圭紭澧炲己鍜屽垎鏋?
 * - 宸ヤ笟妫€娴嬶細浜у搧杈圭紭璐ㄩ噺妫€娴?
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@Spi("FindEdge")
@SpiDescribe("缁煎悎杈圭紭妫€娴嬫护闀?)
public class ImageFindEdgeFilter extends AbstractImageFilter {

    /**
     * 鐢ㄤ簬杈圭紭妫€娴嬬殑姘村钩Sobel绠楀瓙婊ゆ尝鍣?
     * <p>
     * 杩欐槸涓€涓?x3鐨勬护娉㈠櫒:<br>
     * -1 -2 -1 <br>
     * 0  0  0 <br>
     * 1  2  1 <br>
     */
    public static final int[] SOBEL_X = new int[]{-1, -2, -1, 0, 0, 0, 1, 2, 1};
    
    /**
     * 鐢ㄤ簬杈圭紭妫€娴嬬殑鍨傜洿Sobel绠楀瓙婊ゆ尝鍣?
     * <p>
     * 杩欐槸涓€涓?x3鐨勬护娉㈠櫒:<br>
     * -1  0  1 <br>
     * -2  0  2 <br>
     * -1  0  1 <br>
     */
    public static final int[] SOBEL_Y = new int[]{-1, 0, 1, -2, 0, 2, -1, 0, 1};

    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        // 璁＄畻鍥惧儚鎬诲儚绱犳暟
        int total = width * height;
        // 鍒涘缓杈撳嚭鏁扮粍锛屽瓨鍌ㄥ鐞嗗悗鐨凴GB鍊?
        byte[][] output = new byte[3][total];

        // 鍋忕Щ閲忥紝璁＄畻褰撳墠琛屽湪鏁扮粍涓殑浣嶇疆
        int offset = 0;
        
        // 鎻愬彇SOBEL_X婊ゆ尝鍣ㄧ殑鍚勪釜绯绘暟
        int x0 = SOBEL_X[0];
        int x1 = SOBEL_X[1];
        int x2 = SOBEL_X[2];
        int x3 = SOBEL_X[3];
        int x4 = SOBEL_X[4];
        int x5 = SOBEL_X[5];
        int x6 = SOBEL_X[6];
        int x7 = SOBEL_X[7];
        int x8 = SOBEL_X[8];

        // 鎻愬彇SOBEL_Y婊ゆ尝鍣ㄧ殑鍚勪釜绯绘暟
        int k0 = SOBEL_Y[0];
        int k1 = SOBEL_Y[1];
        int k2 = SOBEL_Y[2];
        int k3 = SOBEL_Y[3];
        int k4 = SOBEL_Y[4];
        int k5 = SOBEL_Y[5];
        int k6 = SOBEL_Y[6];
        int k7 = SOBEL_Y[7];
        int k8 = SOBEL_Y[8];

        // 瀛樺偍Y鏂瑰悜鍜孹鏂瑰悜鐨凴GB鍒嗛噺鍗风Н缁撴灉
        int yr = 0, yg = 0, yb = 0;
        int xr = 0, xg = 0, xb = 0;
        // 瀛樺偍鏈€缁堢殑RGB鍒嗛噺鍊?
        int r = 0, g = 0, b = 0;
        
        // 閬嶅巻鍥惧儚鐨勬瘡涓儚绱狅紙璺宠繃杈圭晫锛?
        for (int row = 1; row < height - 1; row++) {
            // 璁＄畻褰撳墠琛岀殑鍋忕Щ閲?
            offset = row * width;
            for (int col = 1; col < width - 1; col++) {
                // 瀵圭孩鑹插垎閲忚繘琛屽嵎绉繍绠?
                yr = k0 * (rArr[offset - width + col - 1] & 0xff) + 
                     k1 * (rArr[offset - width + col] & 0xff) + 
                     k2 * (rArr[offset - width + col + 1] & 0xff) + 
                     k3 * (rArr[offset + col - 1] & 0xff) + 
                     k4 * (rArr[offset + col] & 0xff) + 
                     k5 * (rArr[offset + col + 1] & 0xff) + 
                     k6 * (rArr[offset + width + col - 1] & 0xff) + 
                     k7 * (rArr[offset + width + col] & 0xff) + 
                     k8 * (rArr[offset + width + col + 1] & 0xff);

                xr = x0 * (rArr[offset - width + col - 1] & 0xff) + 
                     x1 * (rArr[offset - width + col] & 0xff) + 
                     x2 * (rArr[offset - width + col + 1] & 0xff) + 
                     x3 * (rArr[offset + col - 1] & 0xff) + 
                     x4 * (rArr[offset + col] & 0xff) + 
                     x5 * (rArr[offset + col + 1] & 0xff) + 
                     x6 * (rArr[offset + width + col - 1] & 0xff) + 
                     x7 * (rArr[offset + width + col] & 0xff) + 
                     x8 * (rArr[offset + width + col + 1] & 0xff);

                // 瀵圭豢鑹插垎閲忚繘琛屽嵎绉繍绠?
                yg = k0 * (gArr[offset - width + col - 1] & 0xff) + 
                     k1 * (gArr[offset - width + col] & 0xff) + 
                     k2 * (gArr[offset - width + col + 1] & 0xff) + 
                     k3 * (gArr[offset + col - 1] & 0xff) + 
                     k4 * (gArr[offset + col] & 0xff) + 
                     k5 * (gArr[offset + col + 1] & 0xff) + 
                     k6 * (gArr[offset + width + col - 1] & 0xff) + 
                     k7 * (gArr[offset + width + col] & 0xff) + 
                     k8 * (gArr[offset + width + col + 1] & 0xff);

                xg = x0 * (gArr[offset - width + col - 1] & 0xff) + 
                     x1 * (gArr[offset - width + col] & 0xff) + 
                     x2 * (gArr[offset - width + col + 1] & 0xff) + 
                     x3 * (gArr[offset + col - 1] & 0xff) + 
                     x4 * (gArr[offset + col] & 0xff) + 
                     x5 * (gArr[offset + col + 1] & 0xff) + 
                     x6 * (gArr[offset + width + col - 1] & 0xff) + 
                     x7 * (gArr[offset + width + col] & 0xff) + 
                     x8 * (gArr[offset + width + col + 1] & 0xff);
                
                // 瀵硅摑鑹插垎閲忚繘琛屽嵎绉繍绠?
                yb = k0 * (bArr[offset - width + col - 1] & 0xff) + 
                     k1 * (bArr[offset - width + col] & 0xff) + 
                     k2 * (bArr[offset - width + col + 1] & 0xff) + 
                     k3 * (bArr[offset + col - 1] & 0xff) + 
                     k4 * (bArr[offset + col] & 0xff) + 
                     k5 * (bArr[offset + col + 1] & 0xff) + 
                     k6 * (bArr[offset + width + col - 1] & 0xff) + 
                     k7 * (bArr[offset + width + col] & 0xff) + 
                     k8 * (bArr[offset + width + col + 1] & 0xff);

                xb = x0 * (bArr[offset - width + col - 1] & 0xff) + 
                     x1 * (bArr[offset - width + col] & 0xff) + 
                     x2 * (bArr[offset - width + col + 1] & 0xff) + 
                     x3 * (bArr[offset + col - 1] & 0xff) + 
                     x4 * (bArr[offset + col] & 0xff) + 
                     x5 * (bArr[offset + col + 1] & 0xff) + 
                     x6 * (bArr[offset + width + col - 1] & 0xff) + 
                     x7 * (bArr[offset + width + col] & 0xff) + 
                     x8 * (bArr[offset + width + col + 1] & 0xff);

                // 璁＄畻姊害骞呭€?
                r = (int) Math.sqrt(yr * yr + xr * xr);
                g = (int) Math.sqrt(yg * yg + xg * xg);
                b = (int) Math.sqrt(yb * yb + xb * xb);

                // 灏嗙粨鏋滈檺鍒跺湪0-255鑼冨洿鍐呭苟瀛樺偍鍒拌緭鍑烘暟缁?
                output[0][offset + col] = (byte) BufferedImageUtils.clamp(r);
                output[1][offset + col] = (byte) BufferedImageUtils.clamp(g);
                output[2][offset + col] = (byte) BufferedImageUtils.clamp(b);

                // 璁＄畻姊害瑙掑害锛堣繖閮ㄥ垎璁＄畻鍦ㄥ綋鍓嶄唬鐮佷腑鏈浣跨敤锛?
                double dy = (yr + yg + yb);
                double dx = (xr + xg + xb);
                double theta = Math.atan(dy / dx);

                // 閲嶇疆鍙橀噺锛屼负涓嬩竴涓儚绱犲仛鍑嗗
                yr = 0;
                yg = 0;
                yb = 0;
                xr = 0;
                xg = 0;
                xb = 0;
            }
        }
        // 灏嗗鐞嗗悗鐨凴GB鏁版嵁鏀惧洖鍥惧儚
        putRgb(output[0], output[1], output[2]);
        // 杩斿洖澶勭悊鍚庣殑鍥惧儚
        return toBitmap();
    }
}
