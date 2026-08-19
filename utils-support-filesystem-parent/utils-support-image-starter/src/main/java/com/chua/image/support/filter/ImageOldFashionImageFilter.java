package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 澶嶅彜鎬€鏃ч鏍煎浘鍍忔护闀?
 *
 * 閫氳繃鐗瑰畾鐨勯鑹插彉鎹㈢煩闃靛皢鐜颁唬褰╄壊鍥惧儚杞崲涓哄叿鏈夋€€鏃у鍙ゆ劅鐨勫浘鍍忔晥鏋溿€?
 * 璇ユ护闀滄ā鎷熻€佸紡鐓х墖鐨勮壊褰╃壒寰侊紝钀ラ€犳俯鏆栥€佹€€蹇电殑瑙嗚姘涘洿銆?
 *
 * 鎶€鏈師鐞嗭細
 * - 浣跨敤鑷畾涔夌殑棰滆壊鍙樻崲鐭╅樀
 * - 璋冩暣RGB鍚勯€氶亾鐨勬潈閲嶅垎閰?
 * - 澧炲己鏆栬壊璋冿紝鍑忓急鍐疯壊璋?
 * - 闄嶄綆鏁翠綋楗卞拰搴﹀拰瀵规瘮搴?
 *
 * 棰滆壊鍙樻崲鐭╅樀锛?
 * R' = 0.393脳R + 0.469脳G + 0.049脳B
 * G' = 0.349脳R + 0.586脳G + 0.068脳B
 * B' = 0.272脳R + 0.534脳G + 0.031脳B
 *
 * 瑙嗚鏁堟灉鐗圭偣锛?
 * - 娓╂殩鐨勮壊璋冿細澧炲己绾㈣壊鍜岄粍鑹叉垚鍒?
 * - 鏌斿拰鐨勫姣斿害锛氶檷浣庡浘鍍忕殑閿愬埄搴?
 * - 鎬€鏃х殑姘涘洿锛氭ā鎷熻€佸紡鑳剁墖鐨勮壊褰╃壒寰?
 * - 缁熶竴鐨勮壊褰╅鏍硷細鍑忓皯鑹插僵鐨勮烦璺冩€?
 *
 * 搴旂敤鍦烘櫙锛?
 * - 鑹烘湳鎽勫奖锛氬垱寤哄鍙ら鏍肩殑鑹烘湳浣滃搧
 * - 鎯呮劅琛ㄨ揪锛氳惀閫犳€€蹇点€佹俯棣ㄧ殑鎯呮劅姘涘洿
 * - 涓婚璁捐锛氬鍙や富棰樼殑瑙嗚璁捐椤圭洰
 * - 绀句氦濯掍綋锛氫负鐓х墖娣诲姞娴佽鐨勫鍙ゆ护闀滄晥鏋?
 * - 鍝佺墝钀ラ攢锛氳惀閫犲搧鐗岀殑鍘嗗彶鎰熷拰鎯呮€€
 *
 * 绠楁硶鐗圭偣锛?
 * - 绾挎€у彉鎹細浣跨敤鐭╅樀杩愮畻杩涜棰滆壊杞崲
 * - 淇濇寔缁嗚妭锛氫笉浼氫涪澶卞浘鍍忕殑缁嗚妭淇℃伅
 * - 璁＄畻绠€鍗曪細姣忎釜鍍忕礌鐙珛澶勭悊锛屾晥鐜囪緝楂?
 * - 鏁堟灉绋冲畾锛氬涓嶅悓绫诲瀷鐨勫浘鍍忛兘鏈変竴鑷寸殑鏁堟灉
 *
 * 娉ㄦ剰锛氬綋鍓嶅疄鐜板垱寤虹殑鏄伆搴﹀浘鍍忥紝濡傞渶淇濇寔褰╄壊澶嶅彜鏁堟灉锛?
 * 搴斾娇鐢═YPE_INT_RGB鑰屼笉鏄疶YPE_BYTE_GRAY銆?
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@SpiDescribe("澶嶅彜鎬€鏃ч鏍兼护闀?)
@Spi("OldFashion")
public class ImageOldFashionImageFilter extends AbstractImageFilter {

    /**
     * 鎵ц澶嶅彜婊ら暅澶勭悊
     *
     * 瀵瑰浘鍍忓簲鐢ㄥ鍙よ壊褰╁彉鎹紝閫氳繃鐗瑰畾鐨勯鑹茬煩闃靛皢鐜颁唬鐓х墖
     * 杞崲涓哄叿鏈夋€€鏃ч鏍肩殑鍥惧儚鏁堟灉銆?
     *
     * @param src 婧愬浘鍍?
     * @param dst 鐩爣鍥惧儚锛堟鍙傛暟鏈娇鐢級
     * @return 搴旂敤澶嶅彜鏁堟灉鍚庣殑鍥惧儚
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        // 娉ㄦ剰锛氳繖閲屽簲璇ヤ娇鐢═YPE_INT_RGB鏉ヤ繚鎸佸僵鑹插鍙ゆ晥鏋?
        BufferedImage vintageImage = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);

        int width = src.getWidth();
        int height = src.getHeight();

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int pixelVal = src.getRGB(x, y);

                // 鎻愬彇RGB鍒嗛噺
                int red = (pixelVal >> 16) & 0xFF;
                int green = (pixelVal >> 8) & 0xFF;
                int blue = pixelVal & 0xFF;

                // 搴旂敤澶嶅彜鑹插僵鍙樻崲鐭╅樀
                int r = (int) (0.393 * red + 0.469 * green + 0.049 * blue);
                int g = (int) (0.349 * red + 0.586 * green + 0.068 * blue);
                int b = (int) (0.272 * red + 0.534 * green + 0.031 * blue);

                // 纭繚棰滆壊鍊煎湪鏈夋晥鑼冨洿鍐?
                r = Math.min(255, Math.max(0, r));
                g = Math.min(255, Math.max(0, g));
                b = Math.min(255, Math.max(0, b));

                // 鍒涘缓澶嶅彜鑹插僵骞惰缃埌鍥惧儚
                Color vintageColor = new Color(r, g, b);
                vintageImage.setRGB(x, y, vintageColor.getRGB());
            }
        }

        return vintageImage;
    }
}
