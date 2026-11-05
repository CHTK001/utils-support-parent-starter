package com.chua.common.support.ai.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 用户对 {@link AgentQuestion} 的回答。
 *
 * <p>一个提问可能包含多个问题，因此答案按 {@code questionId} 分组回传；
 * {@code answers} 保留用户选择/输入的全部文本，顺序即用户选择顺序。
 *
 * @author CH
 * @since 4.0.0.51
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentQuestionAnswer implements Serializable {

    /**
     * 序列化版本号
     */
    private static final long serialVersionUID = 1L;

    /**
     * 对应的问题标识
     */
    private String questionId;

    /**
     * 用户答案；多选时可能有多个
     */
    private List<String> answers;
}
