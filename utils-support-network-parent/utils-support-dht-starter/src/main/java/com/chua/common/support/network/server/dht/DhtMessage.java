package com.chua.common.support.network.server.dht;

import com.chua.common.support.lang.json.Json;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * DHT 消息体。
 * <p>
 * 表示 DHT 协议中节点之间交换的消息，通过 JSON 序列化进行网络传输。
 * 包含消息类型、发送者和接收者的标识符、以及可选的载荷数据。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class DhtMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 消息类型，例如 PING、FIND_NODE、STORE 等
     */
    private DhtMessageType type;

    /**
     * 发送者的节点 ID（160 位十六进制字符串）
     */
    private String senderId;

    /**
     * 发送者对外宣告的主机地址
     */
    private String senderHost;

    /**
     * 发送者对外宣告的端口号
     */
    private int senderPort;

    /**
     * 目标节点 ID，用于 FIND_NODE、FIND_VALUE 等消息定位目标
     */
    private String targetId;

    /**
     * BitTorrent infohash（仅在 GET_PEERS / ANNOUNCE_PEER 中使用）
     */
    private String infohash;

    /**
     * ANNOUNCE_PEER 中的 implied_port 标志，0 表示使用显式端口
     */
    private int impliedPort;

    /**
     * STORE / FIND_VALUE 操作中的键
     */
    private String key;

    /**
     * STORE / FIND_VALUE 操作中的值
     */
    private String value;

    /**
     * FIND_NODE_RESPONSE 中携带的近邻节点列表
     */
    private List<DhtPeer> peers;

    /**
     * GET_PEERS_RESPONSE 中携带的多个值映射
     */
    private Map<String, String> values;

    /**
     * 消息创建时间戳（毫秒）
     */
    private long timestamp;

    /**
     * STORE 消息中值的生存时间（秒）
     */
    private int ttl;

    /**
     * 接收端的本机地址（由传输层在反序列化时设置，不参与序列化）
     */
    private transient String recipientHost;

    /**
     * 接收端的本机端口（由传输层在反序列化时设置，不参与序列化）
     */
    private transient int recipientPort;

    /**
     * KRPC 事务 ID（仅入站 KRPC 查询时设置，不参与序列化）
     */
    private transient String krpcTxId;

    /**
     * 从 JSON 字符串反序列化为 DhtMessage 对象。
     *
     * @param json JSON 字符串
     * @return DhtMessage 实例
     */
    public static DhtMessage fromJson(String json) {
        return Json.fromJson(json, DhtMessage.class);
    }

    /**
     * 将当前消息对象序列化为 JSON 字符串。
     *
     * @return JSON 字符串
     */
    public String toJson() {
        return Json.toJson(this);
    }
}
