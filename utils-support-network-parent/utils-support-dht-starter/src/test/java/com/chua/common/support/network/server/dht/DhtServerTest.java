package com.chua.common.support.network.server.dht;

import com.chua.common.support.network.server.dht.krpc.BencodeCodec;
import com.chua.common.support.network.server.dht.krpc.KrpcMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link DHTServer} 真实链路用例：用裸 {@link DatagramSocket} 按 BEP 5（KRPC / bencode）
 * 直接发 UDP 查询，断言线上字节与响应字段。
 *
 * <p>不依赖任何 mock：服务端是真实的 Netty UDP 通道，客户端是手工拼的 bencode 数据报，
 * 覆盖 ping / find_node / get_peers / announce_peer 四类查询。</p>
 *
 * @author CH
 */
@Timeout(30)
class DhtServerTest {

    /**
     * 服务端固定节点 ID（20 字节），便于断言响应里的 id。
     */
    private static final byte[] NODE_ID = hexToBytes("0123456789abcdef0123456789abcdef01234567");

    /**
     * 客户端节点 ID（20 字节）。
     */
    private static final byte[] PEER_ID = hexToBytes("fedcba9876543210fedcba9876543210fedcba98");

    private static final byte[] TARGET = hexToBytes("1111111111111111111111111111111111111111");

    private static final byte[] INFO_HASH = hexToBytes("2222222222222222222222222222222222222222");

    /**
     * KRPC 紧凑节点条目长度：20 字节 ID + 4 字节 IP + 2 字节端口。
     */
    private static final int COMPACT_NODE_LEN = 26;

    private static DHTServer server;
    private static DatagramSocket client;
    private static InetAddress loopback;
    private static int port;

    @BeforeAll
    static void setUp() throws Exception {
        loopback = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket probe = new DatagramSocket(0, loopback)) {
            port = probe.getLocalPort();
        }
        server = DHTServer.builder()
                .host("127.0.0.1")
                .port(port)
                // seeds() 不带参数 = 清空默认公网引导节点，保证用例不触网
                .seeds()
                .nodeId(toHex(NODE_ID))
                .build();
        server.start();
        client = new DatagramSocket();
        client.setSoTimeout(3000);
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("ping：返回 y=r 且回显事务号与自身 20 字节节点 ID")
    void pingReturnsPong() throws Exception {
        KrpcMessage resp = query("ping", args(PEER_ID));
        assertEquals('r', resp.y, "ping 应回响应，而不是错误/空");
        assertEquals("aa", resp.t, "必须回显事务号 t");
        assertArrayEquals(NODE_ID, idOf(resp), "响应 id 应为服务端节点 ID");
    }

    @Test
    @DisplayName("连续 10 个数据报均得到响应（通道不会被首包关闭）")
    void handlesMultipleDatagrams() throws Exception {
        for (int i = 1; i <= 10; i++) {
            KrpcMessage resp = query("ping", args(PEER_ID));
            assertEquals('r', resp.y, "第 " + i + " 个数据报未得到响应");
        }
    }

    @Test
    @DisplayName("find_node：返回 26 字节整数倍的紧凑节点列表")
    void findNodeReturnsCompactNodes() throws Exception {
        Map<String, Object> args = args(PEER_ID);
        args.put("target", TARGET);
        KrpcMessage resp = query("find_node", args);
        assertEquals('r', resp.y);
        byte[] nodes = resp.getBytes("nodes");
        assertNotNull(nodes, "find_node 响应必须带 nodes");
        assertEquals(0, nodes.length % COMPACT_NODE_LEN,
                "紧凑节点列表长度必须是 " + COMPACT_NODE_LEN + " 的整数倍，实际 " + nodes.length);
        assertTrue(nodes.length >= COMPACT_NODE_LEN, "路由表为空时应至少返回自身节点");
    }

