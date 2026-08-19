package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.utils.BufferedImageUtils;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * Sobel 杈圭紭妫€娴嬪浘鍍忔护闀?
 *
 * 瀹炵幇 Sobel 绠楀瓙杩涜杈圭紭妫€娴嬶紝閫氳繃璁＄畻鍥惧儚姊害鏉ヨ瘑鍒拰绐佸嚭鏄剧ず鍥惧儚涓殑杈圭紭銆?
 * Sobel 绠楀瓙鏄竴绉嶇粡鍏哥殑杈圭紭妫€娴嬬畻娉曪紝鍦ㄨ绠楁満瑙嗚鍜屽浘鍍忓鐞嗕腑骞挎硾搴旂敤銆?
 *
 * 鎶€鏈師鐞嗭細
 * - 浣跨敤 3x3 鍗风Н鏍歌绠楀浘鍍忔搴?
 * - 鍒嗗埆璁＄畻姘村钩鏂瑰悜锛圶鏂瑰悜锛夊拰鍨傜洿鏂瑰悜锛圷鏂瑰悜锛夌殑姊害
 * - 閫氳繃涓€闃跺鏁拌繎浼兼娴嬭竟缂?
 * - 瀵瑰櫔澹板叿鏈変竴瀹氱殑鎶戝埗鑳藉姏
 *
 * Sobel 绠楀瓙锛?
 * X鏂瑰悜锛堟按骞宠竟缂樻娴嬶級锛?
 * [-1  0  1]
 * [-2  0  2]
 * [-1  0  1]
 *
 * Y鏂瑰悜锛堝瀭鐩磋竟缂樻娴嬶級锛?
 * [-1 -2 -1]
 * [ 0  0  0]
 * [ 1  2  1]
 *
 * 绠楁硶鐗圭偣锛?
 * - 璁＄畻鏁堢巼楂橈紝閫傚悎瀹炴椂澶勭悊
 * - 瀵瑰櫔澹版湁涓€瀹氱殑骞虫粦浣滅敤
 * - 鑳藉妫€娴嬩笉鍚屾柟鍚戠殑杈圭紭
 * - 杈圭紭瀹氫綅绮惧害杈冨ソ
 *
 * 搴旂敤鍦烘櫙锛?
 * - 杈圭紭妫€娴嬶細璇嗗埆鍥惧儚涓殑鐗╀綋杞粨
 * - 鐗瑰緛鎻愬彇锛氫负鍚庣画鍥惧儚鍒嗘瀽鎻愪緵鐗瑰緛
 * - 鍥惧儚鍒嗗壊锛氬熀浜庤竟缂樹俊鎭繘琛屽尯鍩熷垎鍓?
 * - 鐩爣璇嗗埆锛氳緟鍔╃墿浣撹瘑鍒拰璺熻釜
 * - 鍖诲褰卞儚锛氬尰瀛﹀浘鍍忕殑杈圭紭澧炲己
 * - 宸ヤ笟妫€娴嬶細浜у搧璐ㄩ噺妫€娴嬩腑鐨勮竟缂樺垎鏋?
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@SpiDescribe("Sobel杈圭紭妫€娴嬫护闀?)
@Spi("sobel")
@AllArgsConstructor
@NoArgsConstructor
public class ImageSobelFilter extends AbstractImageFilter {

    /**
     * Sobel Y鏂瑰悜锛堝瀭鐩磋竟缂樻娴嬶級鍗风Н鏍?
     */
    public static int[] sobelY = new int[]{-1, -2, -1, 0, 0, 0, 1, 2, 1};

    /**
     * Sobel X鏂瑰悜锛堟按骞宠竟缂樻娴嬶級鍗风Н鏍?
     */
    public static int[] sobelX = new int[]{-1, 0, 1, -2, 0, 2, -1, 0, 1};

    /**
     * 鏄惁浣跨敤X鏂瑰悜妫€娴嬶紝true涓篨鏂瑰悜锛堟娴嬪瀭鐩磋竟缂橈級锛宖alse涓篩鏂瑰悜锛堟娴嬫按骞宠竟缂橈級
     */
    private boolean xdirect = true;

    /**
     * 鎵ц Sobel 杈圭紭妫€娴嬫护闀滃鐞?
     *
     * 瀵瑰浘鍍忓簲鐢?Sobel 绠楀瓙杩涜杈圭紭妫€娴嬨€傛牴鎹?xdirect 鍙傛暟閫夋嫨妫€娴嬫柟鍚戯細
     * - true锛氫娇鐢?X 鏂瑰悜绠楀瓙锛屾娴嬪瀭鐩磋竟缂?
     * - false锛氫娇鐢?Y 鏂瑰悜绠楀瓙锛屾娴嬫按骞宠竟缂?
     *
     * @param src 婧愬浘鍍?
     * @param dst 鐩爣鍥惧儚锛堟鍙傛暟鏈娇鐢級
     * @return 杈圭紭妫€娴嬪悗鐨勫浘鍍?
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int total = width * height;
        // RGB涓変釜閫氶亾鐨勮緭鍑烘暟缁?
        byte[][] output = new byte[3][total];

        int offset = 0;
        // Sobel 鍗风Н鏍哥殑9涓郴鏁?
        int k0 = 0, k1 = 0, k2 = 0;
        int k3 = 0, k4 = 0, k5 = 0;
        int k6 = 0, k7 = 0, k8 = 0;

        // 鏍规嵁妫€娴嬫柟鍚戦€夋嫨鐩稿簲鐨?Sobel 绠楀瓙
        if (xdirect) {
            // X鏂瑰悜绠楀瓙锛氭娴嬪瀭鐩磋竟缂?
            k0 = sobelX[0]; k1 = sobelX[1]; k2 = sobelX[2];
            k3 = sobelX[3]; k4 = sobelX[4]; k5 = sobelX[5];
            k6 = sobelX[6]; k7 = sobelX[7]; k8 = sobelX[8];
        } else {
            // Y鏂瑰悜绠楀瓙锛氭娴嬫按骞宠竟缂?
            k0 = sobelY[0]; k1 = sobelY[1]; k2 = sobelY[2];
            k3 = sobelY[3]; k4 = sobelY[4]; k5 = sobelY[5];
            k6 = sobelY[6]; k7 = sobelY[7]; k8 = sobelY[8];
        }

        // 姊害璁＄畻缁撴灉
        int sr = 0, sg = 0, sb = 0;
        int r = 0, g = 0, b = 0;

        // 閬嶅巻鍥惧儚鍍忕礌锛堣烦杩囪竟鐣屽儚绱狅紝鍥犱负闇€瑕?x3閭诲煙锛?
        for (int row = 1; row < height - 1; row++) {
            offset = row * width;
            for (int col = 1; col < width - 1; col++) {

        // 璁＄畻绾㈣壊閫氶亾鐨?Sobel 姊害锛屾柟鍚戦『搴忥細宸︿笂銆佷笂銆佸彸涓娿€佸乏銆佷腑蹇冦€佸彸銆佸乏涓嬨€佷笅銆佸彸涓?
        sr = k0 * (rArr[offset - width + col - 1] & 0xff)
                + k1 * (rArr[offset - width + col] & 0xff)
                + k2 * (rArr[offset - width + col + 1] & 0xff)
                + k3 * (rArr[offset + col - 1] & 0xff)
                + k4 * (rArr[offset + col] & 0xff)
                + k5 * (rArr[offset + col + 1] & 0xff)
                + k6 * (rArr[offset + width + col - 1] & 0xff)
                + k7 * (rArr[offset + width + col] & 0xff)
                + k8 * (rArr[offset + width + col + 1] & 0xff);

                // 璁＄畻缁胯壊閫氶亾鐨?Sobel 姊害
                sg = k0 * (gArr[offset - width + col - 1] & 0xff)
                        + k1 * (gArr[offset - width + col] & 0xff)
                        + k2 * (gArr[offset - width + col + 1] & 0xff)
                        + k3 * (gArr[offset + col - 1] & 0xff)
                        + k4 * (gArr[offset + col] & 0xff)
                        + k5 * (gArr[offset + col + 1] & 0xff)
                        + k6 * (gArr[offset + width + col - 1] & 0xff)
                        + k7 * (gArr[offset + width + col] & 0xff)
                        + k8 * (gArr[offset + width + col + 1] & 0xff);

                // 璁＄畻钃濊壊閫氶亾鐨?Sobel 姊害
                sb = k0 * (bArr[offset - width + col - 1] & 0xff)
                        + k1 * (bArr[offset - width + col] & 0xff)
                        + k2 * (bArr[offset - width + col + 1] & 0xff)
                        + k3 * (bArr[offset + col - 1] & 0xff)
                        + k4 * (bArr[offset + col] & 0xff)
                        + k5 * (bArr[offset + col + 1] & 0xff)
                        + k6 * (bArr[offset + width + col - 1] & 0xff)
                        + k7 * (bArr[offset + width + col] & 0xff)
                        + k8 * (bArr[offset + width + col + 1] & 0xff);

                // 淇濆瓨姊害璁＄畻缁撴灉
                r = sr;
                g = sg;
                b = sb;

                // 灏嗙粨鏋滈檺鍒跺湪鏈夋晥鑼冨洿鍐呭苟瀛樺偍
                output[0][offset + col] = (byte) BufferedImageUtils.clamp(r);
                output[1][offset + col] = (byte) BufferedImageUtils.clamp(g);
                output[2][offset + col] = (byte) BufferedImageUtils.clamp(b);

                // 閲嶇疆姊害鍊硷紝鍑嗗澶勭悊涓嬩竴涓儚绱?
                sr = 0;
                sg = 0;
                sb = 0;
            }
        }

        // 灏嗗鐞嗗悗鐨凴GB鏁版嵁璁剧疆鍥炲浘鍍?
        putRgb(output[0], output[1], output[2]);
        return toBitmap();
    }
}
