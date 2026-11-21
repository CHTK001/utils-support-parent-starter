package com.chua.deeplearning.support.onnx.smoldocling;

import ai.djl.ndarray.NDArray;

/**
 * smoldocling Embed
 *
 * @author CH
 * @since 2025/01/22
 */
public record SmolDoclingEmbedOutput(NDArray embeddings) {

    /**
     * 创建 smoldoclingembed输出 实例
     * @param embeddings 嵌入
     */
    public SmolDoclingEmbedOutput {
    }

    /**
     * 获取嵌入
     *
     * @return 获取嵌入的结果
     */
    public NDArray getEmbeddings() {
        return embeddings;
    }
}
