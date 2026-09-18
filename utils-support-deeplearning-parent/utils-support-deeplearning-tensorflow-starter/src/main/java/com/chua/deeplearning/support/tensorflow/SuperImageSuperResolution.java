/**
* tensor流 超分辨率。
* <p>基于 {@link com.chua.deeplearning.support.image.ImageEnhancer}，使用 tf-super-resolution 模型进行图像超分辨率重建。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class SuperImageSuperResolution {

    /**
    * tensor流 超分辨率模型名称
    */
    private static final String MODEL_NAME = "tf-super-resolution";

    /**
    * 图像增强器
    */
    private final com.chua.deeplearning.support.image.ImageEnhancer enhancer;

    /**
    * 构造 tensor流 超分辨率实例。
    */
    public SuperImageSuperResolution() {
        this.enhancer = com.chua.deeplearning.support.image.ImageEnhancer.create(MODEL_NAME);
    }

    /**
    * 设置模型路径。
    *
    * @param path 模型路径
    * @return this
    */
    public SuperImageSuperResolution modelPath(String path) {
        this.enhancer.modelPath(path);
        return this;
    }

    /**
    * 设置运行设备。
    *
    * @param device 设备名称（如 cpu、gpu）
    * @return this
    */
    public SuperImageSuperResolution device(String device) {
        this.enhancer.device(device);
        return this;
    }

    /**
    * 执行超分辨率推理。
    *
    * @param imageData 输入图像字节数组
    * @return 高分辨率图像字节数组
    */
    public byte[] superResolution(byte[] imageData) {
        return this.enhancer.enhance(imageData);
    }
}
