package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 鍍忕礌鍖栧浘鍍忔护闀?
 *
 * 灏嗗浘鍍忚浆鎹负鍍忕礌鑹烘湳椋庢牸锛岄€氳繃闄嶄綆鍥惧儚鍒嗚鲸鐜囧苟浣跨敤鏂瑰舰鍍忕礌鍧?
 * 鏉ラ噸鐜板浘鍍忓唴瀹癸紝鍒涘缓澶嶅彜鐨?浣嶆父鎴忛鏍艰瑙夋晥鏋溿€?
 *
 * 鎶€鏈師鐞嗭細
 * - 缃戞牸閲囨牱锛氭寜鎸囧畾姝ラ暱瀵瑰浘鍍忚繘琛岀綉鏍奸噰鏍?
 * - 鍍忕礌鍧楀～鍏咃細鐢ㄩ噰鏍风偣鐨勯鑹插～鍏呮暣涓儚绱犲潡
 * - 鍒嗚鲸鐜囬檷浣庯細閫氳繃澧炲ぇ鍍忕礌鍧楀昂瀵搁檷浣庡浘鍍忓垎杈ㄧ巼
 * - 棰滆壊绠€鍖栵細鍑忓皯鍥惧儚涓殑棰滆壊缁嗚妭
 *
 * 绠楁硶娴佺▼锛?
 * 1. 鎸夋寚瀹氭闀块亶鍘嗗浘鍍忓儚绱?
 * 2. 鑾峰彇閲囨牱鐐圭殑RGB棰滆壊鍊?
 * 3. 鐢ㄨ棰滆壊濉厖瀵瑰簲鐨勭煩褰㈠儚绱犲潡
 * 4. 閲嶅鐩村埌瑕嗙洊鏁翠釜鍥惧儚
 *
 * 瑙嗚鏁堟灉鐗圭偣锛?
 * - 澶嶅彜椋庢牸锛氭ā鎷熸棭鏈熺數瀛愭父鎴忕殑鍍忕礌鑹烘湳
 * - 绠€鍖栫粏鑺傦細鍑忓皯鍥惧儚鐨勭粏鑺傚鏉傚害
 * - 鏂瑰潡鏁堟灉锛氭槑鏄剧殑鏂瑰舰鍍忕礌鍧楃粨鏋?
 * - 鑹插僵淇濇寔锛氫繚鐣欏師鍥惧儚鐨勪富瑕佽壊褰╀俊鎭?
 *
 * 鍙傛暟鎺у埗锛?
 * - 姝ラ暱鍊艰秺灏忥細鍍忕礌鍖栫▼搴﹁秺楂橈紝鏁堟灉瓒婃槑鏄?
 * - 姝ラ暱鍊艰秺澶э細淇濈暀鏇村缁嗚妭锛屾晥鏋滆秺杞诲井
 * - 寤鸿鑼冨洿锛?-20鍍忕礌锛屾牴鎹浘鍍忓ぇ灏忚皟鏁?
 *
 * 搴旂敤鍦烘櫙锛?
 * - 娓告垙寮€鍙戯細鍒涘缓澶嶅彜鍍忕礌娓告垙鐨勮壓鏈鏍?
 * - 鑹烘湳鍒涗綔锛氬埗浣滃儚绱犺壓鏈綔鍝?
 * - 缃戦〉璁捐锛氬垱寤虹嫭鐗圭殑瑙嗚鍏冪礌
 * - 绀句氦濯掍綋锛氫负鐓х墖娣诲姞澶嶅彜婊ら暅鏁堟灉
 * - 鍝佺墝璁捐锛氳惀閫犳€€鏃с€佸鍙ょ殑鍝佺墝褰㈣薄
 *
 * 鎬ц兘鐗圭偣锛?
 * - 楂樻晥澶勭悊锛氶€氳繃璺宠穬閲囨牱鍑忓皯璁＄畻閲?
 * - 鍐呭瓨鍙嬪ソ锛氱敓鎴愮殑鍥惧儚淇濇寔鍘熷灏哄
 * - 鍙皟鑺傛€э細閫氳繃姝ラ暱鍙傛暟鎺у埗鏁堟灉寮哄害
 * - 瀹炴椂鎬э細閫傚悎瀹炴椂鍥惧儚澶勭悊搴旂敤
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@Spi("pixel")
@SpiDescribe("鍍忕礌鍖栨护闀?)
public class ImagePixelImageFilter extends AbstractImageFilter{

    /**
     * 榛樿鍍忕礌姝ラ暱锛屽€艰秺灏忓儚绱犲寲绋嬪害瓒婇珮
     * 鎺ㄨ崘鍊硷細5鍍忕礌锛岄€傚悎澶у鏁板浘鍍?
     */
    private static final int DEFAULT_STEP = 5;

    /**
     * 褰撳墠浣跨敤鐨勫儚绱犳闀?
     */
    private int stepValue = DEFAULT_STEP;

    /**
     * 榛樿鏋勯€犲嚱鏁?
     *
     * 浣跨敤榛樿鐨勫儚绱犳闀匡紙5鍍忕礌锛夊垱寤哄儚绱犲寲婊ら暅銆?
     */
    public ImagePixelImageFilter() {
        // 浣跨敤榛樿姝ラ暱
    }

    /**
     * 甯﹀弬鏁扮殑鏋勯€犲嚱鏁?
     *
     * @param stepValue 鍍忕礌姝ラ暱锛屾帶鍒跺儚绱犲寲绋嬪害鍊艰秺灏忔晥鏋滆秺鏄庢樉锛屽缓璁寖鍥?-20
     */
    public ImagePixelImageFilter(int stepValue) {
        this.stepValue = stepValue;
    }


    /**
     * 鎵ц鍍忕礌鍖栨护闀滃鐞?
     *
     * @param src 婧愬浘鍍?
     * @param dst 鐩爣鍥惧儚锛堟鍙傛暟鏈娇鐢級
     * @return 鍍忕礌鍖栧鐞嗗悗鐨勫浘鍍?
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        
        return getPixelImage(src, stepValue);
    
    }

    /**
     * 鐢熸垚鍍忕礌鍖栧浘鍍?
     *
     * 閫氳繃缃戞牸閲囨牱鍜屽儚绱犲潡濉厖鐨勬柟寮忓皢鏅€氬浘鍍忚浆鎹负鍍忕礌鑹烘湳椋庢牸銆?
     * 绠楁硶浼氭寜鎸囧畾姝ラ暱閬嶅巻鍥惧儚锛岀敤閲囨牱鐐圭殑棰滆壊濉厖瀵瑰簲鐨勭煩褰㈠尯鍩熴€?
     *
     * @param sourceImage 杈撳叆鐨勬簮鍥惧儚
     * @param pixelStep 鍍忕礌姝ラ暱锛屾帶鍒跺儚绱犲寲绋嬪害鍊艰秺灏忓儚绱犲寲鏁堟灉瓒婃槑鏄?
     * @return 鍍忕礌鍖栧鐞嗗悗鐨勫浘鍍?
     */
    public BufferedImage getPixelImage(BufferedImage sourceImage, int pixelStep) {
        int width = sourceImage.getWidth();
        int height = sourceImage.getHeight();
        int minX = sourceImage.getMinX();
        int minY = sourceImage.getMinY();

        // 鍒涘缓杈撳嚭鍥惧儚
        BufferedImage pixelizedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = pixelizedImage.createGraphics();

        // 璁剧疆娓叉煋璐ㄩ噺锛堝彲閫夛級
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        // 鎸夋闀块亶鍘嗗浘鍍忥紝鍒涘缓鍍忕礌鍧?
        for (int x = minX; x < width; x += pixelStep) {
            for (int y = minY; y < height; y += pixelStep) {
                // 鑾峰彇閲囨牱鐐圭殑棰滆壊
                int pixelRGB = sourceImage.getRGB(x, y);
                int red = (pixelRGB >> 16) & 0xff;
                int green = (pixelRGB >> 8) & 0xff;
                int blue = pixelRGB & 0xff;

                // 璁剧疆鐢荤瑪棰滆壊
                graphics.setColor(new Color(red, green, blue));

                // 濉厖鍍忕礌鍧楋紙浣跨敤鍦嗚鐭╁舰鍒涘缓鏇存煍鍜岀殑鍍忕礌鏁堟灉锛?
                // 鍙傛暟-1琛ㄧず鍦嗚鍗婂緞涓?锛屽嵆鏅€氱煩褰?
                graphics.fillRoundRect(x, y, pixelStep, pixelStep, -1, -1);
            }
        }

        // 閲婃斁鍥惧舰璧勬簮
        graphics.dispose();
        return pixelizedImage;
    }
}

