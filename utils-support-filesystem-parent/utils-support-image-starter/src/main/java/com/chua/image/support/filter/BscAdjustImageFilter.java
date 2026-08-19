package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * BSC 鍥惧儚璋冩暣婊ら暅
 *
 * BSC (Brightness, Saturation, Contrast) 婊ら暅鎻愪緵瀵瑰浘鍍忎寒搴︺€侀ケ鍜屽害鍜屽姣斿害鐨?
 * 缁煎悎璋冩暣鍔熻兘銆傞€氳繃 HSL 棰滆壊绌洪棿杞崲瀹炵幇绮剧‘鐨勯鑹茶皟鏁淬€?
 *
 * 鎶€鏈師鐞嗭細
 * - RGB 鍒?HSL 棰滆壊绌洪棿杞崲
 * - 鍦?HSL 绌洪棿涓皟鏁翠寒搴﹀拰楗卞拰搴?
 * - 鍦?RGB 绌洪棿涓皟鏁村姣斿害
 * - HSL 鍒?RGB 棰滆壊绌洪棿閫嗚浆鎹?
 *
 * 璋冩暣鍙傛暟锛?
 * - 浜害 (Brightness)锛氭帶鍒跺浘鍍忕殑鏄庢殫绋嬪害
 * - 楗卞拰搴?(Saturation)锛氭帶鍒堕鑹茬殑椴滆壋绋嬪害
 * - 瀵规瘮搴?(Contrast)锛氭帶鍒舵槑鏆楀姣旂殑寮哄害
 *
 * 鍙傛暟鑼冨洿锛?
 * - 鎵€鏈夊弬鏁颁互鐧惧垎姣斿舰寮忚緭鍏ワ紙-100 鍒?+100锛?
 * - 0 琛ㄧず涓嶈皟鏁达紝姝ｅ€煎寮猴紝璐熷€煎噺寮?
 * - 鍐呴儴鑷姩杞崲涓轰箻娉曞洜瀛愶紙0.0 鍒?2.0锛?
 *
 * 搴旂敤鍦烘櫙锛?
 * - 鐓х墖鍚庢湡澶勭悊锛氳皟鏁寸収鐗囩殑鑹插僵鍜屾槑鏆?
 * - 鍥惧儚澧炲己锛氭敼鍠勫浘鍍忕殑瑙嗚鏁堟灉
 * - 鑹插僵鏍℃锛氫慨姝ｅ浘鍍忕殑鑹插僵鍋忓樊
 * - 鑹烘湳鏁堟灉锛氬垱寤虹壒瀹氱殑瑙嗚椋庢牸
 * - 鏄剧ず閫傞厤锛氫负涓嶅悓鏄剧ず璁惧浼樺寲鍥惧儚
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@Spi("bsc")
@SpiDescribe("浜害楗卞拰搴﹀姣斿害璋冩暣婊ら暅")
public class BscAdjustImageFilter extends AbstractImageFilter {

    /**
     * 浜害璋冩暣鍊硷紝鑼冨洿 -100 鍒?+100
     */
    private double brightness;

    /**
     * 瀵规瘮搴﹁皟鏁村€硷紝鑼冨洿 -100 鍒?+100
     */
    private double contrast;

    /**
     * 楗卞拰搴﹁皟鏁村€硷紝鑼冨洿 -100 鍒?+100
     */
    private double saturation;

    /**
     * 鑾峰彇浜害璋冩暣鍊?
     *
     * @return 浜害璋冩暣鍊硷紝鑼冨洿 -100 鍒?+100
     */
    public double getBrightness() {
        return brightness;
    }

    /**
     * 璁剧疆浜害璋冩暣鍊?
     *
     * @param brightness 浜害璋冩暣鍊硷紝鑼冨洿 -100 鍒?+100锛?琛ㄧず涓嶈皟鏁?
     */
    public void setBrightness(double brightness) {
        this.brightness = brightness;
    }

    /**
     * 鑾峰彇楗卞拰搴﹁皟鏁村€?
     *
     * @return 楗卞拰搴﹁皟鏁村€硷紝鑼冨洿 -100 鍒?+100
     */
    public double getSaturation() {
        return saturation;
    }

    /**
     * 璁剧疆楗卞拰搴﹁皟鏁村€?
     *
     * @param saturation 楗卞拰搴﹁皟鏁村€硷紝鑼冨洿 -100 鍒?+100锛?琛ㄧず涓嶈皟鏁?
     */
    public void setSaturation(double saturation) {
        this.saturation = saturation;
    }

    /**
     * 鑾峰彇瀵规瘮搴﹁皟鏁村€?
     *
     * @return 瀵规瘮搴﹁皟鏁村€硷紝鑼冨洿 -100 鍒?+100
     */
    public double getContrast() {
        return contrast;
    }

