package com.chua.deeplearning.support.audio;

import java.util.List;

/**
 * 音频识别管线回调接口，用于测试时捕获各阶段中间数据。
 *
 * <p>所有方法均为 default 实现（no-op），测试时按需覆写需要观察的阶段即可。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public interface AudioRecognitionPipelineCallback {

    /**
     * VAD 切分完成回调。
     *
     * @param segments VAD 切分的语音片段
     */
    default void onVad(List<SpeakerSegment> segments) {
    }

    /**
     * 说话人嵌入提取完成回调。
     *
     * @param embeddings 说话人嵌入向量
     */
    default void onEmbedding(float[][] embeddings) {
    }

    /**
     * 说话人聚类完成回调。
     *
     * @param assignments 说话人分配结果
     */
    default void onCluster(String[] assignments) {
    }

    /**
     * ASR 转写完成回调。
     *
     * @param transcripts 转写结果
     */
    default void onTranscribe(String[] transcripts) {
    }

    /**
     * 管线完成回调。
     *
     * @param result 最终结果
     * @param elapsedMs 耗时毫秒
     */
    default void onComplete(List<SpeakerSegment> result, long elapsedMs) {
    }
}
