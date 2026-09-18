package com.chua.wechat.support.bot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.chua.common.support.ai.bot.BotInboundMessage;
import com.chua.common.support.ai.bot.BotMessageListener;
import com.chua.common.support.ai.bot.BotSendResult;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.utils.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * 个人微信 AI 自动回复处理器，接到 {@link WechatPersonalBotClient#addMessageListener} 上即可。
 *
 * <p>回复在单个后台线程串行执行：回调线程立刻返回，避免 bridge 超时重推；
 * 串行也天然限制了并发，配合 {@link WechatReplyPolicy} 的间隔与频控使用。</p>
 *
 * <p>每个会话保留有限轮历史喂给模型，超出后按最旧的丢弃。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class WechatAutoReplyHandler implements BotMessageListener, AutoCloseable {

    /** 参与上下文的历史消息条数上限（按会话）*/
    private static final int DEFAULT_HISTORY_SIZE = 12;

    /** 保留会话数上限 */
    private static final int MAX_CONVERSATIONS = 256;

    /** 单条微信文本上限，超出拆分为多条 */
    private static final int DEFAULT_MAX_REPLY_LENGTH = 1000;

    /** 发送通道 */
    private final WechatPersonalBotClient botClient;

    /** 模型客户端 */
    private final ChatClient chatClient;

    /** 风控守卫 */
    private final WechatReplyPolicy policy;

    /** 人设提示词 */
    private volatile String systemPrompt
            = "你是微信里的助手，回答简短、口语化，不要用 markdown 标记。";

    /** 历史轮数上限 */
    private final int historySize;

    /** 单条回复长度上限 */
    private final int maxReplyLength;

    /** 会话历史，LRU 淘汰 */
    private final Map<String, Deque<ChatMessage>> histories = Collections.synchronizedMap(
            new LinkedHashMap<String, Deque<ChatMessage>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Deque<ChatMessage>> eldest) {
                    return size() > MAX_CONVERSATIONS;
                }
            });

    /** 串行回复线程 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "wechat-auto-reply");
        thread.setDaemon(true);
        return thread;
    });

    public WechatAutoReplyHandler(WechatPersonalBotClient botClient, ChatClient chatClient) {
        this(botClient, chatClient, new WechatReplyPolicy(), DEFAULT_HISTORY_SIZE,
                DEFAULT_MAX_REPLY_LENGTH);
    }

    public WechatAutoReplyHandler(WechatPersonalBotClient botClient, ChatClient chatClient,
            WechatReplyPolicy policy) {
        this(botClient, chatClient, policy, DEFAULT_HISTORY_SIZE, DEFAULT_MAX_REPLY_LENGTH);
    }

    /**
     * 创建自动回复处理器。
     *
     * @param botClient      发送通道
     * @param chatClient     模型客户端，串行使用，不能与其他线程共享并发调用
     * @param policy         风控守卫
     * @param historySize    每个会话保留的历史消息条数
     * @param maxReplyLength 单条消息长度上限，超出拆分为多条
     */
    public WechatAutoReplyHandler(WechatPersonalBotClient botClient, ChatClient chatClient,
            WechatReplyPolicy policy, int historySize, int maxReplyLength) {
        this.botClient = botClient;
        this.chatClient = chatClient;
        this.policy = policy;
        this.historySize = historySize;
        this.maxReplyLength = maxReplyLength;
    }

    /**
     * 设置人设提示词。
     *
     * @param systemPrompt 提示词
     * @return this
     */
    public WechatAutoReplyHandler systemPrompt(String systemPrompt) {
        if (StringUtils.isNotBlank(systemPrompt)) {
            this.systemPrompt = systemPrompt;
        }
        return this;
    }

    @Override
    public void onMessage(BotInboundMessage message) {
        if (message.getType() != BotInboundMessage.Type.TEXT) {
            return;
        }
        String denyReason = policy.denyReason(message);
        if (denyReason != null) {
            log.debug("[WechatPersonal] 跳过回复, reason={}, from={}", denyReason,
                    message.getFromUser());
            return;
        }
        executor.execute(() -> reply(message));
    }

    /**
     * 关闭回复线程，最多等待 5 秒处理完在途回复。
     */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void reply(BotInboundMessage message) {
        String conversation = conversationOf(message);
        try {
            sleepQuietly(policy.nextDelayMillis());
            List<ChatMessage> history = historyOf(conversation);
            String answer = chatClient.newChat()
                    .system(systemPrompt)
                    .history(history)
                    .chatSync(message.getContent());
            if (StringUtils.isBlank(answer)) {
                return;
            }
            remember(conversation, message.getContent(), answer);
            for (String part : split(answer, maxReplyLength)) {
                BotSendResult result = botClient.sendText(conversation, part);
                if (!result.isSuccess()) {
                    log.warn("[WechatPersonal] 回复失败 to={} err={}", conversation,
                            result.getErrorMessage());
                    return;
                }
            }
            policy.markReplied(conversation);
        } catch (Exception e) {
            log.error("[WechatPersonal] 自动回复异常 to={}: {}", conversation, e.getMessage(), e);
        }
    }

    /**
     * 会话标识：群聊回到群，单聊回到对方，保证回复落到正确的窗口。
     */
    private static String conversationOf(BotInboundMessage message) {
        return StringUtils.isNotBlank(message.getChatId())
                ? message.getChatId() : message.getFromUser();
    }

    private List<ChatMessage> historyOf(String conversation) {
        Deque<ChatMessage> history = histories.get(conversation);
        if (history == null) {
            return new ArrayList<>();
        }
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    private void remember(String conversation, String question, String answer) {
        Deque<ChatMessage> history = histories.computeIfAbsent(conversation,
                key -> new ArrayDeque<>());
        synchronized (history) {
            history.addLast(ChatMessage.builder().role("user").content(question).build());
            history.addLast(ChatMessage.builder().role("assistant").content(answer).build());
            while (history.size() > historySize) {
                history.removeFirst();
            }
        }
    }

    /**
     * 按长度切分长回复，优先在换行与句号处断开。
     */
    static List<String> split(String text, int maxLength) {
        List<String> parts = new ArrayList<>();
        String remaining = text;
        while (remaining.length() > maxLength) {
            int cut = breakPoint(remaining, maxLength);
            parts.add(remaining.substring(0, cut).trim());
            remaining = remaining.substring(cut).stripLeading();
        }
        if (!remaining.isBlank()) {
            parts.add(remaining);
        }
        return parts;
    }

    private static int breakPoint(String text, int maxLength) {
        int limit = Math.min(maxLength, text.length() - 1);
        for (int i = limit - 1; i > limit / 2; i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '.') {
                return i + 1;
            }
        }
        return maxLength;
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
