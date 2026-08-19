package com.chua.deeplearning.support.onnx.smoldocling;

import ai.djl.ndarray.NDArray;

/**
 * SmolDocling Embed                
 *
 * @author CH
 * @version 4.0.0.32
 * @since 2025/01/22
 */
public class SmolDoclingEmbedOutput {

    /** 嵌入向量 */
    /** Embeddings */
    private final NDArray embeddings;

    /**
     * 创建 SmolDoclingEmbedOutput 实例
     * @param embeddings embeddings
     */
    public SmolDoclingEmbedOutput(NDArray embeddings) {
        this.embeddings = embeddings;
    }

    /** 获取Embeddings */
    public NDArray getEmbeddings() {
        return embeddings;
    }
}
