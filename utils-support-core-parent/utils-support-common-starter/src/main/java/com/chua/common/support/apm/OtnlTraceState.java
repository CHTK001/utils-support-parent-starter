package com.chua.common.support.apm;

/**
 * 链路查询的状态过滤。
 * <p>与 SkyWalking UI 的「Trace State」下拉一致：全部 / 成功 / 失败。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public enum OtnlTraceState {

    /**
     * 全部
     */
    ALL,

    /**
     * 成功
     */
    SUCCESS,

    /**
     * 失败
     */
    ERROR
}
