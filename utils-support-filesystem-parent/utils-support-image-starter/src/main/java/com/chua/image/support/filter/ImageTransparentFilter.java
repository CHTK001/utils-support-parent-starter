package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * @author CH
 * @since 4.0.0.42
 */
/**
 * 閸ユ儳鍎氶柅蹇旀鎼达箑顦╅悶鍡樻姢闂€?
 *
 * 鐎电懓娴橀崓蹇氱箻鐞涘矂鈧繑妲戞惔锕€顦╅悶鍡礉鐏忓棔绗夐柅蹇旀閻ㄥ嫬娴橀崓蹇氭祮閹诡澀璐熼崗閿嬫箒闁繑妲戦懗灞炬珯閻ㄥ嫬娴橀崓蹇嬧偓?
 * 闁俺绻冮崚鍡樼€介崓蹇曠閻ㄥ嫰顤侀懝鎻掆偓鍏兼降閸掋倖鏌囬崫顏冪昂閸栧搫鐓欐惔鏃囶嚉閸欐ü璐熼柅蹇旀閿涘苯鐖堕悽銊ょ艾閼冲本娅欑粔濠氭珟閸滃苯娴橀崓蹇撴値閹存劑鈧?
 *
 * 閹垛偓閺堫垰甯悶鍡窗
 * - 閸掑棙鐎藉В蹇庨嚋閸嶅繒绀岄惃鍑碐B閸?
 * - 閺嶈宓佹０婊嗗閻╅晲鎶€鎼达箑鍨介弬顓熸Ц閸氾缚璐熼懗灞炬珯
 * - 鐏忓棜鍎楅弲顖氬剼缁辩姷娈慉lpha闁岸浜剧拋鍓х枂娑撴椽鈧繑妲?
 * - 娣囨繃瀵旈崜宥嗘珯閸嶅繒绀岄惃鍕斧婵顤侀懝鎻掓嫲娑撳秹鈧繑妲戞惔?
 *
 * 缁犳纭跺ù浣衡柤閿?
 * 1. 闁秴宸婚崶鎯у剼閻ㄥ嫭鐦℃稉顏勫剼缁?
 * 2. 閹绘劕褰囬崓蹇曠閻ㄥ嚧GB妫版粏澹婇崐?
 * 3. 閸掋倖鏌囬弰顖氭儊娑撻缚鍎楅弲顖烆杹閼硅绱欓柅姘埗閺勵垳娅ч懝鍙夊灗閸忔湹绮崡鏇氱妫版粏澹婇敍?
 * 4. 鐠佸墽鐤嗛懗灞炬珯閸嶅繒绀屾稉鍝勭暚閸忋劑鈧繑妲?
 * 5. 娣囨繃瀵旈崜宥嗘珯閸嶅繒绀岄惃鍕斧婵顤侀懝?
 *
 * 婢跺嫮鎮婇悧鍦仯閿?
 * - 閼奉亜濮╅懗灞炬珯濡偓濞村绱伴崺杞扮艾妫版粏澹婇惄闀愭妧鎼?
 * - 鏉堝湱绱穱婵囧瘮閿涙矮绻氶幐浣稿閺咁垰顕挒锛勬畱濞撳懏娅氭潏鍦喘
 * - 闁繑妲戞惔锔界瑤閸欐﹫绱伴弨顖涘瘮閸楀﹪鈧繑妲戦弫鍫熺亯
 * - 妫版粏澹婃穱婵堟埂閿涙矮绻氶幐浣稿閺咁垶顤侀懝韫瑝閸?
 *
 * 鎼存梻鏁ら崷鐑樻珯閿?
 * - 閼冲本娅欑粔濠氭珟閿涙艾骞撻梽銈呮禈閸嶅繒娈戦崡鏇″閼冲本娅?
 * - 閸ユ儳鍎氶崥鍫熷灇閿涙矮璐熼崶鎯у剼閸欑姴濮為崙鍡楊槵闁繑妲戦懗灞炬珯
 * - Logo婢跺嫮鎮婇敍姘灡瀵ゆ椽鈧繑妲戦懗灞炬珯閻ㄥ嫭鐖ｈ箛妤€娴橀崓?
 * - 娴溠冩惂閹藉嫬濂栭敍姘箵闂勩倓楠囬崫浣哄弾閻楀洨娈戦懗灞炬珯
 * - 缂冩垿銆夌拋鎹愵吀閿涙艾鍨卞娲偓蹇旀閼冲本娅欓惃鍕禈閺嶅洤鎷伴崗鍐
 *
 * 濞夈劍鍓版禍瀣€嶉敍?
 * - 瑜版挸澧犵€圭偟骞囨稉鏄忣洣闁藉牆顕惂鍊熷閼冲本娅?
 * - 鐎甸€涚艾婢跺秵娼呴懗灞炬珯閸欘垵鍏橀棁鈧憰浣规纯妤傛楠囬惃鍕暬濞?
 * - 瀵ら缚顔呮潏鎾冲弳閸ユ儳鍎氶崗閿嬫箒濞撳懏娅氶惃鍕閺咁垰鎷伴懗灞炬珯鐎佃鐦?
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@Spi("transparent")
@SpiDescribe("闁繑妲戞惔锕佸剹閺咁垳些闂勩倖鎶ら梹?)
public class ImageTransparentFilter extends AbstractImageFilter{
    /**
     * 鐎电懓娴橀崓蹇撶安閻劑鈧繑妲戞惔锕佺箖濠娿們鈧?
     * 濮濄倖鏌熷▔鏇熸，閸︺劏顫︾€涙劗琚憰鍡欐磰閿涘奔浜掔€圭偟骞囬崗铚傜秼閻ㄥ嫰鈧繑妲戞惔锕佺箖濠娿倝鈧槒绶妴?
     * 瑜版挸澧犵€圭偟骞囨潻鏂挎礀 null閿涘矁銆冪粈鍝勭毣閺堫亜鐤勯悳鏉垮徔娴ｆ挾娈戞潻鍥ㄦ姢闁槒绶妴?
     *
     * @param src 閸樼喎顫愰崶鎯у剼閿涘苯鐨㈢€佃顒濋崶鎯у剼鏉╂稖顢戦柅蹇旀鎼达箑顦╅悶?
     * @param dst 閻╊喗鐖ｉ崶鎯у剼閿涘苯顦╅悶鍡楁倵閻ㄥ嫬娴橀崓蹇撶殺鐎涙ê鍋嶉崷銊︻劃閸欏倹鏆熸稉顓烆洤閺嬫粈璐?null閿涘苯绨查崚娑樼紦娑撯偓娑擃亝鏌婇惃鍕禈閸嶅繐顕挒鈩冩降鐎涙ê鍋嶇紒鎾寸亯閵?
     * @return 鏉╂柨娲栫紒蹇氱箖闁繑妲戞惔锕€顦╅悶鍡欐畱閸ユ儳鍎氳ぐ鎾冲鐎圭偟骞囨潻鏂挎礀 null閵?
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        BufferedImage newImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        // 闁秴宸诲В蹇庨嚋閸嶅繒绀?
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // 閼惧嘲褰囪ぐ鎾冲閸嶅繒绀岄惃鍑碐B閸?
                int rgba = src.getRGB(x, y);

                // 鐏忓摏GB閸婅壈娴嗛幑顫礋妫版粏澹婄€电钖?
                Color color = new Color(rgba, true);

                // 婵″倹鐏夎ぐ鎾冲閸嶅繒绀岄弰顖炵拨閼硅绱濋崚娆忕殺Alpha闁岸浜鹃崐鑹邦啎缂冾喕璐?
                if (color.getRed() == 0 && color.getGreen() == 0 && color.getBlue() == 0) {
                    color = new Color(0, 0, 0, 0);
                }

                // 鐏忓棔鎱ㄩ弨鐟版倵閻ㄥ嫰顤侀懝鑼额啎缂冾喖鍩岄弬鎵畱BufferedImage娑?
                newImage.setRGB(x, y, color.getRGB());
            }
        }
        return newImage;
    }
}
