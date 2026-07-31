package com.chua.common.support.network.server.dht.bep9;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * BT Peer Wire 协议客户端（BEP 9 扩展）。
 * <p>
 * 与 BT 对等体建立连接，通过扩展协议协商并下载 torrent 元数据。
 * </p>
 *
 * @author CH
 */
public class PeerWireClient implements AutoCloseable {

    /**
     * BT 协议握手标识。
     */
    private static final byte[] PROTOCOL = "BitTorrent protocol".getBytes(StandardCharsets.US_ASCII);

    /**
     * 保留字节，启用扩展协议（BEP 10）。
     */
    private static final byte[] RESERVED = new byte[]{0, 0, 0, 0, 0, 0x10, 0, 0};

    /**
     * 扩展消息 ID（用于 ut_metadata）。
     */
    private static final int EXTENDED_MSG_ID = 20;

    /**
     * TCP 套接字。
     */
    private final Socket socket;

    /**
     * 套接字输入流。
     */
    private final InputStream in;

    /**
     * 套接字输出流。
     */
    private final OutputStream out;

    /**
     * 操作截止时间戳（毫秒）。
     */
    private final long deadline;

    /**
     * ut_metadata 扩展消息 ID（由对等体分配，-1 表示未知）。
     */
    private int utMetadataId = -1;

    /**
     * 对等体报告的元数据大小（字节）。
     */
    private long peerMetadataSize;

    /**
     * 构造 Peer Wire 客户端。
     *
     * @param address  对等体地址
     * @param infoHash 20 字节 infohash
     * @param timeoutMs 超时时间（毫秒）
     * @throws IOException 连接或握手失败
     */
    public PeerWireClient(InetSocketAddress address, byte[] infoHash, int timeoutMs) throws IOException {
        byte[] peerId = new byte[20];
        ThreadLocalRandom.current().nextBytes(peerId);
        this.deadline = System.currentTimeMillis() + timeoutMs;

        this.socket = new Socket();
        socket.connect(address, Math.min(timeoutMs, 10000));
        socket.setSoTimeout(50);
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();

        doHandshake(infoHash, peerId);
        waitForExtHandshake();
    }

    /**
     * 执行 BT 协议握手。
     *
     * @param infoHash 本地 infohash
     * @param peerId   本地 peer ID
     * @throws IOException 握手失败
     */
    private void doHandshake(byte[] infoHash, byte[] peerId) throws IOException {
        out.write(PROTOCOL.length);
        out.write(PROTOCOL);
        out.write(RESERVED);
        out.write(infoHash);
        out.write(peerId);
        out.flush();

        waitAvailable(1);
        int pstrlen = checkedRead();
        byte[] pstr = new byte[pstrlen];
        checkedReadFully(pstr);
        byte[] reserved = new byte[8];
        checkedReadFully(reserved);
        byte[] infoHashResp = new byte[20];
        checkedReadFully(infoHashResp);
        byte[] peerIdResp = new byte[20];
        checkedReadFully(peerIdResp);

        if (!Arrays.equals(infoHash, infoHashResp)) {
            throw new IOException("InfoHash mismatch");
        }
        if ((reserved[5] & 0x10) == 0) {
            throw new IOException("Peer does not support BEP 10");
        }
    }

    /**
     * 等待对等体发送扩展握手消息。
     *
     * @throws IOException 超时或握手失败
     */
    private void waitForExtHandshake() throws IOException {
        while (deadlineOk()) {
            Frame frame = readFrame();
            if (frame == null) {
                continue;
            }
            if (frame.id == EXTENDED_MSG_ID && frame.payload.length > 0) {
                int extId = frame.payload[0] & 0xff;
                if (extId == 0) {
                    parseExtHandshake(Arrays.copyOfRange(frame.payload, 1, frame.payload.length));
                    return;
                }
            }
        }
        throw new IOException("Timeout waiting for extension handshake");
    }

    /**
     * 解析扩展握手消息。
     *
     * @param data Bencode 编码的握手数据
     */
    @SuppressWarnings("unchecked")
    private void parseExtHandshake(byte[] data) {
        Map<String, Object> dict = (Map<String, Object>)
                com.chua.common.support.network.server.dht.krpc.BencodeCodec.decode(data);
        Object msObj = dict.get("metadata_size");
        if (msObj instanceof Long) {
            peerMetadataSize = (Long) msObj;
        }
        Map<String, Object> m = (Map<String, Object>) dict.get("m");
        if (m != null) {
            Object val = m.get("ut_metadata");
            if (val instanceof Long) {
                utMetadataId = ((Long) val).intValue();
            }
        }
    }

