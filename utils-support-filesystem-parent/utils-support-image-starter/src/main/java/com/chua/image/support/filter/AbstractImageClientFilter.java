package com.chua.image.support.filter;

import com.chua.common.support.ai.image.ImageClient;

import java.awt.image.BufferedImage;

/**
 * 鏀寔 AI 瀹㈡埛绔敞鍏ョ殑鍥惧儚婊ら暅鎶借薄鍩虹被
 *
 * <p>缁ф壙 {@link AbstractImageFilter}, 棰濆鎸佹湁 {@link ImageClient} 寮曠敤,
 * 瀛愮被鍙湪 {@link #filter(BufferedImage, BufferedImage)} 鍐呴儴璋冪敤 AI 瀹㈡埛绔? * 瀵瑰浘鍍忚繘琛岃浆鎹€侀噸鐢熸垚銆侀鏍艰縼绉荤瓑楂樼骇鎿嶄綔銆? *
 * <p>浣跨敤绀轰緥:
 * <pre>{@code
 *   // 鍒涘缓 AI 瀹㈡埛绔?(浠绘剰 provider)
 *   ImageClient client = ImageClient.create("openai", "sk-xxx")
 *       .model("dall-e-3")
 *       .size(1024, 1024);
 *
 *   // 娉ㄥ叆鍒版护闀? *   MyFilter filter = new MyFilter().imageClient(client);
 *
 *   // 搴旂敤婊ら暅
 *   BufferedImage result = filter.converter(sourceImage);
 * }</pre> *
 *   // 搴旂敤婊ら暅
 *   BufferedImage result = filter.converter(sourceImage);
 * }</pre>
 *
 * <p>瀛愮被鐨勫吀鍨嬪疄鐜?
 * <pre>{@code
 *   public class MyAiFilter extends AbstractImageClientFilter {
 *       &#64;Override
 *       public BufferedImage filter(BufferedImage src, BufferedImage dst) {
 *           // 璋冪敤 AI 瀹㈡埛绔皢 src 杞崲涓烘柊鍥惧儚
 *           ImageClient c = requireClient();
 *           return c.referenceImage(src)
 *                    .prompt("姘村僵鐢婚鏍?)
 *                    .generate();
 *       }
 *   }
 * }</pre>eferenceImage(src)
 *                    .prompt("姘村僵鐢婚鏍?)
 *                    .generate();
 *       }
 *   }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractImageClientFilter extends AbstractImageFilter {

    /**
      * AI 鍥惧儚鐢熸垚瀹㈡埛绔? 鐢ㄤ簬鍦?过滤器() 鍐呴儴璋冪敤 AI 鑳藉姏
     */
    private ImageClient imageClient;


    /**
     * 璁剧疆 AI 鍥惧儚鐢熸垚瀹㈡埛绔?     *
     * <p>鏀寔閾惧紡璋冪敤, 渚夸簬鍦ㄥ垱寤哄悗绔嬪嵆娉ㄥ叆:
     * <pre>{@code
     *   new MyFilter().imageClient(client);
     * }</pre>
     * }</pre>
     *
     * @param imageClient AI 瀹㈡埛绔疄渚? 浼?空 琛ㄧず绉婚櫎寮曠敤
     * @return 褰撳墠婊ら暅瀹炰緥
     */
    public AbstractImageClientFilter imageClient(ImageClient imageClient) {
        this.imageClient = imageClient;
        return this;
    }


    /**
     * 鑾峰彇褰撳墠鎸佹湁鐨?AI 瀹㈡埛绔?     *
     * @return imageClient, 鍙兘涓?空
     */
    public ImageClient getImageClient() {
        return imageClient;
    }


    /**
      * 鑾峰彇褰撳墠鎸佹湁鐨?AI 瀹㈡埛绔? 鑻ヤ负 空 鍒欐姏鍑哄紓甯?     *
     * <p>瀛愮被鍦?{@link #filter(BufferedImage, BufferedImage)} 鍐呰皟鐢ㄦ鏂规硶鍙繚璇?     * imageClient 宸叉敞鍏? 閬垮厤 NullPointerException銆?     *
     * @return 闈炵┖鐨?imageClient
     * @throws IllegalStateException 褰?镜像客户端 鏈敞鍏ユ椂
     */
    protected ImageClient requireClient() {
        if (imageClient == null) {
            throw new IllegalStateException(
                    getClass().getSimpleName() + " 闇€瑕佸厛娉ㄥ叆 ImageClient, 璇疯皟鐢?imageClient(...) 璁剧疆");
        }
        return imageClient;
    }
}
