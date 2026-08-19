package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * NTSC 鏍囧噯鐏板害杞崲婊ら暅
 *
 * 浣跨敤 NTSC锛堝浗瀹剁數瑙嗙郴缁熷鍛樹細锛夋爣鍑嗙殑浜害璁＄畻鍏紡灏嗗僵鑹插浘鍍忚浆鎹负鐏板害鍥惧儚銆?
 * 鑰冭檻浜嗕汉鐪煎涓嶅悓棰滆壊鐨勬晱鎰熷害宸紓锛屾彁渚涙洿鑷劧鐨勭伆搴﹁浆鎹㈡晥鏋溿€?
 *
 * 鎶€鏈師鐞嗭細
 * - 鍩轰簬 NTSC 鏍囧噯鐨勪寒搴﹁绠楀叕寮?
 * - 鑰冭檻浜虹溂瀵圭豢鑹叉渶鏁忔劅锛岀孩鑹叉涔嬶紝钃濊壊鏈€涓嶆晱鎰?
 * - 浣跨敤鍔犳潈骞冲潎鑰岄潪绠€鍗曞钩鍧囷紝鑾峰緱鏇寸湡瀹炵殑鐏板害鏁堟灉
 * - 淇濇寔 Alpha 閫氶亾涓嶅彉
 *
 * NTSC 鐏板害鍏紡锛?
 * Gray = 0.299 脳 R + 0.587 脳 G + 0.114 脳 B
 *
 * 瀹炵幇浼樺寲锛?
 * - 浣跨敤鏁存暟杩愮畻鎻愰珮鎬ц兘锛?R脳77 + G脳151 + B脳28) >> 8
 * - 鏉冮噸绯绘暟锛?7/256鈮?.299, 151/256鈮?.587, 28/256鈮?.114
 * - 浣嶇Щ杩愮畻鏇夸唬闄ゆ硶锛屾彁楂樿绠楁晥鐜?
 *
 * 瑙嗚鏁堟灉锛?
 * - 鑷劧鐨勭伆搴﹁浆鎹紝绗﹀悎浜虹溂瑙嗚鐗规€?
 * - 淇濇寔鍥惧儚鐨勪寒搴﹀眰娆″拰瀵规瘮搴?
 * - 缁胯壊鍖哄煙鍦ㄧ伆搴﹀浘涓浉瀵硅緝浜?
 * - 钃濊壊鍖哄煙鍦ㄧ伆搴﹀浘涓浉瀵硅緝鏆?
 *
 * 搴旂敤鍦烘櫙锛?
 * - 鍥惧儚棰勫鐞嗭細涓哄悗缁鐞嗗噯澶囩伆搴﹀浘鍍?
 * - 鎵撳嵃浼樺寲锛氫负榛戠櫧鎵撳嵃鍑嗗鍥惧儚
 * - 鍥惧儚鍒嗘瀽锛氱畝鍖栧浘鍍忔暟鎹紝涓撴敞浜庝寒搴︿俊鎭?
 * - 鑹烘湳鏁堟灉锛氬垱寤虹粡鍏哥殑榛戠櫧鐓х墖鏁堟灉
 * - 璁＄畻鏈鸿瑙夛細涓虹畻娉曞鐞嗗噯澶囧崟閫氶亾鍥惧儚
 *
 * 鎬ц兘鐗圭偣锛?
 * - 楂樻晥鐨勬暣鏁拌繍绠楀疄鐜?
 * - 鏀寔绱㈠紩棰滆壊妯″瀷
 * - 閫愬儚绱犲鐞嗭紝閫傚悎澶у浘鍍?
 * - 鍐呭瓨鍗犵敤浼樺寲
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@SpiDescribe("NTSC鏍囧噯鐏板害杞崲婊ら暅")
@Spi("grayscale")
public class ImageGrayscaleFilter extends AbstractImagePointFilter {

    /**
     * 鏋勯€犲嚱鏁?
     *
     * 鍒濆鍖栫伆搴︽护闀滐紝璁剧疆鏀寔绱㈠紩棰滆壊妯″瀷澶勭悊銆?
     */
    public ImageGrayscaleFilter() {
        canFilterIndexColorModel = true;
    }

    /**
     * 瀵瑰崟涓儚绱犺繘琛岀伆搴﹁浆鎹?
     *
     * 浣跨敤 NTSC 鏍囧噯鍏紡璁＄畻鐏板害鍊硷紝淇濇寔 Alpha 閫氶亾涓嶅彉銆?
     *
     * @param x   鍍忕礌鐨?X 鍧愭爣锛堟鍙傛暟鏈娇鐢級
     * @param y   鍍忕礌鐨?Y 鍧愭爣锛堟鍙傛暟鏈娇鐢級
     * @param rgb 鍘熷 ARGB 鍍忕礌鍊?
     * @return 杞崲鍚庣殑鐏板害 ARGB 鍍忕礌鍊?
     */
    @Override
    public int filterRgb(int x, int y, int rgb) {
        // 鎻愬彇 Alpha 閫氶亾锛屼繚鎸佷笉鍙?
        int a = rgb & 0xff000000;

        // 鎻愬彇 RGB 鍒嗛噺
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;

        // 浣跨敤 NTSC 鏍囧噯鍏紡璁＄畻鐏板害鍊?
        // Gray = 0.299脳R + 0.587脳G + 0.114脳B
        // 浼樺寲涓烘暣鏁拌繍绠楋細(R脳77 + G脳151 + B脳28) >> 8
        rgb = (r * 77 + g * 151 + b * 28) >> 8;

        // 閲嶆柊缁勫悎 ARGB 鍊硷紝RGB 涓変釜閫氶亾閮戒娇鐢ㄧ浉鍚岀殑鐏板害鍊?
        return a | (rgb << 16) | (rgb << 8) | rgb;
    }


}
