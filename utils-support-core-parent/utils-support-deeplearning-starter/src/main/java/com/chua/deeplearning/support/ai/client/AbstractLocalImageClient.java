package com.chua.deeplearning.support.ai.client;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.image.ImageClientSetting;
import com.chua.common.support.ai.image.ImageResponse;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
* 本地引擎图像生成客户端抽象基类。
* <p>
* 统一实现 {@link ImageClient} 的公共逻辑：通过 {@link IdentificationEngine} 获取
* 已注册的文生图翻译器执行图像生成，并提供该引擎的模型列表。
* 子类只需指定引擎名称（如 "onnx"、"pytorch"）。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public abstract class AbstractLocalImageClient implements ImageClient {

    /**
    * 引擎名称（提供者）
    */
    protected final String engine;

    /**
    * 识别引擎实例
    */
    protected final IdentificationEngine identificationEngine;

    /**
    * 当前模型名称
    */
    protected String model;

    /**
    * 生成宽度
    */
    protected Integer width;

    /**
    * 生成高度
    */
    protected Integer height;

    /**
    * 当前提示词
    */
    protected String prompt;

    /**
    * 随机种子
    */
    protected Long seed;

    /**
    * 构造本地图像生成客户端。
    *
    * @param engine  引擎名称，如 "onnx"、"pytorch"
    * @param setting 客户端配置
    */
    protected AbstractLocalImageClient(String engine, ImageClientSetting setting) {
        this.engine = engine;
        this.identificationEngine = AbstractIdentificationEngine.getInstance();
        this.model = setting != null ? setting.getModel() : null;
        this.width = setting != null ? setting.getWidth() : null;
        this.height = setting != null ? setting.getHeight() : null;
        this.prompt = setting != null ? setting.getPrompt() : null;
        this.seed = setting != null ? setting.getSeed() : null;
    }

    @Override
    /** 模型 */
    public ImageClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    /** 获取大小 */
    public ImageClient size(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    @Override
    /** 提示符 */
    public ImageClient prompt(String prompt) {
        this.prompt = prompt;
        return this;
    }

    @Override
    /** Seed */
    public ImageClient seed(Long seed) {
        this.seed = seed;
        return this;
    }

    /**
    * 解析实际使用的模型名称。
    *
    * <p>{@code auto} / 空值表示按当前服务器硬件配置自动挑选推荐模型，
    * 否则返回显式指定的模型名。</p>
    *
    * @return 模型名称
    */
    protected String resolveModel() {
        if (model != null && !model.isBlank() && !"auto".equalsIgnoreCase(model)) {
            return model;
        }
        String recommended = DeeplearningModels.recommended(engine, null);
        if (recommended != null) {
            return recommended;
        }
        List<ModelDefinition> defs = models();
        if (defs.isEmpty()) {
            throw new IllegalStateException("引擎[" + engine + "]没有可用的图像生成模型");
        }
        return defs.getFirst().getId();
    }

    @Override
    /** Generate */
    public BufferedImage generate(String prompt) {
        if (prompt != null) {
            this.prompt = prompt;
        }
        String modelName = resolveModel();
        @SuppressWarnings("unchecked")
        ITranslator<Object, Object> translator =
                (ITranslator<Object, Object>) identificationEngine.get(modelName, ITranslator.class);
        if (translator == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        Object result = translator.translate(this.prompt);
        return toBufferedImage(result, modelName);
    }

    /**
    * 将翻译器输出转换为 缓冲镜像。
    *
    * @param result    翻译器输出
    * @param modelName 模型名称
    * @return BufferedImage
    */
    private static BufferedImage toBufferedImage(Object result, String modelName) {
        if (result instanceof BufferedImage image) {
            return image;
        }
        if (result instanceof ai.djl.modality.cv.Image image) {
            Object wrapped = image.getWrappedImage();
            if (wrapped instanceof BufferedImage bufferedImage) {
                return bufferedImage;
            }
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                image.save(bos, "png");
                return ImageUtils.toBufferedImage(bos.toByteArray());
            } catch (Exception e) {
                throw new IllegalStateException("模型图像转换失败: " + modelName, e);
            }
        }
        throw new IllegalStateException("模型输出不是图像: " + modelName + " -> " + result);
    }

    @Override
    /** 创建任务 */
    public String createTask(String prompt) {
        throw new UnsupportedOperationException("本地图像生成不支持异步任务模式");
    }

    @Override
    /** 查询任务 */
    public ImageResponse queryTask(String taskId) {
        throw new UnsupportedOperationException("本地图像生成不支持异步任务模式");
    }

    @Override
    /** 模型 */
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine);
    }
}
