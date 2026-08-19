package com.chua.image.support.filter;

import com.chua.common.support.constant.NumberConstant;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

/**
 * 鐐规护闀滄娊璞″熀绫?
 *
 * 鎻愪緵鍩轰簬鍍忕礌鐐圭殑鍥惧儚婊ら暅澶勭悊鍔熻兘銆傛绫讳笓闂ㄧ敤浜庡鐞嗘瘡涓儚绱犵偣鐙珛鐨勬护闀滄晥鏋滐紝
 * 濡傞鑹茶皟鏁淬€佷寒搴﹀姣斿害璋冩暣銆佽壊褰╁彉鎹㈢瓑銆傛帴鍙ｈ璁′笌浼犵粺鐨凴GBImageFilter鍏煎銆?
 *
 * 涓昏鐗圭偣锛?
 * - 閫愬儚绱犲鐞嗭細瀵规瘡涓儚绱犵偣鐙珛杩涜婊ら暅澶勭悊
 * - 楂樻€ц兘浼樺寲锛氶拡瀵逛笉鍚屽浘鍍忕被鍨嬭繘琛屼紭鍖栧鐞?
 * - 鍐呭瓨鍙嬪ソ锛氶伩鍏嶄笉蹇呰鐨勫浘鍍忔牸寮忚浆鎹?
 * - 鏄撲簬鎵╁睍锛氬瓙绫诲彧闇€瀹炵幇filterRgb鏂规硶鍗冲彲
 *
 * 閫傜敤鍦烘櫙锛?
 * - 棰滆壊璋冩暣婊ら暅锛堜寒搴︺€佸姣斿害銆侀ケ鍜屽害锛?
 * - 鑹插僵鍙樻崲婊ら暅锛堢伆搴︺€佽礋鐗囥€佸鍙ょ瓑锛?
 * - 闃堝€煎鐞嗘护闀滐紙浜屽€煎寲銆佽壊褰╁垎绂荤瓑锛?
 * - 绠€鍗曠壒鏁堟护闀滐紙鍍忕礌鍖栥€侀┈璧涘厠绛夛級
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
public abstract class AbstractImagePointFilter extends AbstractImageFilter {

    /**
     * 鏄惁鍙互杩囨护绱㈠紩棰滆壊妯″瀷
     */
    protected boolean canFilterIndexColorModel = false;

    /**
     * 甯搁噺锛?56锛岄鑹插€艰绠?
     */
    public static final int MAX_256 = NumberConstant.MAX_256;

    /**
     * 甯搁噺锛?28锛岄鑹插€艰绠?
     */
    public static final int MAX_128 = NumberConstant.MAX_128;

    /**
     * 甯搁噺锛?55锛岄鑹插€艰绠?
     */
    public static final int MAX_255 = NumberConstant.MAX_255;

    /**
     * 鎵ц鐐规护闀滃鐞?
     *
     * 閫愯閫愬儚绱犲湴澶勭悊鍥惧儚锛屽姣忎釜鍍忕礌璋冪敤filterRgb鏂规硶杩涜澶勭悊銆?
     * 閽堝涓嶅悓鐨勫浘鍍忕被鍨嬭繘琛屼簡鎬ц兘浼樺寲锛岄伩鍏嶄笉蹇呰鐨勬牸寮忚浆鎹€?
     *
     * @param src 婧愬浘鍍?
     * @param dst 鐩爣鍥惧儚锛屽彲浠ヤ负null
     * @return 澶勭悊鍚庣殑鍥惧儚
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int width = src.getWidth();
        int height = src.getHeight();
        int type = src.getType();
        WritableRaster srcRaster = src.getRaster();

        if (dst == null) {
            dst = createCompatibleDestImage(src, null);
        }
        WritableRaster dstRaster = dst.getRaster();

        setDimensions(width, height);

        int[] inPixels = new int[width];
        for (int y = 0; y < height; y++) {
            // 閽堝ARGB绫诲瀷鍥惧儚杩涜浼樺寲锛岄伩鍏嶈皟鐢╣etRGB瀵艰嚧鐨勬€ц兘闂
            if (type == BufferedImage.TYPE_INT_ARGB) {
                srcRaster.getDataElements(0, y, width, 1, inPixels);
                for (int x = 0; x < width; x++) {
                    inPixels[x] = filterRgb(x, y, inPixels[x]);
                }
                dstRaster.setDataElements(0, y, width, 1, inPixels);
            } else {
                // 瀵逛簬鍏朵粬绫诲瀷鐨勫浘鍍忥紝浣跨敤鏍囧噯鐨刧etRGB/setRGB鏂规硶
                src.getRGB(0, y, width, 1, inPixels, 0, width);
                for (int x = 0; x < width; x++) {
                    inPixels[x] = filterRgb(x, y, inPixels[x]);
                }
                dst.setRGB(0, y, width, 1, inPixels, 0, width);
            }
        }

        return dst;
    }

    /**
     * 鎶借薄鐨凴GB鍍忕礌婊ら暅鏂规硶
     *
     * 瀛愮被蹇呴』瀹炵幇姝ゆ柟娉曟潵瀹氫箟鍏蜂綋鐨勬护闀滄晥鏋溿€?
     * 姝ゆ柟娉曞鍗曚釜鍍忕礌杩涜澶勭悊锛岃繑鍥炲鐞嗗悗鐨凙RGB鍊笺€?
     *
     * @param x   鍍忕礌鐨刋鍧愭爣
     * @param y   鍍忕礌鐨刌鍧愭爣
     * @param rgb 鍘熷ARGB鍍忕礌鍊?
     * @return 澶勭悊鍚庣殑ARGB鍍忕礌鍊?
     */
    public abstract int filterRgb(int x, int y, int rgb);

    /**
     * 璁剧疆鍥惧儚灏哄
     *
     * 鍦ㄦ护闀滃鐞嗗紑濮嬪墠璋冪敤锛屽瓙绫诲彲浠ラ噸鍐欐鏂规硶鏉ヨ繘琛屽繀瑕佺殑鍒濆鍖栧伐浣溿€?
     *
     * @param width  鍥惧儚瀹藉害
     * @param height 鍥惧儚楂樺害
     */
    public void setDimensions(int width, int height) {
        // 榛樿瀹炵幇涓虹┖锛屽瓙绫诲彲鏍规嵁闇€瑕侀噸鍐?
    }
}