    @Test
    @DisplayName("get_peers：返回 token 与近邻节点")
    void getPeersReturnsToken() throws Exception {
        Map<String, Object> args = args(PEER_ID);
        // 用独立 infohash：本类共用一个服务端，避免被其它用例的 announce 污染
        args.put("info_hash", hexToBytes("5555555555555555555555555555555555555555"));
        KrpcMessage resp = query("get_peers", args);
        assertEquals('r', resp.y);
        String token = resp.getString("token");
        assertNotNull(token, "get_peers 响应必须带 token");
        assertFalse(token.isEmpty(), "token 不应为空串");
        byte[] nodes = resp.getBytes("nodes");
        assertNotNull(nodes, "get_peers 响应应带近邻节点");
        assertEquals(0, nodes.length % COMPACT_NODE_LEN);
        assertTrue(nodes.length >= COMPACT_NODE_LEN);
    }

    @Test
    @DisplayName("announce_peer：BEP5 要求必须回响应（此前无分支、无响应）")
    void announcePeerReturnsResponse() throws Exception {
        Map<String, Object> args = args(PEER_ID);
        args.put("info_hash", INFO_HASH);
        args.put("port", 6881L);
        args.put("implied_port", 0L);
        args.put("token", "deadbeef");
        KrpcMessage resp;
        try {
            resp = query("announce_peer", args);
        } catch (SocketTimeoutException e) {
            fail("announce_peer 查询没有收到任何响应（BEP5 要求返回 {id}）");
            return;
        }
        assertEquals('r', resp.y, "announce_peer 应回响应");
        assertEquals("aa", resp.t, "必须回显事务号 t");
        assertArrayEquals(NODE_ID, idOf(resp), "announce_peer 响应 id 应为服务端节点 ID");
    }

    @Test
    @DisplayName("announce_peer 后 get_peers 应返回该 peer（BEP5 values）")
    void announcedPeerIsDiscoverable() throws Exception {
        byte[] ih = hexToBytes("3333333333333333333333333333333333333333");
        int announcePort = 51413;

        Map<String, Object> announce = args(PEER_ID);
        announce.put("info_hash", ih);
        announce.put("port", (long) announcePort);
        announce.put("implied_port", 0L);
        announce.put("token", "deadbeef");
        KrpcMessage ack = query("announce_peer", announce);
        assertEquals('r', ack.y, "announce_peer 应回响应");

        Map<String, Object> ask = args(PEER_ID);
        ask.put("info_hash", ih);
        KrpcMessage resp = query("get_peers", ask);
        assertEquals('r', resp.y);

        List<byte[]> values = compactValuesOf(resp);
        assertNotNull(values, "宣告后 get_peers 应返回 values（peer 紧凑地址列表）");
        assertFalse(values.isEmpty(), "宣告的 peer 应能被查到");
        boolean found = false;
        for (byte[] v : values) {
            assertEquals(6, v.length, "紧凑 peer 必须是 6 字节（4 IP + 2 端口）");
            int port = ((v[4] & 0xff) << 8) | (v[5] & 0xff);
            if (port == announcePort) {
                found = true;
            }
        }
        assertTrue(found, "应查到刚宣告的端口 " + announcePort + "，实际: " + describe(values));
    }

    @Test
    @DisplayName("announce_peer 的 infohash 会进入爬取集合")
    void announcedInfohashIsCollected() throws Exception {
        byte[] ih = hexToBytes("6666666666666666666666666666666666666666");
        Map<String, Object> announce = args(PEER_ID);
        announce.put("info_hash", ih);
        announce.put("port", 6881L);
        announce.put("implied_port", 0L);
        announce.put("token", "deadbeef");
        assertEquals('r', query("announce_peer", announce).y);

        String hex = toHex(ih);
        assertTrue(server.collectedInfohashes().contains(hex),
                "爬取集合应包含刚宣告的 infohash，实际: " + server.collectedInfohashes());
    }

