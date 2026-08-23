package com.chua.common.support.ai.chat.protocol;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * AI 访问令牌数据模型。
 *
 * <p>包含令牌值、分组、过期时间等元信息。
 * token 分组用于控制不同令牌可访问的模型分组。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class AiToken {

    /** 令牌值（如 sk-xxx） */
    /**
     * 令牌
     */
    private String token;

    /** 令牌分组（如 default、vip、admin），用于路由到对应的模型组 */
    /**
     * 用户组
     */
    private String group;

    /** 过期时间，null 表示永不过期 */
    private Date expireTime;

    /** 是否启用 */
    @Builder.Default
    /**
     * 是否启用
     */
    private boolean enabled = true;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    @Builder.Default
    /** Create时间 */
    private Date createTime = new Date();

    /**
     * 令牌是否有效。
     *
     * @return true 有效，false 已过期或已禁用
     */
    public boolean isValid() {
        if (!enabled) {
            return false;
        }
        if (expireTime != null && expireTime.before(new Date())) {
            return false;
        }
        return true;
    }
}
