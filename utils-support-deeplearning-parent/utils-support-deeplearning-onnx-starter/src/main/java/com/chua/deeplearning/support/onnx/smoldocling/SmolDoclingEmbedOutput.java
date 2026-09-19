package com.chua.deeplearning.support.onnx.smoldocling;

import ai.djl.ndarray.NDArray;

/**
 * smoldocling Embed
 *
 * @author CH
 * @since 2025/01/22
 */
public class SmolDoclingEmbedOutput {

    /** 嵌入向量 */
    private final NDArray embeddings;

    /**
     * 创建 smoldoclingembed输出 实例
     * @param embeddings 嵌入
     */
    public SmolDoclingEmbedOutput(NDArray embeddings) {
        this.embeddings = embeddings;
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
