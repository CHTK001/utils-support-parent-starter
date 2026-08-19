package com.chua.image.support.filter;


import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.utils.BufferedImageUtils;

import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * USM (Unsharp Mask) 閿愬寲婊ら暅
 *
 * USM 鏄竴绉嶇粡鍏哥殑鍥惧儚閿愬寲鎶€鏈紝閫氳繃浠庡師鍥惧儚涓噺鍘绘ā绯婄増鏈潵澧炲己杈圭紭鍜岀粏鑺傘€?
 * 璇ユ妧鏈渶鍒濇潵婧愪簬浼犵粺鎽勫奖鐨勬殫鎴挎妧鏈紝鐜板湪骞挎硾搴旂敤浜庢暟瀛楀浘鍍忓鐞嗐€?
 *
 * 鎶€鏈師鐞嗭細
 * - 鍒涘缓鍘熷浘鍍忕殑楂樻柉妯＄硦鐗堟湰
 * - 璁＄畻鍘熷浘鍍忎笌妯＄硦鍥惧儚鐨勫樊鍊硷紙鍙嶉攼鍖栨帺妯★級
 * - 灏嗗樊鍊兼寜鏉冮噸鍔犲洖鍘熷浘鍍?
 * - 鍏紡锛氶攼鍖栧浘鍍?= 鍘熷浘鍍?+ 鏉冮噸 脳 (鍘熷浘鍍?- 妯＄硦鍥惧儚)
 *
 * 绠楁硶娴佺▼锛?
 * 1. 瀵瑰師鍥惧儚搴旂敤楂樻柉妯＄硦
 * 2. 璁＄畻鍘熷浘鍍忎笌妯＄硦鍥惧儚鐨勫儚绱犲樊鍊?
 * 3. 灏嗗樊鍊间箻浠ユ潈閲嶇郴鏁?
 * 4. 灏嗗姞鏉冨樊鍊兼坊鍔犲埌鍘熷浘鍍?
 *
 * 鍙傛暟璇存槑锛?
 * - weight: 閿愬寲寮哄害鏉冮噸锛岃寖鍥撮€氬父涓?.1-2.0
 *   - 0.1-0.5: 杞诲井閿愬寲锛岄€傚悎浜哄儚
 *   - 0.5-1.0: 涓瓑閿愬寲锛岄€傚悎涓€鑸収鐗?
 *   - 1.0-2.0: 寮虹儓閿愬寲锛岄€傚悎椋庢櫙鎴栭渶瑕佺獊鍑虹粏鑺傜殑鍥惧儚
 *
 * 瑙嗚鏁堟灉锛?
 * - 澧炲己鍥惧儚杈圭紭鍜岀粏鑺?
 * - 鎻愰珮鍥惧儚鐨勮瑙夋竻鏅板害
 * - 涓嶄細寮曞叆鏄庢樉鐨勪吉褰憋紙鐩告瘮绠€鍗曢攼鍖栵級
 * - 淇濇寔鍥惧儚鐨勮嚜鐒跺瑙?
 *
 * 搴旂敤鍦烘櫙锛?
 * - 鐓х墖鍚庢湡澶勭悊锛氭彁鍗囩収鐗囨竻鏅板害
 * - 鍗板埛鍑嗗锛氫负鍗板埛杈撳嚭浼樺寲鍥惧儚
 * - 鎵弿鍥惧儚澶勭悊锛氭敼鍠勬壂鎻忓浘鍍忚川閲?
 * - 鏁板瓧鎽勫奖锛氳ˉ鍋块暅澶存垨浼犳劅鍣ㄧ殑杞诲井妯＄硦
 * - 鍥惧儚缂╂斁锛氬噺灏戠缉鏀惧悗鐨勬ā绯婃晥鏋?
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@SpiDescribe("USM閿愬寲婊ら暅")
@Spi("usm")
public class ImageUsmFilterImage extends ImageGaussianBlurFilter {

    /**
     * USM 閿愬寲鏉冮噸锛屾帶鍒堕攼鍖栧己搴?
     */
    private double weight;

    /**
     * 榛樿鏋勯€犲嚱鏁?
     *
     * 浣跨敤榛樿鐨勯攼鍖栨潈閲?0.6锛岄€傚悎澶у鏁板浘鍍忕殑涓瓑寮哄害閿愬寲銆?
     */
    public ImageUsmFilterImage() {
        this.weight = 0.6;
    }

    /**
     * 甯︽潈閲嶅弬鏁扮殑鏋勯€犲嚱鏁?
     *
     * @param weight 閿愬寲鏉冮噸锛屽缓璁寖鍥?0.1-2.0
     *               - 0.1-0.5: 杞诲井閿愬寲
     *               - 0.5-1.0: 涓瓑閿愬寲
     *               - 1.0-2.0: 寮虹儓閿愬寲
     */
    public ImageUsmFilterImage(double weight) {
        this.weight = weight;
    }

    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        initial(src);
        int total = width * height;
        byte[] r1 = new byte[total];
        byte[] g1 = new byte[total];
        byte[] b1 = new byte[total];
        System.arraycopy(rArr, 0, r1, 0, total);
        System.arraycopy(gArr, 0, g1, 0, total);
        System.arraycopy(bArr, 0, b1, 0, total);
        byte[][] output = new byte[3][total];
        // 楂樻柉妯＄硦
        super.filter(src, dst);
        int r = 0;
        int g = 0;
        int b = 0;

        int r11 = 0;
        int g11 = 0;
        int b11 = 0;

        int r2 = 0;
        int g2 = 0;
        int b2 = 0;

        for (int i = 0; i < total; i++) {
            r11 = r1[i] & 0xff;
            g11 = g1[i] & 0xff;
            b11 = b1[i] & 0xff;

            r2 = rArr[i] & 0xff;
            g2 = gArr[i] & 0xff;
            b2 = bArr[i] & 0xff;

            r = (int) ((r11 - weight * r2) / (1 - weight));
            g = (int) ((g11 - weight * g2) / (1 - weight));
            b = (int) ((b11 - weight * b2) / (1 - weight));

            output[0][i] = (byte) BufferedImageUtils.clamp(r);
            output[1][i] = (byte) BufferedImageUtils.clamp(g);
            output[2][i] = (byte) BufferedImageUtils.clamp(b);
        }

        putRgb(output[0], output[1], output[2]);
        return toBitmap();
    }


}
