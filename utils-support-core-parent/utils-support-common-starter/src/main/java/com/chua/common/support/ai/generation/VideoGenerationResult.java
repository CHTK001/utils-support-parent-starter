package com.chua.common.support.ai.generation;

import java.util.List;
import java.util.Objects;

/**
 * 视频生成结果。
 *
 * <p>统一各服务商的视频生成返回结果。
 *
 * @param videos 生成的视频列表
 * @param prompt 生成提示词
 * @author CH
 * @since 2026/08/11
 */
public record VideoGenerationResult(List<GeneratedVideo> videos, String prompt) {

    /**
     * 规范构造器：对视频列表做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。
     * 全部构造点传入的列表均非空且元素非空，
     * 因此使用 {@link List#copyOf} 拒绝 null 列表与 null 元素。</p>
     *
     * @param videos 生成的视频列表
     * @param prompt 生成提示词
     */
    public VideoGenerationResult {
        videos = List.copyOf(Objects.requireNonNull(videos, "videos 不能为 null"));
    }

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
