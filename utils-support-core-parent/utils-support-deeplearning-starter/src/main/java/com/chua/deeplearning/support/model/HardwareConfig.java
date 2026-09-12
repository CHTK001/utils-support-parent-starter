package com.chua.deeplearning.support.model;

import lombok.Builder;

/**
 * 深度学习模型硬件配置。
 *
 * <p>描述一个模型在指定设备（CPU/GPU）上的运行要求，用于在模型注册时标注
 * 硬件上限（如所需最低显存），配合 {@code auto} 设备策略由运行时按本机配置选择推荐模型：</p>
 * <ul>
 *   <li>{@link #recommended()} 标记该模型是否作为对应能力类型的推荐模型；
 *       同一能力类型可同时推荐多个（由当前服务器硬件配置决定最终选哪个）；</li>
 *   <li>{@link #minVramMb()} 表示 GPU 运行时所需的最低显存，用于判断当前服务器
 *       GPU 是否支持该模型上限；CPU 运行时直接取第一个推荐配置即可。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Builder
public class HardwareConfig {

    /**
     * 设备类型：cpu / gpu（缺省 auto，由运行时按本机探测决定）
     */
    @Builder.Default
    private final String device = "auto"; // device

    /**
     * GPU 运行所需最低显存（MB）。用于判断服务器 GPU 显存上限是否支持该模型。
     */
    private final long minVramMb;

    /**
     * 是否推荐。同一能力类型可同时推荐多个，运行时按服务器配置从推荐列表中挑选。
     */
    @Builder.Default
    private final boolean recommended = false; // recommended

    /**
      * 便于日志/展示的描述信息，可为 空
     */
    private final String description;

    /**
     * 设备类型。
     *
     * @return cpu / gpu / auto
     */
    public String device() {
        return device;
    }

    /**
     * GPU 运行所需最低显存（MB）。
     *
     * @return 显存大小
     */
    public long minVramMb() {
        return minVramMb;
    }

    /**
     * 是否作为对应能力类型的推荐模型。
     *
     * @return true 表示推荐
     */
    public boolean recommended() {
        return recommended;
    }

    /**
     * 便于日志/展示的描述信息。
     *
     * @return 描述信息，可为 空
     */
    public String description() {
        return description;
    }

    /**
     * 判断该配置是否面向 GPU 运行。
     *
     * @return true 表示面向 GPU
     */
    public boolean isGpu() {
        return "gpu".equalsIgnoreCase(device);
    }

    /**
     * 判断该配置是否可用（无 GPU 显存要求时总是可用）。
     *
     * @return true 表示可用
     */
    public boolean isUsable() {
        return minVramMb <= 0;
    }
}