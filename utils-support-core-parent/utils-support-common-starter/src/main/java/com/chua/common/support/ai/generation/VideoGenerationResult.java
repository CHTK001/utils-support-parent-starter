package com.chua.common.support.ai.generation;

/**
* 视频生成结果。
*
* <p>统一各服务商的视频生成返回结果。
*
* @param videos 生成的视频列表
* @param prompt 生成提示词
* @author CH
* @since 2026/08/11
 * @return 结果值
 */
public record VideoGenerationResult(java.util.List<GeneratedVideo> videos, String prompt) {

    /**
    * 单个生成的视频。
    *
    * @param videoUrl 视频播放 URL
    * @param coverUrl 封面图 URL
    * @param width    宽度
    * @param height   高度
    * @param duration 时长（秒）
    */
    public record GeneratedVideo(String videoUrl, String coverUrl,
                                 int width, int height, double duration) {
    }
}
