package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.*;
import java.awt.image.BufferedImage;

import static java.awt.image.BufferedImage.TYPE_INT_RGB;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 椹禌鍏嬪浘鍍忔护闀?
 *
 * 瀹炵幇椹禌鍏嬫晥鏋滅殑鍥惧儚婊ら暅锛屽皢鍥惧儚鍒嗗壊鎴愯鍒欑殑鐭╁舰鍧楋紝
 * 姣忎釜鍧椾娇鐢ㄥ叾涓績鍍忕礌鐨勯鑹茶繘琛屽～鍏咃紝浜х敓鍍忕礌鍖栫殑瑙嗚鏁堟灉銆?
 *
 * 鎶€鏈師鐞嗭細
 * - 灏嗗浘鍍忔寜鎸囧畾澶у皬鍒嗗壊鎴愮煩褰㈢綉鏍?
 * - 璁＄畻姣忎釜缃戞牸鐨勪腑蹇冨儚绱犱綅缃?
 * - 浣跨敤涓績鍍忕礌鐨勯鑹插～鍏呮暣涓綉鏍?
 * - 澶勭悊杈圭晫缃戞牸鐨勭壒娈婃儏鍐?
 *
 * 瑙嗚鏁堟灉锛?
 * - 闄嶄綆鍥惧儚鍒嗚鲸鐜囧拰缁嗚妭
 * - 浜х敓鍍忕礌鍖栫殑鑹烘湳鏁堟灉
 * - 鍙皟鑺傜殑椹禌鍏嬪潡澶у皬
 * - 淇濇寔鍥惧儚鐨勬暣浣撹壊褰╁拰鏋勫浘
 *
 * 搴旂敤鍦烘櫙锛?
 * - 闅愮淇濇姢锛氭ā绯婃晱鎰熶俊鎭?
 * - 鑹烘湳鏁堟灉锛氬垱寤哄儚绱犺壓鏈鏍?
 * - 娓告垙寮€鍙戯細澶嶅彜鍍忕礌娓告垙椋庢牸
 * - 鍥惧儚鍘嬬缉锛氭瀬搴﹀帇缂╃殑棰勮鏁堟灉
 * - 鍒涙剰璁捐锛氱幇浠ｆ暟瀛楄壓鏈晥鏋?
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@EqualsAndHashCode(callSuper = true)
@Data
@SpiDescribe("椹禌鍏嬪儚绱犲寲婊ら暅")
@Spi("mosaic")
@Accessors(chain = true)
public class ImageMosaicFilter extends AbstractImageFilter {

    /**
     * 椹禌鍏嬪潡鐨勫ぇ灏忥紙鍍忕礌锛夛紝榛樿涓?x8鍍忕礌
     */
    private int size = 8;

    /**
     * 榛樿鏋勯€犲嚱鏁帮紝浣跨敤榛樿鐨勯┈璧涘厠鍧楀ぇ灏忥紙8鍍忕礌锛?
     */
    public ImageMosaicFilter() {
    }

    /**
     * 鏋勯€犲嚱鏁帮紝鎸囧畾椹禌鍏嬪潡澶у皬
     *
     * @param size 椹禌鍏嬪潡鐨勫ぇ灏忥紙鍍忕礌锛夛紝蹇呴』澶т簬0
     */
    public ImageMosaicFilter(int size) {
        this.size = size;
    }

    /**
     * 鎵ц椹禌鍏嬫护闀滃鐞?
     *
     * 灏嗚緭鍏ュ浘鍍忓垎鍓叉垚瑙勫垯鐨勭煩褰㈢綉鏍硷紝姣忎釜缃戞牸浣跨敤鍏朵腑蹇冨儚绱犵殑棰滆壊
     * 杩涜濉厖锛屼粠鑰屼骇鐢熼┈璧涘厠鏁堟灉銆傚鐞嗚竟鐣岀綉鏍肩殑鐗规畩鎯呭喌銆?
     *
     * @param src    婧愬浘鍍?
     * @param image1 鐩爣鍥惧儚锛堟鍙傛暟鏈娇鐢級
     * @return 搴旂敤椹禌鍏嬫晥鏋滃悗鐨勫浘鍍忥紝濡傛灉鍙傛暟鏃犳晥鍒欒繑鍥炲師鍥惧儚
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage image1) {
        BufferedImage mosaicImage = new BufferedImage(src.getWidth(), src.getHeight(), TYPE_INT_RGB);

        // 楠岃瘉椹禌鍏嬪潡澶у皬鐨勬湁鏁堟€?
        if (src.getWidth() < size || src.getHeight() < size || size <= 0) {
            return src;
        }

        // 璁＄畻姘村钩鏂瑰悜鐨勭綉鏍兼暟閲?
        int xCount = 0;
        // 璁＄畻鍨傜洿鏂瑰悜鐨勭綉鏍兼暟閲?
        int yCount = 0;

        if (src.getWidth() % size == 0) {
            xCount = src.getWidth() / size;
        } else {
            // 澶勭悊涓嶈兘鏁撮櫎鐨勬儏鍐?
            xCount = src.getWidth() / size + 1;
        }

        if (src.getHeight() % size == 0) {
            yCount = src.getHeight() / size;
        } else {
            // 澶勭悊涓嶈兘鏁撮櫎鐨勬儏鍐?
            yCount = src.getHeight() / size + 1;
        }

        // 褰撳墠缁樺埗浣嶇疆鍧愭爣
        int x = 0;
        int y = 0;

        // 鑾峰彇鍥惧舰涓婁笅鏂囪繘琛岀粯鍒?
        Graphics graphics = mosaicImage.getGraphics();

        // 閬嶅巻鎵€鏈夌綉鏍艰繘琛岄┈璧涘厠澶勭悊
        for (int i = 0; i < xCount; i++) {
            for (int j = 0; j < yCount; j++) {
                // 褰撳墠椹禌鍏嬪潡鐨勫疄闄呭ぇ灏?
                int blockWidth = size;
                int blockHeight = size;

                // 澶勭悊杈圭晫鍧楋細鏈€鍚庝竴琛屾垨鏈€鍚庝竴鍒楀彲鑳戒笉瓒充竴涓畬鏁寸殑size
                if (i == xCount - 1) {
                    blockWidth = src.getWidth() - x;
                }
                if (j == yCount - 1) {
                    blockHeight = src.getHeight() - y;
                }

                // 璁＄畻褰撳墠鍧楃殑涓績鍍忕礌鍧愭爣
                int centerX = x;
                int centerY = y;

                if (blockWidth % 2 == 0) {
                    centerX += blockWidth / 2;
                } else {
                    centerX += (blockWidth - 1) / 2;
                }

                if (blockHeight % 2 == 0) {
                    centerY += blockHeight / 2;
                } else {
                    centerY += (blockHeight - 1) / 2;
                }

                // 鑾峰彇涓績鍍忕礌鐨勯鑹插苟濉厖鏁翠釜鍧?
                Color centerColor = new Color(src.getRGB(centerX, centerY));
                graphics.setColor(centerColor);
                graphics.fillRect(x, y, blockWidth, blockHeight);

                // 绉诲姩鍒颁笅涓€涓瀭鐩翠綅缃?
                y = y + size;
            }

            // 閲嶇疆鍨傜洿鍧愭爣锛岀Щ鍔ㄥ埌涓嬩竴涓按骞充綅缃?
            y = 0;
            x = x + size;
        }

        // 閲婃斁鍥惧舰璧勬簮
        graphics.dispose();
        return mosaicImage;
    }

}
