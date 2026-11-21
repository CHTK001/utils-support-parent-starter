package com.chua.common.support.network.discovery;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;

/**
 * registry-hub / registry-client 之间的注册同步消息信封。
 *
 * <p>{@code registry-hub}（内嵌注册中心）与 {@code registry-client}（注册中心客户端）
 * 基于 {@link com.chua.common.support.network.sync.SyncClient} /
 * {@link com.chua.common.support.network.server.SyncServer} 通道交换本信封，
 * 完成注册、反注册与全量快照对齐。</p>
 *
 * <p>方向约定：</p>
 * <ul>
 *     <li>client -&gt; hub：{@link #OP_REGISTER} / {@link #OP_UNREGISTER} / {@link #OP_SYNC_REQUEST}，
 *     统一走共享主题 {@link #TOPIC}；</li>
 *     <li>hub -&gt; client：{@link #OP_SYNC}（全量快照）与 {@link #OP_EVENT}（增量变更），
 *     走客户端专属主题 {@link #topicOf(String)}——HTTP 传输的拉取队列按主题竞争消费，
 *     每个客户端独占一个下行主题才能保证事件不串流。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.52
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class RegistrySyncMessage {

    /**
     * client -&gt; hub 的共享主题
     */
    public static final String TOPIC = "registry-sync";

    /**
     * 操作：注册服务（client -&gt; hub，携带 path + discovery）
     */
    public static final String OP_REGISTER = "register";

    /**
     * 操作：注销服务（client -&gt; hub，携带 path + discovery.serverId）
     */
    public static final String OP_UNREGISTER = "unregister";

    /**
     * 操作：请求全量快照（client -&gt; hub）
     */
    public static final String OP_SYNC_REQUEST = "sync-request";

    /**
     * 操作：下发全量快照（hub -&gt; client，携带 services）
     */
    public static final String OP_SYNC = "sync";

    /**
     * 操作：增量变更事件（hub -&gt; client，携带 path + discovery + event）
     */
    public static final String OP_EVENT = "event";

    /**
     * 操作类型
     */
    private String op;

    /**
     * 客户端标识（hub 下发的事件里表示注册归属方）
     */
    private String clientId;

    /**
     * 服务路径（已含集群前缀与首斜杠）
     */
    private String path;

    /**
     * 服务实例信息
     */
    private Discovery discovery;

    /**
     * 事件名（add / update / remove），仅 {@link #OP_EVENT} 使用
     */
    private String event;

    /**
     * 全量快照：路径 -&gt; 服务实例列表，仅 {@link #OP_SYNC} 使用
     */
    private Map<String, List<Discovery>> services;

    /**
     * 创建 client -&gt; hub 的操作消息。
     *
     * @param op       操作类型
     * @param clientId 客户端标识
     * @return 消息信封
     */
    public static RegistrySyncMessage of(String op, String clientId) {
        return new RegistrySyncMessage().setOp(op).setClientId(clientId);
    }

    /**
     * 计算指定客户端的专属下行主题。
     *
     * @param clientId 客户端标识
     * @return 主题名
     */
    public static String topicOf(String clientId) {
        return TOPIC + "#" + clientId;
    }
}
