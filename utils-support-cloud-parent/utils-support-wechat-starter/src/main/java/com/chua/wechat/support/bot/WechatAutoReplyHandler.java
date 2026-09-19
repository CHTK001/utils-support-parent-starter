package com.chua.wechat.support.bot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
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
 * <p>回复在单个后台线程串行执行：回调线程立刻返回，避免 Hook 服务超时重推；
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

    /**
     * 保留会话数上限
    */
    private static final int MAX_CONVERSATIONS = 256;

    /**
     * 单条微信文本上限，超出拆分为多条
    */
    private static final int DEFAULT_MAX_REPLY_LENGTH = 1000;

    /**
     * 回复线程数，固定为 1 以串行化发送
    */
    private static final int REPLY_THREAD_COUNT = 1;

    /**
     * 回复队列容量，堆积超过即丢弃新回复
    */
    private static final int QUEUE_CAPACITY = 64;

    /**
     * 空闲线程存活秒数
    */
    private static final long KEEP_ALIVE_SECONDS = 0L;

    /**
     * 回复线程名，便于异常回溯
    */
    private static final String REPLY_THREAD_NAME = "wechat-auto-reply";

    /**
     * 关闭时等待在途回复的秒数
    */
    private static final long AWAIT_TERMINATION_SECONDS = 5L;

    /**
     * 对话角色：用户
    */
    private static final String ROLE_USER = "user";

    /**
     * 对话角色：助手
    */
    private static final String ROLE_ASSISTANT = "assistant";

    /**
     * 发送通道
    */
    private final WechatPersonalBotClient botClient;

    /**
     * 模型客户端
    */
    private final ChatClient chatClient;

    /**
     * 风控守卫
    */
    private final WechatReplyPolicy policy;

    /**
     * 人设提示词
    */
    private volatile String systemPrompt
            = "你是微信里的助手，回答简短、口语化，不要用 markdown 标记。";

    /**
     * 历史轮数上限
    */
    private final int historySize;

    /**
     * 单条回复长度上限
    */
    private final int maxReplyLength;

    /**
     * 会话历史，LRU 淘汰
    */
    private final Map<String, Deque<ChatMessage>> histories = Collections.synchronizedMap(
            new LinkedHashMap<String, Deque<ChatMessage>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Deque<ChatMessage>> eldest) {
                    return size() > MAX_CONVERSATIONS;
                }
            });

    /**
     * 回复线程池：单线程串行，队列有界，满了直接丢消息而不是堆积
    */
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            REPLY_THREAD_COUNT, REPLY_THREAD_COUNT,
            KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, REPLY_THREAD_NAME);
                thread.setDaemon(true);
                return thread;
            },
            (runnable, pool) -> log.warn("[WechatPersonal] 回复队列已满，丢弃本次回复"));

    /**
     * 使用默认风控创建处理器。
     *
     * @param botClient  发送通道
     * @param chatClient 模型客户端
     */
    public WechatAutoReplyHandler(WechatPersonalBotClient botClient, ChatClient chatClient) {
        this(botClient, chatClient, new WechatReplyPolicy(), DEFAULT_HISTORY_SIZE,
                DEFAULT_MAX_REPLY_LENGTH);
    }

    /**
     * 使用指定风控创建处理器。
     *
     * @param botClient  发送通道
     * @param chatClient 模型客户端
     * @param policy     风控守卫
     */
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
        WechatReplyPolicy.DenyReason denyReason = policy.denyReason(message);
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
            if (!executor.awaitTermination(AWAIT_TERMINATION_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 调用模型并把回复发出，运行在回复线程上，任何异常都在此消化，不外溢到监听器。
     *
     * @param message 入站消息，不允许为 null
     */
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
     *
     * @param message 入站消息，不允许为 null
     * @return 群聊返回群 wxid，单聊返回对方 wxid
     */
    private static String conversationOf(BotInboundMessage message) {
        return StringUtils.isNotBlank(message.getChatId())
                ? message.getChatId() : message.getFromUser();
    }

    /**
     * 取出某个会话的历史消息副本。
     *
     * @param conversation 会话 wxid
     * @return 历史消息列表，无记录时返回空列表
     */
    private List<ChatMessage> historyOf(String conversation) {
        Deque<ChatMessage> history = histories.get(conversation);
        if (history == null) {
            return new ArrayList<>();
        }
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    /**
     * 记录一轮问答，超出 {@code historySize} 的最旧消息先丢弃。
     *
     * @param conversation 会话 wxid
     * @param question 本轮提问
     * @param answer 本轮回答
     */
    private void remember(String conversation, String question, String answer) {
        Deque<ChatMessage> history = histories.computeIfAbsent(conversation,
                key -> new ArrayDeque<>());
        synchronized (history) {
            history.addLast(ChatMessage.builder().role(ROLE_USER).content(question).build());
            history.addLast(ChatMessage.builder().role(ROLE_ASSISTANT).content(answer).build());
            while (history.size() > historySize) {
                history.removeFirst();
            }
        }
    }

    /**
     * 按长度切分长回复，优先在换行与句号处断开。
     *
     * @param text 待切分文本，长度不超过 maxLength 时原样返回
     * @param maxLength 单条消息长度上限
     * @return 切分后的片段列表，全空白文本返回空列表
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

    /**
     * 回溯寻找自然断句处，只在后半段查找，避免切出过短的片段。
     *
     * @param text 待切分文本，不允许为 null
     * @param maxLength 单条消息长度上限
     * @return 断点下标，未命中句读时返回 maxLength
     */
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

    /**
     * 静默等待，线程被中断时恢复中断标记并立即返回。
     *
     * @param millis 等待毫秒数，非正数时直接返回
     */
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
