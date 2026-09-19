package com.chua.common.support.ai.agent;

import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.mcp.McpManager;
import com.chua.common.support.ai.memory.MemoryConfig;
import com.chua.common.support.ai.skill.SkillManager;

import java.util.List;

/**
 * ImageDefinition 扩展 AgentDefinition，增加图像生成能力。
 *
 * <p>子 Agent 使用此类时，会自动携带图像生成客户端和模型信息，
 * AgentScopeAgent 内部会将其注册为支持生图的 Model，而不是普通 ChatClient。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ImageDefinition extends AgentDefinition {

    /**
     * 图像生成模型名称，如 dall-e-3、agnes-image-2.0-flash
     */
    private final String imageModel;

    /** 图像生成客户端 */
    private final ImageClient imageClient;

    /**
    * 创建 ImageDefinition 实例
    * @param id id
    * @param name name
    * @param description description
    * @param role role
    * @param instruction instruction
    * @param planning planning
    * @param mcp mcp
    * @param leader leader
    * @param agents agents
    * @param mcpManager mcpManager
    * @param skillManager skillManager
    * @param memory memory
    * @param memoryConfig memoryConfig
    * @param retryConfig retryConfig
    * @param maxToolIterations maxToolIterations
    * @param compressionConfig compressionConfig
    * @param planMaxTask planMaxTask
    * @param debugHook debugHook
    * @param planHook planHook
    * @param systemPrompt systemPrompt
    * @param imageModel imageModel
    * @param imageClient imageClient
    */
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

    /**
     * 获取ImageModel
     * @return 结果字符串
     */
    public String getImageModel() {
        return imageModel;
    }

    /**
     * 获取ImageClient
     * @return Image客户端 对象
     */
    public ImageClient getImageClient() {
        return imageClient;
    }

    /**
     * 快速构建 ImageDefinition
     * @param base 方法入参 base
     * @param imageModel image模型，不允许为 null
     * @param imageClient image客户端，不允许为 null
     * @return ImageDefinition 对象
     */
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
