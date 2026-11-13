package com.chua.common.support.lang.algorithm.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Codec 二级接口（传输级）：aes-gcm 预共享口令、keylog 握手一次。
 *
 * @author CH
 * @since 4.0.0.50
 */
class StreamCodecTest {

    /**
     * 测试载荷
     */
    private static final byte[] PAYLOAD = "连接加密负载-月英".getBytes(StandardCharsets.UTF_8);

    /**
     * 一对长期密钥
     */
    private static final String[] SERVER_KEYS = KeyLogCodec.generateKeyPair();

    /**
     * 服务端指纹
     */
    private static final String PIN = KeyLogCodec.fingerprint(SERVER_KEYS[0]);

    /**
     * aes-gcm 预共享口令：两侧用同一口令，无需握手。
     */
    @Test
    @DisplayName("aes-gcm 同口令双向可通")
    void aesGcmRoundTrip() throws IOException {
        StreamCodec client = StreamCodec.buildStream("aes-gcm", "共享口令");
        StreamCodec server = StreamCodec.buildStream("aes", "共享口令");
        Conn conn = connect(client, server);

        conn.clientOut().write(PAYLOAD);
        conn.clientOut().flush();
        assertArrayEquals(PAYLOAD, conn.serverIn().readNBytes(PAYLOAD.length));

        conn.serverOut().write(PAYLOAD);
        conn.serverOut().flush();
        assertArrayEquals(PAYLOAD, conn.clientIn().readNBytes(PAYLOAD.length));
    }

    /**
     * aes-gcm 口令不一致时解密失败。
     */
    @Test
    @DisplayName("aes-gcm 口令不一致即失败")
    void aesGcmWrongKeyFails() {
        StreamCodec client = StreamCodec.buildStream("aes-gcm", "口令A");
        StreamCodec stranger = StreamCodec.buildStream("aes-gcm", "口令B");
        assertThrows(Exception.class, () -> {
            Conn conn = connect(client, stranger);
            conn.clientOut().write(PAYLOAD);
            conn.clientOut().flush();
            conn.serverIn().readNBytes(PAYLOAD.length);
        });
    }

    /**
     * keylog：客户端用公钥发起握手，服务端用私钥接住，之后双向可通。
     */
    @Test
    @DisplayName("keylog 握手一次后双向可通")
    void keyLogHandshakeRoundTrip() throws IOException {
        StreamCodec client = StreamCodec.buildStream("keylog", "client:" + SERVER_KEYS[0] + "#" + PIN);
        StreamCodec server = StreamCodec.buildStream("keylog", "server:" + SERVER_KEYS[1]);
        Conn conn = connect(client, server);

        conn.clientOut().write(PAYLOAD);
        conn.clientOut().flush();
        assertArrayEquals(PAYLOAD, conn.serverIn().readNBytes(PAYLOAD.length));

        conn.serverOut().write(PAYLOAD);
        conn.serverOut().flush();
        assertArrayEquals(PAYLOAD, conn.clientIn().readNBytes(PAYLOAD.length));
    }

    /**
     * keylog 私钥不匹配时握手即失败。
     */
    @Test
    @DisplayName("keylog 私钥不匹配握手失败")
    void keyLogWrongKeyFails() {
        String[] other = KeyLogCodec.generateKeyPair();
        StreamCodec client = StreamCodec.buildStream("keylog", "client:" + SERVER_KEYS[0] + "#" + PIN);
        StreamCodec stranger = StreamCodec.buildStream("keylog", "server:" + other[1]);
        assertThrows(Exception.class, () -> {
            Conn conn = connect(client, stranger);
            conn.clientOut().write(PAYLOAD);
            conn.clientOut().flush();
            conn.serverIn().readNBytes(PAYLOAD.length);
        });
    }

    /**
     * keylog 公钥 pin 不匹配时客户端拒绝加载。
     */
    @Test
    @DisplayName("keylog pin 不匹配拒绝加载")
    void keyLogPinMismatch() {
        String[] other = KeyLogCodec.generateKeyPair();
        assertThrows(IllegalArgumentException.class,
                () -> StreamCodec.buildStream("keylog", "client:" + other[0] + "#" + PIN));
    }

    /**
     * 未知类型、缺失口令都直接失败，不静默降级。
     */
    @Test
    @DisplayName("未知类型与缺失口令即失败")
    void invalidConfigRejected() {
        assertThrows(IllegalArgumentException.class, () -> StreamCodec.buildStream("no-such-cipher", "k"));
        assertThrows(IllegalArgumentException.class, () -> StreamCodec.buildStream("aes-gcm", ""));
        assertThrows(IllegalArgumentException.class, () -> StreamCodec.buildStream("keylog", ""));
    }

    /**
     * 二级接口同时具备一级（消息级）能力：单条消息加解密。
     */
    @Test
    @DisplayName("二级接口消息级能力与 Codec.build 一致")
    void messageLevelCompat() {
        StreamCodec aes = StreamCodec.buildStream("aes-gcm", "口令");
        String cipher = aes.encodeHex("hello");
        assertEquals("hello", aes.decodeHex(cipher));

        StreamCodec keylog = StreamCodec.buildStream("keylog", "client:" + SERVER_KEYS[0] + "#" + PIN);
        String k2 = keylog.encodeHex("hello");
        assertEquals("hello", StreamCodec.buildStream("keylog", "server:" + SERVER_KEYS[1]).decodeHex(k2));
    }

    /**
     * 用全双工管道把两端接起来：发起方先握手，响应方接住。
     */
    private static Conn connect(StreamCodec client, StreamCodec server) throws IOException {
        PipedInputStream clientRead = new PipedInputStream();
        PipedOutputStream serverWrite = new PipedOutputStream(clientRead);
        PipedInputStream serverRead = new PipedInputStream();
        PipedOutputStream clientWrite = new PipedOutputStream(serverRead);

        StreamCodec.Streams clientStreams = client.open(clientRead, clientWrite, true);
        StreamCodec.Streams serverStreams = server.open(serverRead, serverWrite, false);
        return new Conn(clientStreams.in(), clientStreams.out(), serverStreams.in(), serverStreams.out());
    }

    /**
     * 全双工连接的两端流。
     */
    private record Conn(InputStream clientIn, OutputStream clientOut,
                        InputStream serverIn, OutputStream serverOut) {
    }
}
