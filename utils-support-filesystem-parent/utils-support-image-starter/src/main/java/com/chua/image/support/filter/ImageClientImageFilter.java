package com.chua.image.support.filter;

import com.chua.common.support.ai.image.ImageClient;

import java.awt.image.BufferedImage;

/**
 * AI 鍥惧儚婊ら暅 - 浣跨敤 {@link ImageClient} 杞崲鍥惧儚
 *
 * <p>閫氳繃娉ㄥ叆鐨?AI 瀹㈡埛绔皢婧愬浘鍍忎綔涓哄弬鑰冨浘, 搴旂敤鏂囨湰鎻愮ず璇嶇敓鎴愰鏍煎寲鏂板浘鍍忋€? * 閫傜敤浜?DALL-E銆丮idjourney銆丼table Diffusion 绛夋敮鎸佸浘鐢熷浘(img2img)鐨?AI 鏈嶅姟銆? *
 * <p>浣跨敤绀轰緥:
 * <pre>{@code
 *   // 1. 鍒涘缓 AI 瀹㈡埛绔? *   ImageClient client = ImageClient.create("openai", "sk-xxx")
 *       .model("dall-e-2")
 *       .size(1024, 1024);
 *
 *   // 2. 鍒涘缓婊ら暅骞舵敞鍏ュ鎴风
 *   ImageClientImageFilter filter = new ImageClientImageFilter()
 *       .imageClient(client)
 *       .prompt("杞崲涓烘按褰╃敾椋庢牸")
 *       .imageStrength(0.6);
 *
 *   // 3. 搴旂敤婊ら暅
 *   BufferedImage result = filter.converter(sourceImage);
 * }</pre>
 *
 * <p>娉ㄦ剰浜嬮」:
 * <ul>
 *   <li>蹇呴』鍏堟敞鍏?{@link ImageClient}, 鍚﹀垯 {@link #filter(BufferedImage, BufferedImage)} 浼氭姏鍑哄紓甯?/li>
 *   <li>瀹為檯澶勭悊闇€瑕佽仈缃戣皟鐢?AI 鏈嶅姟, 绂荤嚎鐜涓嬩笉鍙敤</li>
 *   <li>涓嶅悓 provider 瀵?imageStrength 绛夊弬鏁版敮鎸佺▼搴︿笉鍚?/li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ImageClientImageFilter extends AbstractImageClientFilter {

    /**
     * 鎻愮ず璇?     */
    /** 提示词 */
    private String prompt = "";

    /**
     * 鍙傝€冨浘褰卞搷寮哄害, 鑼冨洿 0.0 ~ 1.0
     */
    private double imageStrength = 0.6;


    /**
     * 榛樿鏋勯€? 鍚庣画闇€瑕佹敞鍏?imageClient 骞惰缃?prompt
     */
    public ImageClientImageFilter() {
    }


    /**
     * 璁剧疆 AI 鍥惧儚鐢熸垚瀹㈡埛绔?(瑕嗙洊鐖剁被浠ユ敮鎸侀摼寮忚皟鐢?
     *
     *
     * @param imageClient AI 瀹㈡埛绔疄渚?
     * @return 褰撳墠婊ら暅瀹炰緥
     */
    @Override
    public ImageClientImageFilter imageClient(ImageClient imageClient) {
        super.imageClient(imageClient);
        return this;
    }


    /**
     * 璁剧疆鎻愮ず璇?     *
     * @param prompt 鎻愮ず璇?     * @return 褰撳墠婊ら暅瀹炰緥
     */
    public ImageClientImageFilter prompt(String prompt) {
        this.prompt = prompt == null ? "" : prompt;
        return this;
    }


    /**
     * 璁剧疆鍙傝€冨浘褰卞搷寮哄害
     *
     * @param imageStrength 寮哄害, 鑼冨洿 0.0 ~ 1.0
     * @return 褰撳墠婊ら暅瀹炰緥
     */
    public ImageClientImageFilter imageStrength(double imageStrength) {
        this.imageStrength = Math.max(0.0, Math.min(1.0, imageStrength));
        return this;
    }


    /**
     * 搴旂敤 AI 鍥惧儚杞崲
     *
     * <p>璋冪敤娉ㄥ叆鐨?{@link ImageClient}, 灏嗘簮鍥惧儚浣滀负鍙傝€冨浘,
     * 缁撳悎 prompt 鍜?imageStrength 璋冪敤 {@code referenceImage(src).prompt(...).imageStrength(...).generate()}銆?     *
     *
     * @param src 婧愬浘鍍?
     * @param dst 鐩爣鍥惧儚 (鏈护闀滃拷鐣? 濮嬬粓鍒涘缓鏂板浘鍍?
     * @return AI 鐢熸垚鐨勬柊鍥惧儚
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        ImageClient client = requireClient();
        return client
                .referenceImage(src)
                .prompt(prompt)
                .imageStrength(imageStrength)
                .generate();
    }
}