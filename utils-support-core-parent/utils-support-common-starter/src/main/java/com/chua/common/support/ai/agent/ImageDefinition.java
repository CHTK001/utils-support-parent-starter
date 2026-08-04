package com.chua.common.support.ai.agent;

import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.mcp.McpManager;
import com.chua.common.support.ai.memory.MemoryConfig;
import com.chua.common.support.ai.skill.SkillManager;

import java.util.List;
import org.jspecify.annotations.NullUnmarked;

/**
 * ImageDefinition 扩展 AgentDefinition，增加图像生成能力。
 *
 * <p>子 Agent 使用此类时，会自动携带图像生成客户端和模型信息，
 * AgentScopeAgent 内部会将其注册为支持生图的 Model，而不是普通 ChatClient。
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
public class ImageDefinition extends AgentDefinition {

    /** 图像生成模型名称，如 dall-e-3、agnes-image-2.0-flash */
    private final String imageModel;

    /** 图像生成客户端 */
    private final ImageClient imageClient;

    public ImageDefinition(String id, String name, String description, String role,
                           String instruction, boolean planning, boolean mcp, boolean leader,
                           List<AgentDefinition> agents,
                           McpManager mcpManager, SkillManager skillManager,
                           boolean memory, MemoryConfig memoryConfig,
                           AgentRetryConfig retryConfig, int maxToolIterations,
                           AgentCompressionConfig compressionConfig,
                           int planMaxTask, AgentDebugHook debugHook, AgentPlanHook planHook,
                           String systemPrompt,
                           String imageModel, ImageClient imageClient) {
        super(id, name, description, role, instruction, planning, mcp, leader, agents,
              mcpManager, skillManager, memory, memoryConfig, retryConfig, maxToolIterations,
              compressionConfig, planMaxTask, debugHook, planHook, systemPrompt);
        this.imageModel = imageModel;
        this.imageClient = imageClient;
    }

    public String getImageModel() {
        return imageModel;
    }

    public ImageClient getImageClient() {
        return imageClient;
    }

    /** 快速构建 ImageDefinition */
    public static ImageDefinition wrap(AgentDefinition base, String imageModel, ImageClient imageClient) {
        return new ImageDefinition(
                base.getId(), base.getName(), base.getDescription(), base.getRole(),
                base.getInstruction(), base.isPlanning(), base.isMcp(), base.isLeader(),
                base.getAgents(), base.getMcpManager(), base.getSkillManager(),
                base.isMemory(), base.getMemoryConfig(), base.getRetryConfig(),
                base.getMaxToolIterations(), base.getCompressionConfig(),
                base.getPlanMaxTask(), base.getDebugHook(), base.getPlanHook(),
                base.getSystemPrompt(),
                imageModel, imageClient);
    }
}
