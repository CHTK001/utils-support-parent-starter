package com.chua.image.support.filter;

import com.chua.common.support.ai.image.ImageClient;

import java.awt.image.BufferedImage;

/**
 * 支持 AI 客户端注入的图像滤镜抽象基类
 *
 * <p>继承 {@link AbstractImageFilter}, 额外持有 {@link ImageClient} 引用,
 * 子类可在 {@link #filter(BufferedImage, BufferedImage)} 内部调用 AI 客户端
 * 对图像进行转换、重生成、风格迁移等高级操作。
 *
 * <p>使用示例:
 * <pre>{@code
 *   // 创建 AI 客户端 (任意 provider)
 *   ImageClient client = ImageClient.create("openai", "sk-xxx")
 *       .model("dall-e-3")
 *       .size(1024, 1024);
 *
 *   // 注入到滤镜
 *   MyFilter filter = new MyFilter().imageClient(client);
 *
 *   // 应用滤镜
 *   BufferedImage result = filter.converter(sourceImage);
 * }</pre>
 *
 * <p>子类的典型实现:
 * <pre>{@code
 *   public class MyAiFilter extends AbstractImageClientFilter {
 *       &#64;Override
 *       public BufferedImage filter(BufferedImage src, BufferedImage dst) {
 *           // 调用 AI 客户端将 src 转换为新图像
 *           ImageClient c = requireClient();
 *           return c.referenceImage(src)
 *                    .prompt("水彩画风格")
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
     * AI 图像生成客户端, 用于在 filter() 内部调用 AI 能力
     */
    private ImageClient imageClient;


    /**
     * 设置 AI 图像生成客户端
     *
     * <p>支持链式调用, 便于在创建后立即注入:
     * <pre>{@code
     *   new MyFilter().imageClient(client);
     * }</pre>
     *
     * @param imageClient AI 客户端实例, 传 null 表示移除引用
     * @return 当前滤镜实例
     */
    public AbstractImageClientFilter imageClient(ImageClient imageClient) {
        this.imageClient = imageClient;
        return this;
    }


    /**
     * 获取当前持有的 AI 客户端
     *
     * @return imageClient, 可能为 null
     */
    public ImageClient getImageClient() {
        return imageClient;
    }


    /**
     * 获取当前持有的 AI 客户端, 若为 null 则抛出异常
     *
     * <p>子类在 {@link #filter(BufferedImage, BufferedImage)} 内调用此方法可保证
     * imageClient 已注入, 避免 NullPointerException。
     *
     * @return 非空的 imageClient
     * @throws IllegalStateException 当 imageClient 未注入时
     */
    protected ImageClient requireClient() {
        if (imageClient == null) {
            throw new IllegalStateException(
                    getClass().getSimpleName() + " 需要先注入 ImageClient, 请调用 imageClient(...) 设置");
        }
        return imageClient;
    }
}
