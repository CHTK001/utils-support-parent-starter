package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * @author CH
 * @since 4.0.0.42
 */
/**
 * 鍥惧儚閫忔槑搴﹀鐞嗘护闀?
 *
 * 瀵瑰浘鍍忚繘琛岄€忔槑搴﹀鐞嗭紝灏嗕笉閫忔槑鐨勫浘鍍忚浆鎹负鍏锋湁閫忔槑鑳屾櫙鐨勫浘鍍忋€?
 * 閫氳繃鍒嗘瀽鍍忕礌鐨勯鑹插€兼潵鍒ゆ柇鍝簺鍖哄煙搴旇鍙樹负閫忔槑锛屽父鐢ㄤ簬鑳屾櫙绉婚櫎鍜屽浘鍍忓悎鎴愩€?
 *
 * 鎶€鏈師鐞嗭細
 * - 鍒嗘瀽姣忎釜鍍忕礌鐨凴GB鍊?
 * - 鏍规嵁棰滆壊鐩镐技搴﹀垽鏂槸鍚︿负鑳屾櫙
 * - 灏嗚儗鏅儚绱犵殑Alpha閫氶亾璁剧疆涓洪€忔槑
 * - 淇濇寔鍓嶆櫙鍍忕礌鐨勫師濮嬮鑹插拰涓嶉€忔槑搴?
 *
 * 绠楁硶娴佺▼锛?
 * 1. 閬嶅巻鍥惧儚鐨勬瘡涓儚绱?
 * 2. 鎻愬彇鍍忕礌鐨凴GB棰滆壊鍊?
 * 3. 鍒ゆ柇鏄惁涓鸿儗鏅鑹诧紙閫氬父鏄櫧鑹叉垨鍏朵粬鍗曚竴棰滆壊锛?
 * 4. 璁剧疆鑳屾櫙鍍忕礌涓哄畬鍏ㄩ€忔槑
 * 5. 淇濇寔鍓嶆櫙鍍忕礌鐨勫師濮嬮鑹?
 *
 * 澶勭悊鐗圭偣锛?
 * - 鑷姩鑳屾櫙妫€娴嬶細鍩轰簬棰滆壊鐩镐技搴?
 * - 杈圭紭淇濇寔锛氫繚鎸佸墠鏅璞＄殑娓呮櫚杈圭紭
 * - 閫忔槑搴︽笎鍙橈細鏀寔鍗婇€忔槑鏁堟灉
 * - 棰滆壊淇濈湡锛氫繚鎸佸墠鏅鑹蹭笉鍙?
 *
 * 搴旂敤鍦烘櫙锛?
 * - 鑳屾櫙绉婚櫎锛氬幓闄ゅ浘鍍忕殑鍗曡壊鑳屾櫙
 * - 鍥惧儚鍚堟垚锛氫负鍥惧儚鍙犲姞鍑嗗閫忔槑鑳屾櫙
 * - Logo澶勭悊锛氬垱寤洪€忔槑鑳屾櫙鐨勬爣蹇楀浘鍍?
 * - 浜у搧鎽勫奖锛氬幓闄や骇鍝佺収鐗囩殑鑳屾櫙
 * - 缃戦〉璁捐锛氬垱寤洪€忔槑鑳屾櫙鐨勫浘鏍囧拰鍏冪礌
 *
 * 娉ㄦ剰浜嬮」锛?
 * - 褰撳墠瀹炵幇涓昏閽堝鐧借壊鑳屾櫙
 * - 瀵逛簬澶嶆潅鑳屾櫙鍙兘闇€瑕佹洿楂樼骇鐨勭畻娉?
 * - 寤鸿杈撳叆鍥惧儚鍏锋湁娓呮櫚鐨勫墠鏅拰鑳屾櫙瀵规瘮
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@Spi("transparent")
@SpiDescribe("閫忔槑搴﹁儗鏅Щ闄ゆ护闀?)
public class ImageTransparentFilter extends AbstractImageFilter{
    /**
     * 瀵瑰浘鍍忓簲鐢ㄩ€忔槑搴﹁繃婊ゃ€?
     * 姝ゆ柟娉曟棬鍦ㄨ瀛愮被瑕嗙洊锛屼互瀹炵幇鍏蜂綋鐨勯€忔槑搴﹁繃婊ら€昏緫銆?
     * 褰撳墠瀹炵幇杩斿洖 null锛岃〃绀哄皻鏈疄鐜板叿浣撶殑杩囨护閫昏緫銆?
     *
     * @param src 鍘熷鍥惧儚锛屽皢瀵规鍥惧儚杩涜閫忔槑搴﹀鐞?
     * @param dst 鐩爣鍥惧儚锛屽鐞嗗悗鐨勫浘鍍忓皢瀛樺偍鍦ㄦ鍙傛暟涓鏋滀负 null锛屽簲鍒涘缓涓€涓柊鐨勫浘鍍忓璞℃潵瀛樺偍缁撴灉銆?
     * @return 杩斿洖缁忚繃閫忔槑搴﹀鐞嗙殑鍥惧儚褰撳墠瀹炵幇杩斿洖 null銆?
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        BufferedImage newImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        // 閬嶅巻姣忎釜鍍忕礌
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // 鑾峰彇褰撳墠鍍忕礌鐨凴GB鍊?
                int rgba = src.getRGB(x, y);

                // 灏哛GB鍊艰浆鎹负棰滆壊瀵硅薄
                Color color = new Color(rgba, true);

                // 濡傛灉褰撳墠鍍忕礌鏄粦鑹诧紝鍒欏皢Alpha閫氶亾鍊艰缃负0
                if (color.getRed() == 0 && color.getGreen() == 0 && color.getBlue() == 0) {
                    color = new Color(0, 0, 0, 0);
                }

                // 灏嗕慨鏀瑰悗鐨勯鑹茶缃埌鏂扮殑BufferedImage涓?
                newImage.setRGB(x, y, color.getRGB());
            }
        }
        return newImage;
    }
}
