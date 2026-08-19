package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.utils.BufferedImageUtils;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * Sobel 鏉堝湱绱Λ鈧ù瀣禈閸嶅繑鎶ら梹?
 *
 * 鐎圭偟骞?Sobel 缁犳鐡欐潻娑滎攽鏉堝湱绱Λ鈧ù瀣剁礉闁俺绻冪拋锛勭暬閸ユ儳鍎氬顖氬閺夈儴鐦戦崚顐㈡嫲缁愪礁鍤弰鍓с仛閸ユ儳鍎氭稉顓犳畱鏉堝湱绱妴?
 * Sobel 缁犳鐡欓弰顖欑缁夊秶绮￠崗鍝ユ畱鏉堝湱绱Λ鈧ù瀣暬濞夋洩绱濋崷銊吀缁犳婧€鐟欏棜顫庨崪灞芥禈閸嶅繐顦╅悶鍡曡厬楠炴寧纭炬惔鏃傛暏閵?
 *
 * 閹垛偓閺堫垰甯悶鍡窗
 * - 娴ｈ法鏁?3x3 閸楅袧閺嶆瓕顓哥粻妤€娴橀崓蹇旑潽鎼?
 * - 閸掑棗鍩嗙拋锛勭暬濮樻潙閽╅弬鐟版倻閿涘湺閺傜懓鎮滈敍澶婃嫲閸ㄥ倻娲块弬鐟版倻閿涘湻閺傜懓鎮滈敍澶屾畱濮婎垰瀹?
 * - 闁俺绻冩稉鈧梼璺侯嚤閺佹媽绻庢导鍏碱梾濞村绔熺紓?
 * - 鐎电懓娅旀竟鏉垮徔閺堝绔寸€规氨娈戦幎鎴濆煑閼宠棄濮?
 *
 * Sobel 缁犳鐡欓敍?
 * X閺傜懓鎮滈敍鍫熸寜楠炲疇绔熺紓妯活梾濞村绱氶敍?
 * [-1  0  1]
 * [-2  0  2]
 * [-1  0  1]
 *
 * Y閺傜懓鎮滈敍鍫濈€惄纾嬬珶缂傛ɑ顥呭ù瀣剁礆閿?
 * [-1 -2 -1]
 * [ 0  0  0]
 * [ 1  2  1]
 *
 * 缁犳纭堕悧鍦仯閿?
 * - 鐠侊紕鐣婚弫鍫㈠芳妤傛﹫绱濋柅鍌氭値鐎圭偞妞傛径鍕倞
 * - 鐎电懓娅旀竟鐗堟箒娑撯偓鐎规氨娈戦獮铏拨娴ｆ粎鏁?
 * - 閼宠棄顧勫Λ鈧ù瀣╃瑝閸氬本鏌熼崥鎴犳畱鏉堝湱绱?
 * - 鏉堝湱绱€规矮缍呯划鎯у鏉堝啫銈?
 *
 * 鎼存梻鏁ら崷鐑樻珯閿?
 * - 鏉堝湱绱Λ鈧ù瀣剁窗鐠囧棗鍩嗛崶鎯у剼娑擃厾娈戦悧鈺€缍嬫潪顔肩波
 * - 閻楃懓绶涢幓鎰絿閿涙矮璐熼崥搴ｇ敾閸ユ儳鍎氶崚鍡樼€介幓鎰返閻楃懓绶?
 * - 閸ユ儳鍎氶崚鍡楀閿涙艾鐔€娴滃氦绔熺紓妯逛繆閹垵绻樼悰灞藉隘閸╃喎鍨庨崜?
 * - 閻╊喗鐖ｇ拠鍡楀焼閿涙俺绶熼崝鈺冨⒖娴ｆ捁鐦戦崚顐㈡嫲鐠虹喕閲?
 * - 閸栬顒熻ぐ鍗炲剼閿涙艾灏扮€涳箑娴橀崓蹇曟畱鏉堝湱绱晶鐐插繁
 * - 瀹搞儰绗熷Λ鈧ù瀣剁窗娴溠冩惂鐠愩劑鍣哄Λ鈧ù瀣╄厬閻ㄥ嫯绔熺紓妯哄瀻閺?
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@SpiDescribe("Sobel鏉堝湱绱Λ鈧ù瀣姢闂€?)
@Spi("sobel")
@AllArgsConstructor
@NoArgsConstructor
public class ImageSobelFilter extends AbstractImageFilter {

    /**
     * Sobel Y閺傜懓鎮滈敍鍫濈€惄纾嬬珶缂傛ɑ顥呭ù瀣剁礆閸楅袧閺?
     */
    public static int[] sobelY = new int[]{-1, -2, -1, 0, 0, 0, 1, 2, 1};

    /**
     * Sobel X閺傜懓鎮滈敍鍫熸寜楠炲疇绔熺紓妯活梾濞村绱氶崡椋幮濋弽?
     */
    public static int[] sobelX = new int[]{-1, 0, 1, -2, 0, 2, -1, 0, 1};

    /**
     * 閺勵垰鎯佹担璺ㄦ暏X閺傜懓鎮滃Λ鈧ù瀣剁礉true娑撶閺傜懓鎮滈敍鍫燁梾濞村鐎惄纾嬬珶缂傛﹫绱氶敍瀹朼lse娑撶閺傜懓鎮滈敍鍫燁梾濞村鎸夐獮瀹犵珶缂傛﹫绱?
     */
    private boolean xdirect = true;

    /**
     * 閹笛嗩攽 Sobel 鏉堝湱绱Λ鈧ù瀣姢闂€婊冾槱閻?
     *
     * 鐎电懓娴橀崓蹇撶安閻?Sobel 缁犳鐡欐潻娑滎攽鏉堝湱绱Λ鈧ù瀣ㄢ偓鍌涚壌閹?xdirect 閸欏倹鏆熼柅澶嬪濡偓濞村鏌熼崥鎴窗
     * - true閿涙矮濞囬悽?X 閺傜懓鎮滅粻妤€鐡欓敍灞绢梾濞村鐎惄纾嬬珶缂?
     * - false閿涙矮濞囬悽?Y 閺傜懓鎮滅粻妤€鐡欓敍灞绢梾濞村鎸夐獮瀹犵珶缂?
     *
     * @param src 濠ф劕娴橀崓?
     * @param dst 閻╊喗鐖ｉ崶鎯у剼閿涘牊顒濋崣鍌涙殶閺堫亙濞囬悽顭掔礆
     * @return 鏉堝湱绱Λ鈧ù瀣倵閻ㄥ嫬娴橀崓?
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int total = width * height;
        // RGB娑撳閲滈柅姘朵壕閻ㄥ嫯绶崙鐑樻殶缂?
        byte[][] output = new byte[3][total];

        int offset = 0;
        // Sobel 閸楅袧閺嶅摜娈?娑擃亞閮撮弫?
        int k0 = 0, k1 = 0, k2 = 0;
        int k3 = 0, k4 = 0, k5 = 0;
        int k6 = 0, k7 = 0, k8 = 0;

        // 閺嶈宓佸Λ鈧ù瀣煙閸氭垿鈧瀚ㄩ惄绋跨安閻?Sobel 缁犳鐡?
        if (xdirect) {
            // X閺傜懓鎮滅粻妤€鐡欓敍姘梾濞村鐎惄纾嬬珶缂?
            k0 = sobelX[0]; k1 = sobelX[1]; k2 = sobelX[2];
            k3 = sobelX[3]; k4 = sobelX[4]; k5 = sobelX[5];
            k6 = sobelX[6]; k7 = sobelX[7]; k8 = sobelX[8];
        } else {
            // Y閺傜懓鎮滅粻妤€鐡欓敍姘梾濞村鎸夐獮瀹犵珶缂?
            k0 = sobelY[0]; k1 = sobelY[1]; k2 = sobelY[2];
            k3 = sobelY[3]; k4 = sobelY[4]; k5 = sobelY[5];
            k6 = sobelY[6]; k7 = sobelY[7]; k8 = sobelY[8];
        }

        // 濮婎垰瀹崇拋锛勭暬缂佹挻鐏?
        int sr = 0, sg = 0, sb = 0;
        int r = 0, g = 0, b = 0;

        // 闁秴宸婚崶鎯у剼閸嶅繒绀岄敍鍫ｇ儲鏉╁洩绔熼悾灞藉剼缁辩媴绱濋崶鐘辫礋闂団偓鐟?x3闁鐓欓敍?
        for (int row = 1; row < height - 1; row++) {
            offset = row * width;
            for (int col = 1; col < width - 1; col++) {

        // 鐠侊紕鐣荤痪銏ｅ闁岸浜鹃惃?Sobel 濮婎垰瀹抽敍灞炬煙閸氭垿銆庢惔蹇ョ窗瀹革缚绗傞妴浣风瑐閵嗕礁褰告稉濞库偓浣镐箯閵嗕椒鑵戣箛鍐︹偓浣稿礁閵嗕礁涔忔稉瀣ㄢ偓浣风瑓閵嗕礁褰告稉?
        sr = k0 * (rArr[offset - width + col - 1] & 0xff)
                + k1 * (rArr[offset - width + col] & 0xff)
                + k2 * (rArr[offset - width + col + 1] & 0xff)
                + k3 * (rArr[offset + col - 1] & 0xff)
                + k4 * (rArr[offset + col] & 0xff)
                + k5 * (rArr[offset + col + 1] & 0xff)
                + k6 * (rArr[offset + width + col - 1] & 0xff)
                + k7 * (rArr[offset + width + col] & 0xff)
                + k8 * (rArr[offset + width + col + 1] & 0xff);

                // 鐠侊紕鐣荤紒鑳闁岸浜鹃惃?Sobel 濮婎垰瀹?
                sg = k0 * (gArr[offset - width + col - 1] & 0xff)
                        + k1 * (gArr[offset - width + col] & 0xff)
                        + k2 * (gArr[offset - width + col + 1] & 0xff)
                        + k3 * (gArr[offset + col - 1] & 0xff)
                        + k4 * (gArr[offset + col] & 0xff)
                        + k5 * (gArr[offset + col + 1] & 0xff)
                        + k6 * (gArr[offset + width + col - 1] & 0xff)
                        + k7 * (gArr[offset + width + col] & 0xff)
                        + k8 * (gArr[offset + width + col + 1] & 0xff);

                // 鐠侊紕鐣婚拑婵婂闁岸浜鹃惃?Sobel 濮婎垰瀹?
                sb = k0 * (bArr[offset - width + col - 1] & 0xff)
                        + k1 * (bArr[offset - width + col] & 0xff)
                        + k2 * (bArr[offset - width + col + 1] & 0xff)
                        + k3 * (bArr[offset + col - 1] & 0xff)
                        + k4 * (bArr[offset + col] & 0xff)
                        + k5 * (bArr[offset + col + 1] & 0xff)
                        + k6 * (bArr[offset + width + col - 1] & 0xff)
                        + k7 * (bArr[offset + width + col] & 0xff)
                        + k8 * (bArr[offset + width + col + 1] & 0xff);

                // 娣囨繂鐡ㄥ顖氬鐠侊紕鐣荤紒鎾寸亯
                r = sr;
                g = sg;
                b = sb;

                // 鐏忓棛绮ㄩ弸婊堟閸掕泛婀張澶嬫櫏閼煎啫娲块崘鍛嫙鐎涙ê鍋?
                output[0][offset + col] = (byte) BufferedImageUtils.clamp(r);
                output[1][offset + col] = (byte) BufferedImageUtils.clamp(g);
                output[2][offset + col] = (byte) BufferedImageUtils.clamp(b);

                // 闁插秶鐤嗗顖氬閸婄》绱濋崙鍡楊槵婢跺嫮鎮婃稉瀣╃娑擃亜鍎氱槐?
                sr = 0;
                sg = 0;
                sb = 0;
            }
        }

        // 鐏忓棗顦╅悶鍡楁倵閻ㄥ嚧GB閺佺増宓佺拋鍓х枂閸ョ偛娴橀崓?
        putRgb(output[0], output[1], output[2]);
        return toBitmap();
    }
}
