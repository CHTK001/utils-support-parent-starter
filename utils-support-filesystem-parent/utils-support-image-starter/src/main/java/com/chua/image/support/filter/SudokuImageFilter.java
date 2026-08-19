package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 涔濆鏍煎浘鍍忔护闀?
 *
 * 灏嗗浘鍍忓垎鍓叉垚涔濅釜绛夊ぇ鐨勫尯鍩燂紝骞跺湪姣忎釜鍖哄煙涔嬮棿娣诲姞鍒嗗壊绾匡紝
 * 鍒涘缓绫讳技涔濆鏍兼垨缃戞牸鐨勮瑙夋晥鏋溿€傚父鐢ㄤ簬鍥惧儚甯冨眬璁捐鍜岃壓鏈垱浣溿€?
 *
 * 鎶€鏈師鐞嗭細
 * - 灏嗗浘鍍忔寜3x3缃戞牸杩涜鍒嗗壊
 * - 鍦ㄧ綉鏍肩嚎浣嶇疆缁樺埗鍒嗗壊绾?
 * - 淇濇寔鍘熷浘鍍忓唴瀹癸紝浠呮坊鍔犵綉鏍肩嚎鏉?
 * - 鏀寔鑷畾涔夌嚎鏉＄矖缁?
 *
 * 绠楁硶娴佺▼锛?
 * 1. 璁＄畻鍥惧儚鐨勪笁绛夊垎鐐逛綅缃?
 * 2. 鍦ㄦ按骞冲拰鍨傜洿鏂瑰悜缁樺埗鍒嗗壊绾?
 * 3. 浣跨敤鎸囧畾绮楃粏鐨勭嚎鏉?
 * 4. 淇濇寔鍘熷浘鍍忕殑鍏朵粬閮ㄥ垎涓嶅彉
 *
 * 瑙嗚鏁堟灉锛?
 * - 鍒涘缓瑙勬暣鐨勪節瀹牸甯冨眬
 * - 澧炲己鍥惧儚鐨勭粨鏋勬劅
 * - 閫傚悎鎽勫奖鏋勫浘鍙傝€?
 * - 鍙敤浜庤壓鏈璁℃晥鏋?
 *
 * 搴旂敤鍦烘櫙锛?
 * - 鎽勫奖鏋勫浘锛氫節瀹牸鏋勫浘娉曞弬鑰冪嚎
 * - 鍥惧儚璁捐锛氬垱寤虹綉鏍煎竷灞€鏁堟灉
 * - 鑹烘湳鍒涗綔锛氱幇浠ｈ壓鏈鏍煎鐞?
 * - 鏁欏婕旂ず锛氭瀯鍥惧師鐞嗗睍绀?
 * - 鍥惧儚鍒嗘瀽锛氬尯鍩熷垝鍒嗗拰鏍囪
 *
 * 鍙傛暟璇存槑锛?
 * - solid: 鍒嗗壊绾跨殑绮楃粏锛屽崟浣嶄负鍍忕礌
 *   - 1-3: 缁嗙嚎鏉★紝閫傚悎绮剧粏鏁堟灉
 *   - 4-8: 涓瓑绾挎潯锛岄€傚悎涓€鑸娇鐢?
 *   - 9+: 绮楃嚎鏉★紝閫傚悎寮鸿皟鏁堟灉
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@Spi("Sudoku")
@SpiDescribe("涔濆鏍肩綉鏍兼护闀?)
public class SudokuImageFilter extends AbstractImageFilter{

    /**
     * 鍒嗗壊绾跨殑绮楃粏锛堝儚绱狅級
     */
    private final int solid;

    /**
     * 榛樿鏋勯€犲嚱鏁?
     *
     * 浣跨敤榛樿鐨勭嚎鏉＄矖缁嗭紙5鍍忕礌锛夊垱寤轰節瀹牸婊ら暅銆?
     */
    public SudokuImageFilter() {
        this(5);
    }

    /**
     * 甯﹀弬鏁扮殑鏋勯€犲嚱鏁?
     *
     * @param solid 鍒嗗壊绾跨殑绮楃粏锛屽崟浣嶄负鍍忕礌锛屽缓璁寖鍥?-20
     */
    public SudokuImageFilter(int solid) {
        this.solid = solid;
    }

    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int smallWidth = width / 3 ;
        int smallHeight = height / 3 ;
        BufferedImage newBufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics graphics = newBufferedImage.getGraphics();
        graphics.setColor(new Color(255, 255, 255));
        graphics.fillRect(0, 0, width, height);
        for(int i = 0 ; i < 3; i++) {
            for(int j = 0 ; j < 3; j++) {
                int startX = j * smallWidth;
                int startY = i * smallHeight;
                BufferedImage subimage = src.getSubimage(startX, startY, smallWidth - solid, smallHeight - solid);
                graphics.drawImage(subimage, startX, startY, null);
            }
        }
        graphics.dispose();
        return newBufferedImage;
    }
}