    @Test
    @DisplayName("announce_peer：implied_port=1 时用来源端口")
    void impliedPortUsesSenderPort() throws Exception {
        byte[] ih = hexToBytes("4444444444444444444444444444444444444444");
        Map<String, Object> announce = args(PEER_ID);
        announce.put("info_hash", ih);
        announce.put("implied_port", 1L);
        announce.put("token", "deadbeef");
        assertEquals('r', query("announce_peer", announce).y);

        Map<String, Object> ask = args(PEER_ID);
        ask.put("info_hash", ih);
        List<byte[]> values = compactValuesOf(query("get_peers", ask));
        assertNotNull(values, "implied_port 宣告后应能查到 peer");
        boolean found = false;
        for (byte[] v : values) {
            int port = ((v[4] & 0xff) << 8) | (v[5] & 0xff);
            if (port == client.getLocalPort()) {
                found = true;
            }
        }
        assertTrue(found, "implied_port=1 应记录来源端口 " + client.getLocalPort()
                + "，实际: " + describe(values));
    }

    /**
     * 取响应里的紧凑 peer 列表（values）。
     *
     * @param resp get_peers 响应
     * @return 6 字节数组列表；无 values 时返回 null
     */
    private static List<byte[]> compactValuesOf(KrpcMessage resp) {
        Object raw = resp.r == null ? null : resp.r.get("values");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<byte[]> out = new java.util.ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof byte[] bytes) {
                out.add(bytes);
            } else if (o instanceof String text) {
                // bencode 库按 ISO-8859-1 解字符串；紧凑 peer 是 6 字节二进制，必须按原字节还原
                out.add(text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            }
        }
        return out;
    }

    /**
     * 把紧凑 peer 列表转成可读文本（失败信息用）。
     *
     * @param values 紧凑 peer 列表
     * @return 描述文本
     */
    private static String describe(List<byte[]> values) {
        if (values == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("[");
        for (byte[] v : values) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(v.length == 6
                    ? ((v[0] & 0xff) + "." + (v[1] & 0xff) + "." + (v[2] & 0xff) + "." + (v[3] & 0xff)
                    + ":" + (((v[4] & 0xff) << 8) | (v[5] & 0xff)))
                    : ("len=" + v.length));
        }
        return sb.append(']').toString();
    }

    @Test
    @DisplayName("非法报文：丢弃且不影响后续查询")
    void garbageDatagramDoesNotBreakServer() throws Exception {
        byte[] junk = "this-is-not-bencode".getBytes(StandardCharsets.UTF_8);
        client.send(new DatagramPacket(junk, junk.length, loopback, port));
        KrpcMessage resp = query("ping", args(PEER_ID));
        assertEquals('r', resp.y, "收到非法报文后服务端仍应正常应答");
        assertArrayEquals(NODE_ID, idOf(resp));
    }

    /**
     * 发送一条 KRPC 查询并等待响应。
     *
     * @param q    查询类型
     * @param args 查询参数
     * @return 解析后的响应
     * @throws Exception 网络异常或超时
     */
    private static KrpcMessage query(String q, Map<String, Object> args) throws Exception {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("t", "aa");
        msg.put("y", "q");
        msg.put("q", q);
        msg.put("a", args);
        byte[] payload = BencodeCodec.encode(msg);
        client.send(new DatagramPacket(payload, payload.length, loopback, port));

        byte[] buf = new byte[4096];
        DatagramPacket resp = new DatagramPacket(buf, buf.length);
        client.receive(resp);
        return KrpcMessage.parse(Arrays.copyOf(resp.getData(), resp.getLength()));
    }

    /**
     * 构造查询参数（带客户端节点 ID）。
     *
     * @param id 节点 ID
     * @return 可变参数映射
     */
    private static Map<String, Object> args(byte[] id) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("id", id);
        return args;
    }

    /**
     * 取响应中的节点 ID 原始字节。
     *
     * @param resp 响应
     * @return 20 字节节点 ID
     */
    private static byte[] idOf(KrpcMessage resp) {
        byte[] id = resp.getBytes("id");
        assertNotNull(id, "响应 r.id 不应为空");
        return id;
    }

    /**
     * 十六进制字符串转字节数组。
     *
     * @param hex 40 字符十六进制
     * @return 20 字节数组
     */
    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /**
     * 字节数组转十六进制字符串。
     *
     * @param raw 字节数组
     * @return 十六进制串
     */
    private static String toHex(byte[] raw) {
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
