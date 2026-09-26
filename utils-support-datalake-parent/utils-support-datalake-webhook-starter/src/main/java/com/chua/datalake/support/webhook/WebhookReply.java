package com.chua.datalake.support.webhook;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Webhook 应答体：外部系统据此判断数据是否真正被管线消化。
 *
 * <p>只用 {@code ok=true} 冒充成功会让推送方在数据已经丢失时仍继续推送，
 * 因此应答固定携带：本次受理条数、本次失败条数、累计条数与最后一条的管线状态。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebhookReply {

    /**
     * 本次请求是否全部受理成功
     */
    private boolean ok;

    /**
     * 结果分类：accepted / partial_failed / sink_failed / pipeline_not_found / rejected / degraded
     */
    private String status;

    /**
     * 拒绝或失败原因，成功时为 null
     */
    private String error;

    /**
     * 最后一条数据的管线状态（PipelineState 名称），未进入管线时为 null
     */
    private String state;

    /**
     * 本次接收的数据条数
     */
    private int received;

    /**
     * 本次未被管线消化的数据条数
     */
    private int failed;

    /**
     * 累计接收的数据条数
     */
    private Long receivedTotal;

    /**
     * 累计未被管线消化的数据条数
     */
    private Long failedTotal;
}
