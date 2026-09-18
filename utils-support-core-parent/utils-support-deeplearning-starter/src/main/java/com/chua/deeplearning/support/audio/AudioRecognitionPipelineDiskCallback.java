package com.chua.deeplearning.support.audio;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
* 音频识别管线磁盘回调：自动将各阶段中间数据落盘，供人工/脚本查看真实流程效果。
*
* <p>各阶段数据输出到日志：VAD 切分、嵌入、聚类、转写结果。</p>
*
* @author CH
* @since 4.0.0.43
* @param arr arr
* @return 去重数量的结果
* @param result 结果
* @param elapsedMs elapsedms
* @param segments segments
 */
@Slf4j
public class AudioRecognitionPipelineDiskCallback implements AudioRecognitionPipelineCallback {

    @Override
    public void onVad(List<SpeakerSegment> segments) {
        log.info("[audio-callback] VAD 切分完成：{} 个语音片段", segments.size());
    }

    @Override
    public void onEmbedding(float[][] embeddings) {
        log.info("[audio-callback] 说话人嵌入提取完成：{} 个片段", embeddings.length);
    }

    @Override
    public void onCluster(String[] assignments) {
        log.info("[audio-callback] 说话人聚类完成：{} 个说话人", distinctCount(assignments));
    }

    @Override
    public void onTranscribe(String[] transcripts) {
        log.info("[audio-callback] ASR 转写完成：{} 个片段", transcripts.length);
    }

    @Override
    public void onComplete(List<SpeakerSegment> result, long elapsedMs) {
        log.info("[audio-callback] 管线完成：{}ms，{} 个最终片段", elapsedMs, result.size());
    }

    /**
     * distinct数量。
     *
     * @param arr 数组，不允许为 null
     * @return 结果数值
     */
    private int distinctCount(String[] arr) {
        return (int) java.util.Arrays.stream(arr).distinct().count();
    }
}