    /**
     * 璁剧疆瀵规瘮搴﹁皟鏁村€?
     *
     * @param contrast 瀵规瘮搴﹁皟鏁村€硷紝鑼冨洿 -100 鍒?+100锛?琛ㄧず涓嶈皟鏁?
     */
    public void setContrast(double contrast) {
        this.contrast = contrast;
    }

    /**
     * 鎵ц BSC 璋冩暣婊ら暅澶勭悊
     *
     * 瀵瑰浘鍍忚繘琛屼寒搴︺€侀ケ鍜屽害鍜屽姣斿害鐨勭患鍚堣皟鏁淬€?
     * 浣跨敤 HSL 棰滆壊绌洪棿杩涜浜害鍜岄ケ鍜屽害璋冩暣锛孯GB 绌洪棿杩涜瀵规瘮搴﹁皟鏁淬€?
     *
     * @param src  婧愬浘鍍?
     * @param dest 鐩爣鍥惧儚锛屽彲浠ヤ负 null
     * @return 璋冩暣鍚庣殑鍥惧儚
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dest) {
        // 澶勭悊鍙傛暟锛屽皢鐧惧垎姣旇浆鎹负涔樻硶鍥犲瓙
        handleParameters();

        int width = src.getWidth();
        int height = src.getHeight();

        if (dest == null) {
            dest = creatCompatibleDestImage(src, null);
        }

        int[] inPixels = new int[width * height];
        int[] outPixels = new int[width * height];
        getRgb(src, 0, 0, width, height, inPixels);

        int index = 0;
        for (int row = 0; row < height; row++) {
            int ta = 0, tr = 0, tg = 0, tb = 0;
            for (int col = 0; col < width; col++) {
                index = row * width + col;

// 鎻愬彇 ARGB 鍒嗛噺
        // Alpha 閫氶亾
        ta = (inPixels[index] >> 24) & 0xff;
        // 绾㈣壊閫氶亾
        tr = (inPixels[index] >> 16) & 0xff;
        // 缁胯壊閫氶亾
        tg = (inPixels[index] >> 8) & 0xff;
        // 钃濊壊閫氶亾
        tb = inPixels[index] & 0xff;

                // RGB 杞崲涓?HSL 鑹插僵绌洪棿
                double[] hsl = rgb2Hsl(new int[]{tr, tg, tb});

                // 璋冩暣楗卞拰搴︼紙鍦?HSL 绌洪棿涓級
                hsl[1] = hsl[1] * saturation;
                if (hsl[1] < 0.0) {
                    hsl[1] = 0.0;
                }
                if (hsl[1] > 255.0) {
                    hsl[1] = 255.0;
                }

                // 璋冩暣浜害锛堝湪 HSL 绌洪棿涓級
                hsl[2] = hsl[2] * brightness;
                if (hsl[2] < 0.0) {
                    hsl[2] = 0.0;
                }
                if (hsl[2] > 255.0) {
                    hsl[2] = 255.0;
                }

                // HSL 杞崲鍥?RGB 绌洪棿
                int[] rgb = hsl2Rgb(hsl);
                tr = clamp(rgb[0]);
                tg = clamp(rgb[1]);
                tb = clamp(rgb[2]);

                // 璋冩暣瀵规瘮搴︼紙鍦?RGB 绌洪棿涓級
                double cr = ((tr / 255.0d) - 0.5d) * contrast;
                double cg = ((tg / 255.0d) - 0.5d) * contrast;
                double cb = ((tb / 255.0d) - 0.5d) * contrast;

                // 璁＄畻鏈€缁堢殑 RGB 鍊?
                tr = (int) ((cr + 0.5f) * 255.0f);
                tg = (int) ((cg + 0.5f) * 255.0f);
                tb = (int) ((cb + 0.5f) * 255.0f);

                // 閲嶆柊缁勫悎 ARGB 鍊?
                outPixels[index] = (ta << 24) | (clamp(tr) << 16) | (clamp(tg) << 8) | clamp(tb);
            }
        }

        setRgb(dest, 0, 0, width, height, outPixels);
        return dest;
    }

    /**
     * 澶勭悊璋冩暣鍙傛暟
     *
     * 灏嗙櫨鍒嗘瘮褰㈠紡鐨勮皟鏁村弬鏁拌浆鎹负涔樻硶鍥犲瓙銆?
     * 杈撳叆鑼冨洿 -100 鍒?+100锛岃浆鎹负 0.0 鍒?2.0 鐨勪箻娉曞洜瀛愩€?
     */
    public void handleParameters() {
        contrast = (1.0 + contrast / 100.0);
        brightness = (1.0 + brightness / 100.0);
        saturation = (1.0 + saturation / 100.0);
    }

    /**
     * 灏嗛鑹插€奸檺鍒跺湪鏈夋晥鑼冨洿鍐?
     *
     * @param value 棰滆壊鍊?
     * @return 闄愬埗鍦?0-255 鑼冨洿鍐呯殑棰滆壊鍊?
     */
    public int clamp(int value) {
        return value > 255 ? 255 : ((value < 0) ? 0 : value);
    }
}
