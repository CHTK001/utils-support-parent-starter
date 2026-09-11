package com.chua.deeplearning.support.core.provider;

import com.chua.deeplearning.support.core.api.Model3DConfig;
import com.chua.deeplearning.support.core.model.Model3DFormat;
import com.chua.deeplearning.support.core.model.Model3DStyle;
import com.chua.deeplearning.support.core.model.DefaultModel3D;
import com.chua.deeplearning.support.core.model.Model3D;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.IOException;
import java.util.Base64;

/**
 * Forge 风格 API 3D 生成器
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ForgeStyleApiModel3DGenerator extends AbstractApiModel3DGenerator {

    /**
     * 创建 ForgeStyleApiModel3DGenerator 实例
     * @param config config
     */
    public ForgeStyleApiModel3DGenerator(Model3DConfig config) {
        super(config);
    }

    @Override
    /** 构建TextRequestBody */
    protected String buildTextRequestBody(String prompt, Model3DFormat format, Model3DStyle style, String quality) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("prompt", prompt);
            node.put("format", format.getExtension());
            node.put("style", style.getCode());
            if (quality != null) {
                node.put("quality", quality);
            } else {
                node.put("quality", config.getDefaultQuality());
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("构建文本请求体失败", e);
        }
    }

    @Override
    /** 构建ImageRequestBody */
    protected String buildImageRequestBody(byte[] image, Model3DFormat format, Model3DStyle style, String quality) {
        try {
            String base64 = Base64.getEncoder().encodeToString(image);
            ObjectNode node = objectMapper.createObjectNode();
            node.put("image", base64);
            node.put("format", format.getExtension());
            node.put("style", style.getCode());
            if (quality != null) {
                node.put("quality", quality);
            } else {
                node.put("quality", config.getDefaultQuality());
            }
            node.put("mime_type", "image/png");
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("构建图片请求体失败", e);
        }
    }

    @Override
    /** 构建MultiViewRequestBody */
    protected String buildMultiViewRequestBody(byte[][] images, Model3DFormat format, Model3DStyle style, String quality) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("format", format.getExtension());
            node.put("style", style.getCode());
            if (quality != null) {
                node.put("quality", quality);
            } else {
                node.put("quality", config.getDefaultQuality());
            }
            node.put("multi_view", true);
            for (int i = 0; i < images.length; i++) {
                String base64 = Base64.getEncoder().encodeToString(images[i]);
                node.put("view_" + (i + 1), base64);
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("构建多视角请求体失败", e);
        }
    }

    @Override
    /** 构建SketchRequestBody */
    protected String buildSketchRequestBody(byte[] sketch, String description, Model3DFormat format, Model3DStyle style, String quality) {
        try {
            String base64 = Base64.getEncoder().encodeToString(sketch);
            ObjectNode node = objectMapper.createObjectNode();
            node.put("sketch", base64);
            node.put("description", description);
            node.put("format", format.getExtension());
            node.put("style", style.getCode());
            if (quality != null) {
                node.put("quality", quality);
            } else {
                node.put("quality", config.getDefaultQuality());
            }
            node.put("mime_type", "image/png");
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("构建草图请求体失败", e);
        }
    }

    @Override
    /** 构建StylizeRequestBody */
    protected String buildStylizeRequestBody(Model3D model, Model3DStyle style, int resolution) {
        try {
            String modelBase64 = Base64.getEncoder().encodeToString(model.getData().readAllBytes());
            ObjectNode node = objectMapper.createObjectNode();
            node.put("model", modelBase64);
            node.put("style", style.getCode());
            node.put("resolution", resolution);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("构建风格化请求体失败", e);
        }
    }
}