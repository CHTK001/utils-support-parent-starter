package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 鐏板害鍖栧浘鍍忔护闀?
 *
 * 灏嗗僵鑹插浘鍍忚浆鎹负鐏板害鍥惧儚鐨勬护闀溿€傞€氳繃灏嗗浘鍍忕殑棰滆壊妯″紡杞崲涓虹伆搴︽ā寮忥紝
 * 绉婚櫎鍥惧儚涓殑棰滆壊淇℃伅锛屽彧淇濈暀浜害淇℃伅銆?
 *
 * 鎶€鏈師鐞嗭細
 * - 浣跨敤BufferedImage鐨凾YPE_BYTE_GRAY妯″紡
 * - 鑷姩搴旂敤鏍囧噯鐨凴GB鍒扮伆搴﹁浆鎹㈠叕寮?
 * - 淇濇寔鍥惧儚鐨勫師濮嬪昂瀵稿拰缁嗚妭
 *
 * 搴旂敤鍦烘櫙锛?
 * - 鍥惧儚棰勫鐞嗭細涓哄悗缁殑鍥惧儚鍒嗘瀽鍋氬噯澶?
 * - 鑹烘湳鏁堟灉锛氬垱寤洪粦鐧界収鐗囨晥鏋?
 * - 鏂囨。澶勭悊锛氬皢褰╄壊鏂囨。杞崲涓虹伆搴︿互鑺傜渷瀛樺偍绌洪棿
 * - 鎵撳嵃浼樺寲锛氫负榛戠櫧鎵撳嵃鏈哄噯澶囧浘鍍?
 *
 * 鎬ц兘鐗圭偣锛?
 * - 楂樻晥杞崲锛氬埄鐢↗ava鍐呯疆鐨勯鑹茬┖闂磋浆鎹?
 * - 鍐呭瓨浼樺寲锛氱伆搴﹀浘鍍忓崰鐢ㄦ洿灏戠殑鍐呭瓨绌洪棿
 * - 璐ㄩ噺淇濊瘉锛氫繚鎸佸浘鍍忕殑缁嗚妭鍜屽姣斿害
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@Slf4j
@SpiDescribe("鐏板害鍖栧浘鍍忔护闀?)
@Spi("gray")
public class ImageGrayImageFilter extends AbstractImageFilter {

    /**
     * 鎵ц鐏板害鍖栨护闀滃鐞?
     *
     * 灏嗚緭鍏ョ殑褰╄壊鍥惧儚杞崲涓虹伆搴﹀浘鍍忋€備娇鐢˙ufferedImage鐨凾YPE_BYTE_GRAY
     * 妯″紡鏉ヨ嚜鍔ㄥ鐞哛GB鍒扮伆搴︾殑杞崲锛岀‘淇濊浆鎹㈣川閲忓拰鎬ц兘銆?
     *
     * @param src 婧愬僵鑹插浘鍍?
     * @param dst 鐩爣鍥惧儚锛堟鍙傛暟鏈娇鐢紝鏂规硶浼氬垱寤烘柊鐨勭伆搴﹀浘鍍忥級
     * @return 杞崲鍚庣殑鐏板害鍥惧儚锛岃浆鎹㈠け璐ユ椂杩斿洖null
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        try {
            // 鍒涘缓涓€涓伆搴︽ā寮忕殑鍥惧儚锛岃嚜鍔ㄥ鐞嗛鑹茬┖闂磋浆鎹?
            BufferedImage grayImage = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
            int width = src.getWidth();
            int height = src.getHeight();

            // 閫愬儚绱犲鍒讹紝BufferedImage浼氳嚜鍔ㄨ繘琛孯GB鍒扮伆搴︾殑杞崲
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    grayImage.setRGB(x, y, src.getRGB(x, y));
                }
            }

            return grayImage;
        } catch (Exception e) {
            log.error("鐏板害鍖栨护闀滃鐞嗗け璐?, e);
            return null;
        }
    }
}
