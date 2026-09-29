package com.chua.common.support.ai.bot;

import lombok.Builder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bot 群组信息。
 *
 * <p>跨 IM 渠道统一描述一个群/会话：渠道自带的列表接口（飞书 {@code listGroups}）只给出
 * 群标识与群名；钉钉、QQ 等没有列表接口，只能在收到入站群消息时把观察到的成员累积起来，
 * 因此同一个记录里「标识/名称」与「成员/扩展字段」的填充度并不一致，下游必须容忍缺省。</p>
 *
 * @param groupId     群组 ID，即群会话的唯一标识，不允许为 {@code null}（规范构造器强制校验）。取值来源为各渠道
 *                    的会话 ID：飞书为 {@code chat_id}，钉钉、QQ 为入站消息携带的 {@code chatId}
 * @param groupName   群组名称，允许为 {@code null}。仅飞书 {@code listGroups} 会填（取 {@code chat.getName()}），
 *                    钉钉、QQ 的观察态记录只填标识与成员，名称留空
 * @param memberIds   成员 ID 列表，允许为 {@code null}，非空时构造器会做不可变拷贝。内容是「已观察到的成员」而非
 *                    「群的全部成员」：钉钉、QQ 按入站消息逐个累积，飞书列表接口不返回成员故为 {@code null}；
 *                    元素不允许为 {@code null}
 * @param memberCount 成员数量，取已观察到的成员数（如 {@code members.size()}），原生 {@code int} 不可为 {@code null}，
 *                    未观察到成员时为 0；因观察来源有限，该值可能远小于真实群人数，也可能与 {@code memberIds}
 *                    不一致（{@code memberIds} 为 {@code null} 时成员数未知，按 0 记）
 * @param extra       扩展字段，允许为 {@code null}，非空时构造器会做不可变拷贝。用于承载渠道私有的额外元数据
 *                    （如群类型、成员角色等），当前各渠道调用点均未赋值，预留用途
 * @author CH
 * @since 2026/07/18
 */
@Builder
public record BotGroupInfo(
        /**
         * 群组 ID
         */
        String groupId,
        /**
         * 群组名称
         */
        String groupName,
        /**
         * 成员 ID 列表
         */
        List<String> memberIds,
        /**
         * 成员数量
         */
        int memberCount,
        /**
         * 扩展字段
         */
        Map<String, Object> extra
) {

    /**
     * 规范构造器：对集合组件做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。
     * {@code groupName} 与 {@code extra} 在现有调用点均不赋值（业务上就是缺省 null），
     * 故保留 null 语义，只在非 null 时做不可变拷贝。</p>
     *
     * @param groupId     群组 ID，不允许为 null
     * @param groupName   群组名称，允许为 null
     * @param memberIds   成员 ID 列表，允许为 null
     * @param memberCount 成员数量
     * @param extra       扩展字段，允许为 null
     */
    public BotGroupInfo {
        groupId = Objects.requireNonNull(groupId, "groupId 不能为 null");
        memberIds = memberIds == null ? null : List.copyOf(memberIds);
        extra = extra == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(extra));
    }
}
