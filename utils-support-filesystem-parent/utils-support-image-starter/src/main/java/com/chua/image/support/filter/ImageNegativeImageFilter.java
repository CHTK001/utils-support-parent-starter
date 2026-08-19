package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 璐熺墖鏁堟灉鍥惧儚婊ら暅
 *
 * 瀹炵幇缁忓吀鐨勮礋鐗囷紙鍙嶈浆锛夋晥鏋滐紝閫氳繃灏嗘瘡涓儚绱犵殑RGB鍊艰繘琛屽弽杞搷浣滐紝
 * 鍒涘缓绫讳技鑳剁墖璐熺墖鐨勮瑙夋晥鏋溿€?
 *
 * 鎶€鏈師鐞嗭細
 * - 瀵规瘡涓鑹查€氶亾鎵ц鍙嶈浆鎿嶄綔锛氭柊鍊?= 255 - 鍘熷€?
 * - 淇濇寔Alpha閫氶亾涓嶅彉锛堝鏋滃瓨鍦級
 * - 浜殑鍖哄煙鍙樻殫锛屾殫鐨勫尯鍩熷彉浜?
 * - 棰滆壊鍙樹负鍏惰ˉ鑹?
 *
 * 鏁板鍏紡锛?
 * - R' = 255 - R
 * - G' = 255 - G
 * - B' = 255 - B
 *
 * 瑙嗚鏁堟灉锛?
 * - 鐧借壊鍙樹负榛戣壊锛岄粦鑹插彉涓虹櫧鑹?
 * - 绾㈣壊鍙樹负闈掕壊锛岀豢鑹插彉涓哄搧绾㈣壊锛岃摑鑹插彉涓洪粍鑹?
 * - 鍒涘缓瓒呯幇瀹炵殑鑹烘湳鏁堟灉
 * - 绐佸嚭鍥惧儚鐨勮疆寤撳拰缁撴瀯
 *
 * 搴旂敤鍦烘櫙锛?
 * - 鑹烘湳鎽勫奖锛氬垱寤虹嫭鐗圭殑瑙嗚鏁堟灉
 * - 鍥惧儚鍒嗘瀽锛氱獊鍑烘樉绀哄浘鍍忕壒寰?
 * - 鍒涙剰璁捐锛氬埗浣滅壒娈婄殑瑙嗚鍏冪礌
 * - 鍖诲褰卞儚锛氭煇浜涘尰瀛﹀浘鍍忕殑鏄剧ず闇€姹?
 * - 澶滆鏁堟灉锛氭ā鎷熷瑙嗚澶囩殑鏄剧ず鏁堟灉
 *
 * 娉ㄦ剰锛氬綋鍓嶅疄鐜板垱寤虹殑鏄伆搴﹀浘鍍忥紝濡傞渶淇濇寔褰╄壊鏁堟灉锛?
 * 搴斾娇鐢═YPE_INT_RGB鑰屼笉鏄疶YPE_BYTE_GRAY銆?
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@SpiDescribe("璐熺墖鍙嶈浆婊ら暅")
@Spi("negative")
public class ImageNegativeImageFilter extends AbstractImageFilter {

    /**
     * 鎵ц璐熺墖婊ら暅澶勭悊
     *
     * 瀵瑰浘鍍忕殑姣忎釜鍍忕礌杩涜棰滆壊鍙嶈浆鎿嶄綔锛屽皢RGB鍊艰浆鎹负鍏惰ˉ鑹层€?
     *
     * @param src 婧愬浘鍍?
     * @param dst 鐩爣鍥惧儚锛堟鍙傛暟鏈娇鐢級
     * @return 搴旂敤璐熺墖鏁堟灉鍚庣殑鍥惧儚
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        // 娉ㄦ剰锛氳繖閲屽簲璇ヤ娇鐢═YPE_INT_RGB鏉ヤ繚鎸佸僵鑹叉晥鏋?
        BufferedImage negativeImage = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);

        int width = src.getWidth();
        int height = src.getHeight();

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int pixelVal = src.getRGB(x, y);

                // 鎻愬彇RGB鍒嗛噺
                int red = (pixelVal >> 16) & 0xFF;
                int green = (pixelVal >> 8) & 0xFF;
                int blue = pixelVal & 0xFF;

                // 鎵ц棰滆壊鍙嶈浆锛氭柊鍊?= 255 - 鍘熷€?
                red = 255 - red;
                green = 255 - green;
                blue = 255 - blue;

                // 閲嶆柊缁勫悎RGB鍊?
                Color negativeColor = new Color(red, green, blue);
                negativeImage.setRGB(x, y, negativeColor.getRGB());
            }
        }

        return negativeImage;
    }
}
