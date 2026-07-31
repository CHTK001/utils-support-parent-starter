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

    private final NDArray embeddings;

    public SmolDoclingEmbedOutput(NDArray embeddings) {
        this.embeddings = embeddings;
    }

    public NDArray getEmbeddings() {
        return embeddings;
    }
}
