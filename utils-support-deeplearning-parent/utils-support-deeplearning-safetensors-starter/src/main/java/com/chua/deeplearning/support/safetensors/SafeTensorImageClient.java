package com.chua.deeplearning.support.safetensors;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.image.ImageClientSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalImageClient;

import java.util.List;

/**
 * SafeTensor 本地文生图客户端（HTTP 网关）。
 * <p>
 * 通过本地 SafeTensorService（localhost:8765）调度文生图类模型（tiny-sd、sd1.5、flux 等），
 * 统一以 {@link ImageClient} 对外提供图像生成能力。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("safetensors")
public class SafeTensorImageClient extends AbstractLocalImageClient {

    /**
     * 构造 SafeTensor 文生图客户端。
     *
     * @param setting 客户端配置
     */
    public SafeTensorImageClient(ImageClientSetting setting) {
        super("safetensors", setting);
    }

    @Override
    /** Models */
    public List<ModelDefinition> models() {
        return SafeTensorModels.ofType("image_gen");
    }
}