    /**
     * 获取 ut_metadata 扩展消息 ID。
     *
     * @return 扩展消息 ID，-1 表示未知
     */
    public int getUtMetadataId() {
        return utMetadataId;
    }

    /**
     * 获取对等体报告的元数据大小。
     *
     * @return 元数据大小（字节）
     */
    public long getPeerMetadataSize() {
        return peerMetadataSize;
    }

    /**
     * 发送扩展消息。
     *
     * @param extMsgId 扩展消息 ID
     * @param payload  消息负载
     * @throws IOException 发送失败
     */
    public void sendExtendedMessage(int extMsgId, byte[] payload) throws IOException {
        byte[] buf = new byte[1 + payload.length];
        buf[0] = (byte) extMsgId;
        System.arraycopy(payload, 0, buf, 1, payload.length);
        writeMessage(EXTENDED_MSG_ID, buf);
    }

    /**
     * 发送扩展握手消息（声明支持的 ut_metadata）。
     *
     * @param metadataSize 本地元数据大小
     * @throws IOException 发送失败
     */
    public void sendExtHandshake(long metadataSize) throws IOException {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("ut_metadata", 1L);
        Map<String, Object> dict = new java.util.LinkedHashMap<>();
        dict.put("m", m);
        dict.put("metadata_size", metadataSize);
        sendExtendedMessage(0, com.chua.common.support.network.server.dht.krpc.BencodeCodec.encode(dict));
    }

    /**
     * 读取一条 BT 消息帧。
     *
     * @return Frame 实例，超时或连接关闭返回 null
     * @throws IOException 读取失败
     */
    public Frame readFrame() throws IOException {
        if (!deadlineOk()) {
            return null;
        }
        int len = readInt();
        if (len < 0) {
            return null;
        }
        if (len == 0) {
            return null;
        }
        int id = checkedRead();
        if (id < 0) {
            return null;
        }
        byte[] payload = new byte[len - 1];
        checkedReadFully(payload);
        return new Frame(id, payload);
    }

    /**
     * 写入 BT 消息帧。
     *
     * @param id     消息 ID
     * @param payload 消息负载
     * @throws IOException 写入失败
     */
    private void writeMessage(int id, byte[] payload) throws IOException {
        int total = payload.length + 1;
        out.write(new byte[]{
                (byte) (total >> 24), (byte) (total >> 16),
                (byte) (total >> 8), (byte) total
        });
        out.write(id);
        out.write(payload);
        out.flush();
    }

    /**
     * 读取 4 字节大端序有符号整数。
     *
     * @return 整数，连接关闭返回 -1
     * @throws IOException 读取失败
     */
    private int readInt() throws IOException {
        int b0 = checkedRead();
        int b1 = checkedRead();
        int b2 = checkedRead();
        int b3 = checkedRead();
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) {
            return -1;
        }
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    /**
     * 读取单个字节。
     *
     * @return 字节值，连接关闭抛出 EOFException
     * @throws IOException 读取失败
     */
    private int checkedRead() throws IOException {
        waitAvailable(1);
        int b = in.read();
        if (b < 0) {
            throw new EOFException("Connection closed");
        }
        return b;
    }

    /**
     * 读取指定长度的字节数组。
     *
     * @param buf 目标缓冲区
     * @throws IOException 读取失败或连接关闭
     */
    private void checkedReadFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            waitAvailable(buf.length - off);
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new EOFException("Connection closed");
            }
            off += n;
        }
    }

    /**
     * 等待直到指定字节数可用或超时。
     *
     * @param needed 需要的字节数
     * @throws IOException 超时
     */
    private void waitAvailable(int needed) throws IOException {
        while (deadlineOk() && in.available() < needed) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!deadlineOk()) {
            throw new IOException("Read timeout");
        }
    }

    /**
     * 检查是否仍在截止时间内。
     *
     * @return 未超时返回 true
     */
    private boolean deadlineOk() {
        return System.currentTimeMillis() < deadline;
    }

    /**
     * 关闭套接字连接。
     */
    @Override
    public void close() {
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * BT 消息帧。
     */
    public static class Frame {

        /**
         * 消息 ID。
         */
        public final int id;

        /**
         * 消息负载。
         */
        public final byte[] payload;

        /**
         * 构造消息帧。
         *
         * @param id     消息 ID
         * @param payload 消息负载
         */
        Frame(int id, byte[] payload) {
            this.id = id;
            this.payload = payload;
        }
    }
}
