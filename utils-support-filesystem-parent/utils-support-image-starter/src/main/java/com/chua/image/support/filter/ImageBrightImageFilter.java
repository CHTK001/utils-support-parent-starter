package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 鏄庝寒搴﹀寮哄浘鍍忔护闀?
 *
 * 閫氳繃澧炲姞RGB鍚勪釜棰滆壊閫氶亾鐨勬暟鍊兼潵鎻愰珮鍥惧儚鐨勬暣浣撲寒搴︺€?
 * 璇ユ护闀滃鍥惧儚鐨勬瘡涓儚绱犵偣杩涜浜害璋冩暣锛屼娇鍥惧儚鐪嬭捣鏉ユ洿鍔犳槑浜€?
 *
 * 鎶€鏈師鐞嗭細
 * - 鎻愬彇姣忎釜鍍忕礌鐨凴GB鍒嗛噺
 * - 瀵规瘡涓鑹查€氶亾澧炲姞鍥哄畾鏁板€硷紙榛樿+10锛?
 * - 纭繚棰滆壊鍊间笉瓒呰繃255鐨勪笂闄?
 * - 閲嶆柊缁勫悎RGB鍊煎舰鎴愭柊鐨勫儚绱?
 *
 * 搴旂敤鍦烘櫙锛?
 * - 鐓х墖鍚庢湡澶勭悊锛氭彁鍗囨殫娣＄収鐗囩殑浜害
 * - 鍥惧儚澧炲己锛氭敼鍠勪綆鍏夌収鏉′欢涓嬫媿鎽勭殑鍥惧儚
 * - 鏄剧ず浼樺寲锛氫负涓嶅悓鏄剧ず璁惧璋冩暣鍥惧儚浜害
 * - 鑹烘湳鏁堟灉锛氬垱寤烘槑浜€佹竻鏂扮殑瑙嗚鏁堟灉
 *
 * 娉ㄦ剰浜嬮」锛?
 * - 杩囧害澧炰寒鍙兘瀵艰嚧鍥惧儚杩囨洕
 * - 寤鸿閰嶅悎瀵规瘮搴﹁皟鏁翠娇鐢?
 * - 瀵逛簬宸茬粡寰堜寒鐨勫浘鍍忔晥鏋滄湁闄?
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@Spi("Bright")
@SpiDescribe("鏄庝寒搴﹀寮烘护闀?)
public class ImageBrightImageFilter extends AbstractImageFilter {

    /**
     * 榛樿浜害澧炲姞鍊?
     */
    private static final int DEFAULT_BRIGHTNESS_INCREASE = 10;

    /**
     * 鎵ц鏄庝寒搴﹀寮烘护闀滃鐞?
     *
     * 瀵瑰浘鍍忕殑姣忎釜鍍忕礌杩涜浜害澧炲己澶勭悊锛岄€氳繃澧炲姞RGB鍚勯€氶亾鐨勬暟鍊?
     * 鏉ユ彁楂樺浘鍍忕殑鏁翠綋浜害銆傚鐞嗚繃绋嬩腑浼氱‘淇濋鑹插€间笉浼氭孩鍑恒€?
     *
     * @param src 婧愬浘鍍?
     * @param dst 鐩爣鍥惧儚锛堟鍙傛暟鏈娇鐢紝鏂规硶浼氬垱寤烘柊鍥惧儚锛?
     * @return 浜害澧炲己鍚庣殑鍥惧儚
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        // 娉ㄦ剰锛氳繖閲屽簲璇ヤ娇鐢═YPE_INT_RGB鑰屼笉鏄疶YPE_BYTE_GRAY锛屽洜涓烘垜浠淇濇寔褰╄壊
        BufferedImage brightImage = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);

        int width = src.getWidth();
        int height = src.getHeight();

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int pixelVal = src.getRGB(x, y);

                // 鎻愬彇RGB鍒嗛噺
                int red = (pixelVal >> 16) & 0xFF;
                int green = (pixelVal >> 8) & 0xFF;
                int blue = pixelVal & 0xFF;

                // 澧炲姞浜害锛岀‘淇濅笉瓒呰繃255
                red = Math.min(255, red + DEFAULT_BRIGHTNESS_INCREASE);
                green = Math.min(255, green + DEFAULT_BRIGHTNESS_INCREASE);
                blue = Math.min(255, blue + DEFAULT_BRIGHTNESS_INCREASE);

                // 閲嶆柊缁勫悎RGB鍊?
                Color brightColor = new Color(red, green, blue);
                brightImage.setRGB(x, y, brightColor.getRGB());
            }
        }

        return brightImage;
    }
}
