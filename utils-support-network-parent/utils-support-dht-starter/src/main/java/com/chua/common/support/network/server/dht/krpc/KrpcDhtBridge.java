package com.chua.common.support.network.server.dht.krpc;

import com.chua.common.support.network.server.dht.DhtMessage;
import com.chua.common.support.network.server.dht.DhtMessageType;
import com.chua.common.support.network.server.dht.DhtPeer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
* 内部 DHT 与标准 KRPC 协议之间的桥接器。
* <p>
* 实现 DHT 内部消息格式（JSON）与 钻头torrent KRPC 协议格式（Bencode）的双向转换，
* 支持紧凑节点列表编解码，使本 DHT 实现能与标准 钻头torrent DHT 网络互通。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public class KrpcDhtBridge {

    /**
    * KRPC 节点 标识 的字节长度（20 字节 = 160 位）
    */
    private static final int KRP_NODE_ID_BYTES = 20;

    /**
    * 事务 标识 计数器
    */
    private final AtomicInteger txCounter = new AtomicInteger(0);

    /**
    * 挂起的查询映射（事务 标识 -> pending查询）
    */
    private final Map<String, PendingQuery> pending = new ConcurrentHashMap<>();

    /**
    * 挂起的查询记录，包含事务 标识、原始请求和消息类型。
    * @author CH
    * @since 4.0.0
    */
    public static class PendingQuery {

        /**
        * 事务 标识
        */
        public final String txId;

        /**
        * 原始请求消息
        */
        public final DhtMessage request;

        /**
        * 请求消息类型
        */
        public final DhtMessageType type;

        /**
        * 请求发起时间戳
        */
        public final long startTime;

        /**
        * 构造挂起查询记录。
        *
        * @param txId    事务 标识
        * @param request 原始请求
        * @param type    消息类型
        */
        public PendingQuery(String txId, DhtMessage request, DhtMessageType type) {
            this.txId = txId;
            this.request = request;
            this.type = type;
            this.startTime = System.currentTimeMillis();
        }
    }

    /**
    * 生成随机的 KRPC 节点 标识（SHA-1 哈希）。
    *
    * @return 20 字节的节点 标识
    */
    public static byte[] generateNodeId() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(("krpc-" + System.nanoTime() + "-" + Math.random()).getBytes(StandardCharsets.UTF_8));
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            byte[] id = new byte[20];
            new Random().nextBytes(id);
            return id;
        }
    }

    /**
    * 将十六进制节点 标识 转换为 KRPC 协议中的 20 字节原始 标识。
    *
    * @param hex 十六进制字符串
    * @return 20 字节原始 标识
    */
    public static byte[] hexToRawId(String hex) {
        if (hex == null || hex.isEmpty()) {
            byte[] id = new byte[20];
            new Random().nextBytes(id);
            return id;
        }
        byte[] raw = new byte[KRP_NODE_ID_BYTES];
        String hexStr = hex.replace("-", "");
        byte[] full = hexStringToBytes(hexStr);
        System.arraycopy(full, 0, raw, 0, Math.min(KRP_NODE_ID_BYTES, full.length));
        return raw;
    }

    /**
    * 将 KRPC 协议的 20 字节原始 标识 转换为十六进制字符串。
    *
    * @param raw 20 字节原始 标识
    * @return 十六进制字符串
    */
    public static String rawIdToHex(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
    * 将十六进制字符串转换为字节数组。
    *
    * @param hex 十六进制字符串
    * @return 字节数组
    */
    public static byte[] hexStringToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /**
    * 生成下一个事务 标识。
    *
    * @return 2 字节的事务 标识
    */
    public String nextTxId() {
        int n = txCounter.incrementAndGet() & 0xffff;
        return new String(new byte[]{(byte) (n >> 8), (byte) (n & 0xff)}, StandardCharsets.ISO_8859_1);
    }

    /**
    * 注册挂起的查询。
    *
    * @param txId    事务 标识
    * @param request 原始请求
    * @param type    消息类型
    */
    public void registerPending(String txId, DhtMessage request, DhtMessageType type) {
        pending.put(txId, new PendingQuery(txId, request, type));
    }

    /**
    * 移除并返回挂起的查询。
    *
    * @param txId 事务 标识
    * @return PendingQuery 实例，未找到返回 空
    */
    public PendingQuery removePending(String txId) {
        return pending.remove(txId);
    }

    /**
    * 判断字节数组是否为 KRPC（Bencode）格式。
    *
    * @param data 字节数组
    * @return 如果是 Bencode 格式返回 true
    */
    public static boolean isKrpcMessage(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }
        byte first = data[0];
        return first == 'd' || first == 'l' || first == 'i';
    }

    /**
    * 判断字节数组是否为 JSON 格式。
    *
    * @param data 字节数组
    * @return 如果以 '{' 开头返回 true
    */
    public static boolean isJsonMessage(byte[] data) {
        return data != null && data.length > 0 && data[0] == '{';
    }

    /**
    * 将 DHT 内部消息编码为 KRPC 查询格式。
    *
    * @param msg  DHT 消息
    * @param txId 事务 标识
    * @return Bencode 编码的字节数组
    */
    public byte[] encodeQuery(DhtMessage msg, String txId) {
        KrpcMessage krpc = new KrpcMessage();
        krpc.t = txId;
        krpc.y = 'q';
        krpc.a = new LinkedHashMap<>();
        krpc.a.put("id", hexToRawId(msg.getSenderId()));

        switch (msg.getType()) {
            case PING:
                krpc.q = "ping";
                break;
            case FIND_NODE:
                krpc.q = "find_node";
                krpc.a.put("target", hexToRawId(msg.getTargetId()));
                break;
            case GET_PEERS:
                krpc.q = "get_peers";
                krpc.a.put("info_hash",
                        hexToRawId(msg.getInfohash() != null ? msg.getInfohash() : msg.getTargetId()));
                break;
            case ANNOUNCE_PEER:
                krpc.q = "announce_peer";
                krpc.a.put("info_hash", hexToRawId(msg.getInfohash()));
                krpc.a.put("port", (long) (msg.getImpliedPort() > 0 ? msg.getImpliedPort() : msg.getSenderPort()));
                krpc.a.put("token", "");
                krpc.a.put("implied_port", msg.getImpliedPort() > 0 ? 1L : 0L);
                break;
            default:
                krpc.q = "ping";
        }
        return krpc.encode();
    }

    /**
    * 解码 KRPC 响应并转换为 DHT 内部消息。
    *
    * @param data           KRPC 响应的字节数组
    * @param originalRequest 原始请求消息
    * @return 解码后的 DHT 消息
    */
    public DhtMessage decodeResponse(byte[] data, DhtMessage originalRequest) {
        KrpcMessage krpc = KrpcMessage.parse(data);
        if (krpc.y == 'e') {
            DhtMessage err = new DhtMessage();
            err.setType(originalRequest.getType());
            err.setSenderId("");
            err.setTargetId(originalRequest.getTargetId());
            err.setValue("KRPC error: " + (krpc.e != null ? krpc.e : "[unknown]"));
            return err;
        }
        if (krpc.r == null) {
            return null;
        }

        DhtMessage resp = new DhtMessage();
        resp.setType(responseTypeOf(originalRequest.getType()));
        resp.setTargetId(originalRequest.getTargetId());
        resp.setSenderId(rawIdToHex(getBytes(krpc.r, "id")));
        resp.setTimestamp(System.currentTimeMillis());

        switch (originalRequest.getType()) {
            case FIND_NODE: {
                byte[] nodes = getBytes(krpc.r, "nodes");
                if (nodes != null) {
                    List<DhtPeer> peers = decodeCompactNodeList(nodes);
                    resp.setPeers(peers);
                }
                if (resp.getPeers() == null) {
                    resp.setPeers(Collections.emptyList());
                }
                break;
            }
            case GET_PEERS: {
                @SuppressWarnings("unchecked")
                List<Object> values = (List<Object>) krpc.r.get("values");
                if (values != null) {
                    List<DhtPeer> peers = new ArrayList<>();
                    for (Object v : values) {
                        byte[] peerData = v instanceof String
                                ? ((String) v).getBytes(StandardCharsets.ISO_8859_1)
                                : (byte[]) v;
                        if (peerData.length >= 6) {
                            String host = (peerData[0] & 0xff) + "." + (peerData[1] & 0xff) + "."
                                    + (peerData[2] & 0xff) + "." + (peerData[3] & 0xff);
                            int port = ((peerData[4] & 0xff) << 8) | (peerData[5] & 0xff);
                            peers.add(DhtPeer.builder()
                                    .nodeId("")
                                    .host(host)
                                    .port(port)
                                    .lastSeen(System.currentTimeMillis())
                                    .build());
                        }
                    }
                    resp.setPeers(peers);
                    resp.setInfohash(originalRequest.getInfohash());
                }
                if (resp.getPeers() == null || resp.getPeers().isEmpty()) {
                    byte[] nodes = getBytes(krpc.r, "nodes");
                    if (nodes != null) {
                        resp.setPeers(decodeCompactNodeList(nodes));
                    }
                }
                String token = getString(krpc.r, "token");
                resp.setValue(token);
                break;
            }
            case ANNOUNCE_PEER: {
                resp.setInfohash(originalRequest.getInfohash());
                break;
            }
            default:
                break;
        }
        return resp;
    }

    /**
    * 将 DHT 内部响应消息编码为 KRPC 响应格式。
    *
    * @param resp DHT 响应消息
    * @param txId 事务 标识
    * @return Bencode 编码的字节数组
    */
    public byte[] encodeResponse(DhtMessage resp, String txId) {
        KrpcMessage krpc = new KrpcMessage();
        krpc.t = txId;
        krpc.y = 'r';
        krpc.r = new LinkedHashMap<>();
        krpc.r.put("id", hexToRawId(resp.getSenderId()));

        switch (resp.getType()) {
            case FIND_NODE_RESPONSE: {
                if (resp.getPeers() != null && !resp.getPeers().isEmpty()) {
                    krpc.r.put("nodes", encodeCompactNodeList(resp.getPeers()));
                } else {
                    krpc.r.put("nodes", new byte[0]);
                }
                break;
            }
            case GET_PEERS_RESPONSE: {
                if (resp.getPeers() != null && !resp.getPeers().isEmpty()) {
                    boolean hasNodeIds = resp.getPeers().stream()
                            .anyMatch(p -> p.getNodeId() != null && !p.getNodeId().isEmpty());
                    if (hasNodeIds) {
                        krpc.r.put("nodes", encodeCompactNodeList(resp.getPeers()));
                    } else {
                        List<byte[]> values = new ArrayList<>();
                        for (DhtPeer p : resp.getPeers()) {
                            values.add(encodeCompactPeer(p));
                        }
                        krpc.r.put("values", values);
                    }
                }
                krpc.r.put("token", resp.getValue() != null ? resp.getValue() : "");
                break;
            }
            case ANNOUNCE_PEER_RESPONSE: {
                break;
            }
            case PONG:
                break;
            default:
                break;
        }
        return krpc.encode();
    }

    /**
    * 解码 KRPC 查询消息（入站）并转换为 DHT 内部消息。
    *
    * @param krpc   KRPC 消息
    * @param sender 发送者地址
    * @return DHT 消息
    */
    public DhtMessage decodeQuery(KrpcMessage krpc, InetSocketAddress sender) {
        DhtMessage msg = new DhtMessage();
        msg.setKrpcTxId(krpc.t);
        msg.setSenderId(rawIdToHex(getBytes(krpc.a, "id")));
        msg.setSenderHost(sender.getHostString());
        msg.setSenderPort(sender.getPort());
        msg.setTimestamp(System.currentTimeMillis());
        msg.setTargetId(msg.getSenderId());

        switch (krpc.q) {
            case "ping":
                msg.setType(DhtMessageType.PING);
                break;
            case "find_node": {
                msg.setType(DhtMessageType.FIND_NODE);
                byte[] target = getBytes(krpc.a, "target");
                if (target != null) {
                    msg.setTargetId(rawIdToHex(target));
                }
                break;
            }
            case "get_peers": {
                msg.setType(DhtMessageType.GET_PEERS);
                byte[] ih = getBytes(krpc.a, "info_hash");
                String infohash = ih != null ? rawIdToHex(ih) : "";
                msg.setInfohash(infohash);
                msg.setTargetId(infohash);
                break;
            }
            case "announce_peer": {
                msg.setType(DhtMessageType.ANNOUNCE_PEER);
                byte[] ih = getBytes(krpc.a, "info_hash");
                String infohash = ih != null ? rawIdToHex(ih) : "";
                msg.setInfohash(infohash);
                msg.setTargetId(infohash);
                Long port = getLong(krpc.a, "port");
                Long implied = getLong(krpc.a, "implied_port");
                if (implied != null && implied == 1) {
                    msg.setImpliedPort(sender.getPort());
                } else if (port != null) {
                    msg.setImpliedPort(port.intValue());
                }
                byte[] token = getBytes(krpc.a, "token");
                if (token != null) {
                    msg.setValue(new String(token, StandardCharsets.ISO_8859_1));
                }
                break;
            }
            default:
                msg.setType(DhtMessageType.PING);
        }
        return msg;
    }

    /**
    * 将节点列表编码为紧凑节点列表格式（每个节点 26 字节）。
    *
    * @param peers 节点列表
    * @return 紧凑编码的字节数组
    */
    public byte[] encodeCompactNodeList(List<DhtPeer> peers) {
        List<byte[]> entries = new ArrayList<>();
        for (DhtPeer p : peers) {
            byte[] nodeId = p.getNodeId() != null && !p.getNodeId().isEmpty()
                    ? hexToRawId(p.getNodeId()) : new byte[KRP_NODE_ID_BYTES];
            byte[] addr = compactAddressBytes(p.getHost(), p.getPort());
            if (addr != null) {
                byte[] entry = new byte[KRP_NODE_ID_BYTES + 6];
                System.arraycopy(nodeId, 0, entry, 0, KRP_NODE_ID_BYTES);
                System.arraycopy(addr, 0, entry, KRP_NODE_ID_BYTES, 6);
                entries.add(entry);
            }
        }
        int total = entries.stream().mapToInt(e -> e.length).sum();
        byte[] result = new byte[total];
        int off = 0;
        for (byte[] e : entries) {
            System.arraycopy(e, 0, result, off, e.length);
            off += e.length;
        }
        return result;
    }

    /**
    * 解码紧凑节点列表（每个节点 26 字节）为 dhtpeer 列表。
    *
    * @param data 紧凑编码的字节数组
    * @return 节点列表
    */
    public List<DhtPeer> decodeCompactNodeList(byte[] data) {
        List<DhtPeer> peers = new ArrayList<>();
        int entrySize = KRP_NODE_ID_BYTES + 6;
        for (int i = 0; i + entrySize <= data.length; i += entrySize) {
            byte[] nodeId = new byte[KRP_NODE_ID_BYTES];
            System.arraycopy(data, i, nodeId, 0, KRP_NODE_ID_BYTES);
            String host = (data[i + 20] & 0xff) + "." + (data[i + 21] & 0xff) + "."
                    + (data[i + 22] & 0xff) + "." + (data[i + 23] & 0xff);
            int port = ((data[i + 24] & 0xff) << 8) | (data[i + 25] & 0xff);
            peers.add(DhtPeer.builder()
                    .nodeId(rawIdToHex(nodeId))
                    .host(host)
                    .port(port)
                    .lastSeen(System.currentTimeMillis())
                    .build());
        }
        return peers;
    }

    /**
    * 将节点编码为紧凑 peer 格式（6 字节：4 字节 IP + 2 字节端口）。
    *
    * @param p 节点
    * @return 6 字节的紧凑编码
    */
    public byte[] encodeCompactPeer(DhtPeer p) {
        byte[] addr = compactAddressBytes(p.getHost(), p.getPort());
        return addr != null ? addr : new byte[6];
    }

    /**
    * 将主机和端口编码为紧凑地址格式（6 字节）。
    *
    * @param host 主机 ipv4 地址
    * @param port 端口号
    * @return 6 字节数组，格式异常时返回 空
    */
    private byte[] compactAddressBytes(String host, int port) {
        try {
            byte[] result = new byte[6];
            String[] parts = host.split("\\.");
            if (parts.length != 4) {
                return null;
            }
            for (int i = 0; i < 4; i++) {
                int b = Integer.parseInt(parts[i]);
                if (b < 0 || b > 255) {
                    return null;
                }
                result[i] = (byte) b;
            }
            result[4] = (byte) (port >> 8);
            result[5] = (byte) (port & 0xff);
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    /**
    * 根据请求类型返回对应的响应类型。
    *
    * @param requestType 请求类型
    * @return 响应类型
    */
    private DhtMessageType responseTypeOf(DhtMessageType requestType) {
        switch (requestType) {
            case PING:
                return DhtMessageType.PONG;
            case FIND_NODE:
                return DhtMessageType.FIND_NODE_RESPONSE;
            case GET_PEERS:
                return DhtMessageType.GET_PEERS_RESPONSE;
            case ANNOUNCE_PEER:
                return DhtMessageType.ANNOUNCE_PEER_RESPONSE;
            default:
                return requestType;
        }
    }

    /**
    * 从 映射 中获取字节数组类型的值。
    *
    * @param map 映射
    * @param key 键
    * @return 字节数组
    */
    private static byte[] getBytes(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object v = map.get(key);
        if (v instanceof byte[]) {
            return (byte[]) v;
        }
        if (v instanceof String) {
            return ((String) v).getBytes(StandardCharsets.ISO_8859_1);
        }
        return null;
    }

    /**
    * 从 映射 中获取字符串类型的值。
    *
    * @param map 映射
    * @param key 键
    * @return 字符串
    */
    private static String getString(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object v = map.get(key);
        if (v instanceof String) {
            return (String) v;
        }
        if (v instanceof byte[]) {
            return new String((byte[]) v, StandardCharsets.ISO_8859_1);
        }
        return v != null ? v.toString() : null;
    }

    /**
    * 从 映射 中获取长整型类型的值。
    *
    * @param map 映射
    * @param key 键
    * @return 长整型值
    */
    private static Long getLong(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object v = map.get(key);
        return v instanceof Number ? ((Number) v).longValue() : null;
    }
}
