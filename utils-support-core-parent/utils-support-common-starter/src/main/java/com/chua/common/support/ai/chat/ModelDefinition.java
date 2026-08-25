package com.chua.common.support.ai.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI 模型定义。
 * <p>
 * 描述一个 AI 模型的基本信息，包括 ID、名称、提供商、描述和能力列表。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelDefinition {

    /**
     * 模型 ID
     */
    private String id;

    /**
     * 模型名称
     */
    private String name;

    /**
     * 模型提供商
     */
    private String provider;

    /**
     * 模型描述
     */
    private String description;

    /**
     * 模型能力列表
     */
    private List<String> capabilities;

    /**
     * 模型文件远程下载地址（本地找不到时自动下载）
     */
    private String downloadUrl;

    /**
     * 下载文件是否为压缩包（zip）
     */
    private boolean compress;

    /**
     * 压缩包内目标文件名（compress=true 时生效）
     */
    private String downloadFileName;

    /**
     * 输入单价（每 Token）
     */
    private BigDecimal inputUnitPrice;

    /**
     * 输出单价（每 Token）
     */
    private BigDecimal outputUnitPrice;

    /**
     * 货币单位
     */
    @Builder.Default
    /** Currency */
    private String currency = "USD";

    /**
     * 智能指数（如 Artificial Analysis Intelligence Index）
     */
    private BigDecimal intelligenceIndex;

    /**
     * 输出速度（Token/秒，中位数）
     */
    private BigDecimal outputSpeedTokensPerSecond;

    /**
     * 延迟（秒，首 Token 中位耗时）
     */
    private BigDecimal latencyFirstTokenSeconds;

    /**
     * 图标地址
     */
    private String iconUrl;
}
