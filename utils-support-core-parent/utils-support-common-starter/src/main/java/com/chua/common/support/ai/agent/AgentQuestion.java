package com.chua.common.support.ai.agent;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Agent 运行期间向用户提出的一个问题。
 *
 * <p>与 {@link ConfirmationItem} 的区别：确认项是「批准 / 拒绝」的二元决策，
 * 而本类型承载「选择题 + 自由输入」的提问（对应 opencode 的 {@code question.v2.asked}）。
 *
 * @author CH
 * @since 4.0.0.51
 */
@Data
@Builder
public class AgentQuestion implements Serializable {

    /**
     * 序列化版本号
     */
    private static final long serialVersionUID = 1L;

    /**
     * 问题标识（回传答案时原样带回）
     */
    private String id;

    /**
     * 问题分组标题，可为空
     */
    private String header;

    /**
     * 问题正文
     */
    private String question;

    /**
     * 可选项；为空表示纯自由输入
     */
    private List<String> options;

    /**
     * 是否允许多选
     */
    private boolean multiple;

    /**
     * 是否允许自由输入（选项之外的自定义答案）
     */
    private boolean custom;
}
