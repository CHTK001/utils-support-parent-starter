package com.chua.qiniu.support;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/**
* 七牛云对话客户端（桩实现）
*
* <p>七牛云为对象存储服务商，暂未提供 AI 大模型对话能力。
* 此实现为桩（Stub），调用时返回固定的提示信息。
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi({"qiniu"})
public class QiniuChatClient implements ChatClient {

    /**
    * 七牛云暂不支持 AI 对话的提示消息
     */
    private static final String NOT_SUPPORTED_MSG = "七牛云暂不支持 AI 对话";

    /**
    * 是否启用深度思考
     */
    private boolean thinking;

    /**
    * 深度思考力度
     */
    private String thinkingEffort;

    /**
    * 是否启用智能搜索
     */
    private boolean smartSearch;

    /**
    * 技能管理器
     */
    private SkillManager skillManager;

    @Override
    /** 模型 */
    public ChatClient model(String model) {
        return this;
    }

    @Override
    /** Temperature */
    public ChatClient temperature(double temperature) {
        return this;
    }

    @Override
    /** 最大值令牌 */
    public ChatClient maxTokens(int maxTokens) {
        return this;
    }

    @Override
    /** 系统 */
    public ChatClient system(String system) {
        return this;
    }

    @Override
    /** Thinking */
    public ChatClient thinking(boolean thinking) {
        this.thinking = thinking;
        return this;
    }

    @Override
    /** thinkingeffort */
    public ChatClient thinkingEffort(String effort) {
        this.thinkingEffort = effort;
        return this;
    }

    @Override
    /** Smart搜索 */
    public ChatClient smartSearch(boolean smartSearch) {
        this.smartSearch = smartSearch;
        return this;
    }

    @Override
    /** Skill */
    public ChatClient skill(SkillManager skillManager) {
        this.skillManager = skillManager;
        return this;
    }

    @Override
    /** 对话同步 */
    public String chatSync(String prompt) {
        log.warn("七牛云暂不支持 AI 对话，调用 chatSync 返回固定提示");
        return NOT_SUPPORTED_MSG;
    }

    @Override
    /** 对话 */
    public void chat(String prompt, Consumer<ChatResponse> consumer) {
        chat(prompt, consumer, () -> {
        }, e -> {
        });
    }

    @Override
    /**
    * 对话
    * @param prompt 提示符
    * @param consumer consumer
    * @param onComplete on完成
    * @param onError on错误
     */
    public void chat(String prompt, Consumer<ChatResponse> consumer,
                     Runnable onComplete, Consumer<Throwable> onError) {
        try {
            long startTime = System.currentTimeMillis();
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.START)
                    .build());

            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.STREAMING)
                    .content(NOT_SUPPORTED_MSG)
                    .build());

            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.STOP)
                    .content(NOT_SUPPORTED_MSG)
                    .fullContent(NOT_SUPPORTED_MSG)
                    .usage(AiUsage.builder()
                            .model("qiniu")
                            .provider("qiniu")
                            .startTime(startTime)
                            .durationMillis(System.currentTimeMillis() - startTime)
                            .build())
                    .build());

            onComplete.run();
        } catch (Exception e) {
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR)
                    .errorMessage(e.getMessage())
                    .build());
            onError.accept(e);
        }
    }
}
