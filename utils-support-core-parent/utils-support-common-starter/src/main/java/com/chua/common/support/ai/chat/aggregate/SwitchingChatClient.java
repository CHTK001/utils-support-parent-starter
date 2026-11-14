// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.ai.chat.aggregate;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.chat.ChatTool;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.chat.aggregate.strategy.RouterStrategy;
import com.chua.common.support.ai.skill.SkillManager;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 切换型对话客户端基类：管理多个成员客户端，并在每次调用时挑一个转发。
 *
 * <p>本类只负责三件事，全部在内部完成，调用方看不到成员差异：</p>
 * <ol>
 *   <li><b>成员表</b>：{@link #addMember} 登记成员（带标签与权重），支持运行时热替换；</li>
 *   <li><b>配置传播</b>：{@code model / system / history / tools / thinkingEffort …}
 *       一律广播给全部成员，调用方拿到的就是「一个普通的 ChatClient」；</li>
 *   <li><b>选谁</b>：子类覆写 {@link #select}（与 {@link #onSuccess}/{@link #onFailure}
 *       一起构成策略）；本类按「选中 → 执行 → 记结果」的顺序跑完，失败时换下一个，
 *       直到选出成功或成员全部试过。</li>
 * </ol>
 *
 * <p>已提供的实现：</p>
 * <ul>
 *   <li>{@link MultiKeyChatClient}：同一供应商的多个 API Key（按顺序 / 轮换 / 最少使用）；</li>
 *   <li>{@link RouterGroupChatClient}：路由组供应商的成员模型（按智能 / 顺序 / 用完再换 …）。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public abstract class SwitchingChatClient implements ChatClient {

    /**
     * 成员客户端列表（并发安全，支持热更新）
     */
    private final CopyOnWriteArrayList<SwitchingMember> members = new CopyOnWriteArrayList<>();

    /**
     * 单次请求最多尝试的成员数；默认取成员总数（每个成员都试一遍）
     */
    private int maxAttempts = -1;

    /**
     * 客户端是否已关闭
     */
    private volatile boolean closed = false;

    /**
     * 成员：标签 + 权重 + 真实客户端 + 累计使用次数
     *
     * @param label  成员标签（Key 摘要 / 供应商:模型），用于日志与冷却记录
     * @param weight 权重（为 0 表示不参与新的分配，仍可接管进行中的请求）
     * @param client 真实客户端
     * @param used   累计被选中次数
     */
    public record SwitchingMember(String label, int weight, ChatClient client, AtomicInteger used) {

        /**
         * 创建一个成员
         *
         * @param label  成员标签
         * @param weight 权重
         * @param client 真实客户端
         * @return 成员
         */
        public static SwitchingMember of(String label, int weight, ChatClient client) {
            return new SwitchingMember(label, weight, client, new AtomicInteger());
        }
    }

    // ======================== 成员管理 ========================

    /**
     * 登记一个成员。
     *
     * @param label  成员标签（日志与冷却记录用）
     * @param weight 权重
     * @param client 真实客户端；为 null 时忽略
     * @return 当前实例，支持链式调用
     */
    public SwitchingChatClient addMember(String label, int weight, ChatClient client) {
        if (client == null) {
            log.warn("[Switching] 忽略空成员 label={}", label);
            return this;
        }
        members.add(SwitchingMember.of(label, weight, client));
        return this;
    }

    /**
     * 批量登记成员（从零开始重建成员表，供热更新使用）。
     *
     * @param list 成员列表
     * @return 当前实例，支持链式调用
     */
    protected SwitchingChatClient resetMembers(List<SwitchingMember> list) {
        members.clear();
        if (list != null) {
            members.addAll(list);
        }
        return this;
    }

    /**
     * 成员快照（只读）。
     *
     * @return 成员列表
     */
    public List<SwitchingMember> members() {
        return List.copyOf(members);
    }

    /**
     * 成员数量。
     *
     * @return 数量
     */
    public int memberCount() {
        return members.size();
    }

    /**
     * 是否没有任何成员。
     *
     * @return true 表示空
     */
    public boolean isEmpty() {
        return members.isEmpty();
    }

    /**
     * 设置单次请求最多尝试的成员数。
     *
     * @param maxAttempts 最大尝试数；小于等于 0 表示全部成员
     */
    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    /**
     * 本类名称（日志用）。
     *
     * @return 简短名称
     */
    protected String kind() {
        return getClass().getSimpleName();
    }

    // ======================== 策略钩子 ========================

    /**
     * 从候选里挑一个成员（不含已尝试过的）。
     *
     * @param candidates 本次仍可尝试的成员（已排除本次已试过的）
     * @param prompt     用户输入
     * @param attempt    第几次尝试（从 0 开始）
     * @return 选中的成员；返回 null 表示放弃本次请求
     */
    protected abstract SwitchingMember select(List<SwitchingMember> candidates, String prompt, int attempt);

    /**
     * 一次调用成功后回调（策略据此更新权重、用量、末位时间等）。
     *
     * @param member 成功成员
     * @param prompt 用户输入
     * @param costMs 本次耗时（毫秒）
     */
    protected void onSuccess(SwitchingMember member, String prompt, long costMs) {
    }

    /**
     * 一次调用失败后回调（策略据此记冷却、降级权重等）。
     *
     * @param member 失败成员
     * @param prompt 用户输入
     * @param error  异常
     */
    protected void onFailure(SwitchingMember member, String prompt, Throwable error) {
    }

    /**
     * 是否把这次失败当作「该成员暂时不可用」。
     *
     * <p>默认：401/403/429/余额/网络等一律算；参数错误（400、invalid_request）这类
     * 换谁都会失败的请求错误不算，避免把整组成员轮流拉黑。子类可覆写。</p>
     *
     * @param error 异常
     * @return true 表示该成员应暂时避开
     */
    protected boolean isMemberUnavailable(Throwable error) {
        String msg = error == null || error.getMessage() == null ? "" : error.getMessage().toLowerCase();
        return !(msg.contains("400") || msg.contains("invalid_request") || msg.contains("invalid parameter"));
    }

    /**
     * 按本类的口径过滤出本次可尝试的成员。
     *
     * @param all 全部成员
     * @return 可尝试成员
     */
    protected List<SwitchingMember> available(List<SwitchingMember> all) {
        List<SwitchingMember> result = new ArrayList<>(all.size());
        for (SwitchingMember m : all) {
            if (m.weight() > 0 && m.client() != null) {
                result.add(m);
            }
        }
        return result;
    }

    // ======================== ChatClient 接口 ========================

    /**
     * 同步对话：按策略挑成员，失败换下一个。
     *
     * @param prompt 用户输入
     * @return 响应文本
     */
    @Override
    public String chatSync(String prompt) {
        checkClosed();
        List<SwitchingMember> pending = new ArrayList<>(available(members()));
        if (pending.isEmpty()) {
            throw new IllegalStateException("[" + kind() + "] 没有任何可用成员");
        }
        int limit = maxAttempts > 0 ? Math.min(maxAttempts, pending.size()) : pending.size();
        Throwable last = null;
        for (int attempt = 0; attempt < limit; attempt++) {
            SwitchingMember picked = select(pending, prompt, attempt);
            if (picked == null) {
                break;
            }
            pending.remove(picked);
            picked.used().incrementAndGet();
            long start = System.currentTimeMillis();
            try {
                String text = picked.client().chatSync(prompt);
                onSuccess(picked, prompt, System.currentTimeMillis() - start);
                return text;
            } catch (Exception e) {
                last = e;
                if (isMemberUnavailable(e)) {
                    onFailure(picked, prompt, e);
                }
                log.warn("[{}] 成员 {} 调用失败（第 {} 次），尝试下一个: {}",
                        kind(), picked.label(), attempt + 1, e.getMessage());
            }
        }
        throw new IllegalStateException("[" + kind() + "] 全部成员调用失败", last);
    }

    /**
     * 流式对话：按策略挑成员；一旦有成员开始输出内容就不再切换。
     *
     * @param prompt   用户输入
     * @param consumer 响应回调
     */
    @Override
    public void chat(String prompt, Consumer<ChatResponse> consumer) {
        chat(prompt, consumer, () -> {
        }, e -> {
            throw new RuntimeException("对话请求失败: " + e.getMessage(), e);
        });
    }

    /**
     * 流式对话（带完成与错误回调）。
     *
     * @param prompt     用户输入
     * @param consumer   响应回调
     * @param onComplete 完成回调
     * @param onError    错误回调
     */
    @Override
    public void chat(String prompt, Consumer<ChatResponse> consumer,
                     Runnable onComplete, Consumer<Throwable> onError) {
        checkClosed();
        List<SwitchingMember> pending = new ArrayList<>(available(members()));
        if (pending.isEmpty()) {
            IllegalStateException empty = new IllegalStateException("[" + kind() + "] 没有任何可用成员");
            consumer.accept(ChatResponse.builder().state(ChatResponse.State.ERROR)
                    .errorMessage(empty.getMessage()).build());
            onError.accept(empty);
            return;
        }
        consumer.accept(ChatResponse.builder().state(ChatResponse.State.START).build());
        int limit = maxAttempts > 0 ? Math.min(maxAttempts, pending.size()) : pending.size();
        // 底层客户端会自己发带 usage 的 STOP；本层只在它没发时补一个，避免冲突计量
        boolean[] stopDelivered = new boolean[1];
        AiUsage[] streamedUsage = new AiUsage[1];
        Throwable last = null;
        for (int attempt = 0; attempt < limit; attempt++) {
            SwitchingMember picked = select(pending, prompt, attempt);
            if (picked == null) {
                break;
            }
            pending.remove(picked);
            picked.used().incrementAndGet();
            boolean[] started = new boolean[1];
            Consumer<ChatResponse> tracked = response -> {
                if (response == null) {
                    return;
                }
                if (response.getState() == ChatResponse.State.STREAMING) {
                    started[0] = true;
                }
                if (response.getState() == ChatResponse.State.STOP) {
                    stopDelivered[0] = true;
                    if (response.getUsage() != null) {
                        streamedUsage[0] = response.getUsage();
                    }
                }
                consumer.accept(response);
            };
            long start = System.currentTimeMillis();
            try {
                picked.client().chat(prompt, tracked);
                if (!stopDelivered[0]) {
                    consumer.accept(ChatResponse.builder()
                            .state(ChatResponse.State.STOP)
                            .usage(streamedUsage[0])
                            .build());
                }
                onSuccess(picked, prompt, System.currentTimeMillis() - start);
                onComplete.run();
                return;
            } catch (Exception e) {
                last = e;
                if (isMemberUnavailable(e)) {
                    onFailure(picked, prompt, e);
                }
                log.warn("[{}] 成员 {} 流式失败（第 {} 次）: {}", kind(), picked.label(), attempt + 1, e.getMessage());
                // 已经推给调用方的片段收不回来：不再切换，直接按失败收尾
                if (started[0] || stopDelivered[0]) {
                    break;
                }
            }
        }
        Throwable error = last != null ? last : new IllegalStateException("[" + kind() + "] 全部成员调用失败");
        consumer.accept(ChatResponse.builder().state(ChatResponse.State.ERROR)
                .errorMessage(error.getMessage()).build());
        onError.accept(error);
    }

    /**
     * 同步对话并返回完整响应（文本 + 本次用量）。
     *
     * @param prompt 用户输入
     * @return 响应
     */
    @Override
    public ChatSyncResponse chatSyncWithResponse(String prompt) {
        return ChatSyncResponse.builder().text(chatSync(prompt)).build();
    }

    /**
     * 全部成员可用的模型清单（去重保序）。
     *
     * @return 模型列表
     */
    @Override
    public List<ModelDefinition> models() {
        List<ModelDefinition> result = new ArrayList<>();
        for (SwitchingMember m : members) {
            try {
                List<ModelDefinition> list = m.client().models();
                if (list == null) {
                    continue;
                }
                for (ModelDefinition def : list) {
                    if (def == null) {
                        continue;
                    }
                    String id = def.getId();
                    boolean dup = id != null && result.stream().anyMatch(d -> id.equals(d.getId()));
                    if (!dup) {
                        result.add(def);
                    }
                }
            } catch (Exception e) {
                log.debug("[{}] 读取成员模型失败 {}: {}", kind(), m.label(), e.getMessage());
            }
        }
        return result;
    }

    /**
     * 代表成员（读密钥、地址、模型等单一值时使用）。
     *
     * @return 首个成员；无成员返回 null
     */
    protected ChatClient representative() {
        for (SwitchingMember m : members) {
            if (m.client() != null) {
                return m.client();
            }
        }
        return null;
    }

    @Override
    public ChatClientSetting getSetting() {
        ChatClient rep = representative();
        return rep == null ? null : rep.getSetting();
    }

    @Override
    public String getModel() {
        ChatClient rep = representative();
        return rep == null ? null : rep.getModel();
    }

    @Override
    public String getApiKey() {
        ChatClient rep = representative();
        return rep == null ? null : rep.getApiKey();
    }

    @Override
    public String getBaseUrl() {
        ChatClient rep = representative();
        return rep == null ? null : rep.getBaseUrl();
    }

    // ======================== 配置广播 ========================

    /**
     * 把一项请求级配置广播给全部成员。
     *
     * @param action 配置动作
     * @return 当前实例，支持链式调用
     */
    private SwitchingChatClient broadcast(java.util.function.Consumer<ChatClient> action) {
        for (SwitchingMember m : members) {
            try {
                action.accept(m.client());
            } catch (Exception e) {
                log.debug("[{}] 配置传播失败 {}: {}", kind(), m.label(), e.getMessage());
            }
        }
        return this;
    }

    @Override
    public ChatClient model(String model) {
        /*
         * 默认把模型名广播给每个成员 —— 多 Key 聚合（MultiKey）共用同一个模型，这是对的。
         * 但路由组的成员各自绑定了自己的模型：请求里的模型是「组的名字」，
         * 广播下去会把成员模型覆盖成组名，上游直接 400 invalid chat completions request
         * （2026-11-14 实测：路由组成员全 400，直连同一个成员模型正常）。
         * 需要「成员各用各的模型」的聚合重写 {@link #propagateModel()} 返回 false。
         */
        if (!propagateModel()) {
            return this;
        }
        return broadcast(c -> c.model(model));
    }

    /**
     * 请求级 {@code model(...)} 是否要广播给成员。
     *
     * <p>默认 true（多 Key 聚合共用一个模型）；成员各自绑定模型的聚合（路由组）应返回 false。</p>
     *
     * @return true 表示把模型名下发给成员
     */
    protected boolean propagateModel() {
        return true;
    }

    @Override
    public ChatClient system(String system) {
        return broadcast(c -> c.system(system));
    }

    @Override
    public ChatClient temperature(double temperature) {
        return broadcast(c -> c.temperature(temperature));
    }

    @Override
    public ChatClient maxTokens(int maxTokens) {
        return broadcast(c -> c.maxTokens(maxTokens));
    }

    @Override
    public ChatClient topP(Double topP) {
        return topP == null ? this : broadcast(c -> c.topP(topP));
    }

    @Override
    public ChatClient thinking(boolean thinking) {
        return broadcast(c -> c.thinking(thinking));
    }

    @Override
    public ChatClient thinkingEffort(String effort) {
        return effort == null || effort.isBlank() ? this : broadcast(c -> c.thinkingEffort(effort));
    }

    @Override
    public ChatClient smartSearch(boolean smartSearch) {
        return broadcast(c -> c.smartSearch(smartSearch));
    }

    @Override
    public ChatClient stream(boolean stream) {
        return broadcast(c -> c.stream(stream));
    }

    @Override
    public ChatClient addImage(String imageUrl) {
        return broadcast(c -> c.addImage(imageUrl));
    }

    @Override
    public ChatClient history(List<ChatMessage> messages) {
        return broadcast(c -> c.history(messages));
    }

    @Override
    public ChatClient tools(List<ChatTool> tools) {
        return tools == null || tools.isEmpty() ? this : broadcast(c -> c.tools(tools));
    }

    @Override
    public ChatClient tool(ChatTool tool) {
        return tool == null ? this : broadcast(c -> c.tool(tool));
    }

    @Override
    public ChatClient toolChoice(String toolChoice) {
        return toolChoice == null || toolChoice.isBlank() ? this : broadcast(c -> c.toolChoice(toolChoice));
    }

    @Override
    public ChatClient skill(SkillManager skillManager) {
        return skillManager == null ? this : broadcast(c -> c.skill(skillManager));
    }

    @Override
    public ChatClient skillPrompt(String block) {
        return block == null || block.isEmpty() ? this : broadcast(c -> c.skillPrompt(block));
    }

    @Override
    public ChatClient extraBody(Map<String, Object> extraBody) {
        return extraBody == null || extraBody.isEmpty() ? this : broadcast(c -> c.extraBody(extraBody));
    }

    // ======================== 生命周期 ========================

    /**
     * 关闭：标记关闭并关闭全部成员（成员为共享实例时由上层决定是否真的需要关闭）。
     */
    @Override
    public void close() {
        closed = true;
        for (SwitchingMember m : members) {
            try {
                m.client().close();
            } catch (Exception e) {
                log.debug("[{}] 关闭成员失败 {}: {}", kind(), m.label(), e.getMessage());
            }
        }
    }

    /**
     * 关闭校验。
     */
    protected void checkClosed() {
        if (closed) {
            throw new IllegalStateException("[" + kind() + "] 客户端已关闭");
        }
    }

    /**
     * 是否已关闭。
     *
     * @return true 表示已关闭
     */
    public boolean isClosed() {
        return closed;
    }
}
