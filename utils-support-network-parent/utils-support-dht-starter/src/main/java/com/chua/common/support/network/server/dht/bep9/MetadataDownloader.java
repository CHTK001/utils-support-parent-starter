package com.chua.common.support.network.server.dht.bep9;

import com.chua.common.support.network.server.dht.krpc.BencodeCodec;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
* BEP 9 元数据下载器。
* <p>
* 通过扩展协议与 BT 对等体协商并下载 torrent 元数据（种子文件）。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public class MetadataDownloader {

    /**
    * 元数据块大小（字节），与 BEP 9 规范一致。
     */
    private static final int BLOCK_SIZE = 16384;

    /**
    * 单个块的最大重试次数。
     */
    private static final int MAX_RETRIES = 2;

    /**
    * 下载取消标志。
     */
    private volatile boolean cancelled;

    /**
    * 从指定 BT 对等体下载元数据。
    *
    * @param peer     对等体地址
    * @param infoHash 20 字节 infohash
    * @param timeoutMs 超时时间（毫秒）
    * @return 下载结果
     */
    public MetadataResult download(InetSocketAddress peer, byte[] infoHash, int timeoutMs) {
        this.cancelled = false;
        long deadline = System.currentTimeMillis() + timeoutMs;
        PeerWireClient client = null;
        try {
            client = new PeerWireClient(peer, infoHash, timeoutMs);
            if (client.getUtMetadataId() < 0) {
                return MetadataResult.err("no ut_metadata");
            }
            long metadataSize = client.getPeerMetadataSize();
            if (metadataSize <= 0 || metadataSize > 10_000_000) {
                return MetadataResult.err("invalid metadata_size: " + metadataSize);
            }

            client.sendExtHandshake(0);

            int pieces = (int) ((metadataSize + BLOCK_SIZE - 1) / BLOCK_SIZE);
            byte[][] blocks = new byte[pieces][];
            int utId = client.getUtMetadataId();

            long totalSize = metadataSize;
            for (int i = 0; i < pieces && !cancelled; i++) {
                if (System.currentTimeMillis() > deadline) {
                    return MetadataResult.err("timeout piece " + i);
                }
                byte[] block = downloadPiece(client, utId, i, deadline);
                if (block == null) {
                    return MetadataResult.err("piece " + i + " failed");
                }
                int expected = (i == pieces - 1) ? (int) (totalSize - (long) BLOCK_SIZE * i) : BLOCK_SIZE;
                if (block.length != expected) {
                    return MetadataResult.err("piece " + i + " size mismatch: " + block.length + " != " + expected);
                }
                blocks[i] = block;
            }
            if (cancelled) {
                return MetadataResult.err("cancelled");
            }

            byte[] full = new byte[(int) metadataSize];
            int pos = 0;
            for (byte[] block : blocks) {
                int copyLen = Math.min(block.length, full.length - pos);
                System.arraycopy(block, 0, full, pos, copyLen);
                pos += copyLen;
            }

            String name = parseName(full);
            return MetadataResult.ok(name, full);
        } catch (Exception e) {
            return MetadataResult.err(e.getMessage());
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    /**
    * 下载指定索引的元数据块。
    *
    * @param client   peerwire 客户端
    * @param utId     ut_metadata 扩展消息 标识
    * @param piece    块索引
    * @param deadline 截止时间戳（毫秒）
    * @return 块数据，失败返回 空
    * @throws Exception 下载异常
     */
    private byte[] downloadPiece(PeerWireClient client, int utId, int piece, long deadline) throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("msg_type", 0L);
        req.put("piece", (long) piece);
        byte[] reqBytes = BencodeCodec.encode(req);

        for (int retry = 0; retry < MAX_RETRIES && !cancelled; retry++) {
            if (System.currentTimeMillis() > deadline) {
                return null;
            }
            client.sendExtendedMessage(utId, reqBytes);

            long roundEnd = Math.min(deadline, System.currentTimeMillis() + 4000);
            while (System.currentTimeMillis() < roundEnd && !cancelled) {
                PeerWireClient.Frame frame = client.readFrame();
                if (frame == null) {
                    break;
                }
                if (frame.id != 20) {
                    continue;
                }
                byte[] result = tryExtractPiece(frame.payload, piece, utId);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
    * 从扩展消息负载中提取指定块的数据。
    *
    * @param payload      消息负载
    * @param expectedPiece 期望的块索引
    * @param extMsgId     扩展消息 标识
    * @return 块数据，不匹配或异常返回 空
     */
    private byte[] tryExtractPiece(byte[] payload, int expectedPiece, int extMsgId) {
        if (payload.length < 2) {
            return null;
        }
        if ((payload[0] & 0xff) != extMsgId) {
            return null;
        }

        byte[] body = Arrays.copyOfRange(payload, 1, payload.length);
        int dictEnd = findDictEnd(body, 0);
        if (dictEnd <= 0) {
            return null;
        }

        byte[] dictBytes = Arrays.copyOf(body, dictEnd);
        byte[] raw = Arrays.copyOfRange(body, dictEnd, body.length);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> dict = (Map<String, Object>) BencodeCodec.decode(dictBytes);
            long msgType = dict.get("msg_type") instanceof Long ? (Long) dict.get("msg_type") : -1;
            long piece = dict.get("piece") instanceof Long ? (Long) dict.get("piece") : -1;

            byte[] resultBlock = raw.length > 0 ? raw : null;
            if (piece != expectedPiece) {
                return null;
            }
            if (msgType == 2) {
                return null;
            }
            if (msgType != 1) {
                return null;
            }

            return resultBlock;
        } catch (Exception e) {
            return null;
        }
    }

    /**
    * 在 Bencode 数据中查找字典结束位置。
    *
    * @param data  Bencode 字节数组
    * @param start 起始索引
    * @return 字典结束位置（'e' 的位置），无效返回 -1
     */
    static int findDictEnd(byte[] data, int start) {
        if (start >= data.length || data[start] != 'd') {
            return -1;
        }
        int depth = 1;
        int i = start + 1;
        while (i < data.length && depth > 0) {
            byte b = data[i];
            if (b == 'd' || b == 'l') {
                depth++;
            } else if (b == 'e') {
                depth--;
            } else if (b == 'i') {
                while (i < data.length && data[i] != 'e') {
                    i++;
                }
            } else if (b >= '0' && b <= '9') {
                int lenEnd = i;
                while (lenEnd < data.length && data[lenEnd] != ':') {
                    lenEnd++;
                }
                if (lenEnd >= data.length) {
                    return -1;
                }
                int len = 0;
                for (int j = i; j < lenEnd; j++) {
                    len = len * 10 + (data[j] - '0');
                }
                i = lenEnd + len;
            }
            i++;
        }
        return depth == 0 ? i : -1;
    }

    /**
    * 从元数据中解析种子名称。
    *
    * @param metadata 完整元数据字节数组
    * @return 种子名称，失败返回 "unknown"
     */
    @SuppressWarnings("unchecked")
    private String parseName(byte[] metadata) {
        try {
            Map<String, Object> root = (Map<String, Object>) BencodeCodec.decode(metadata);
            Object infoObj = root.get("info");
            if (infoObj instanceof Map) {
                Map<String, Object> info = (Map<String, Object>) infoObj;
                String name = str(info.get("name"));
                if (name != null) {
                    return name;
                }
                Object filesObj = info.get("files");
                if (filesObj instanceof java.util.List) {
                    java.util.List<Map<String, Object>> files = (java.util.List<Map<String, Object>>) filesObj;
                    if (!files.isEmpty()) {
                        Object pathObj = files.get(0).get("path");
                        if (pathObj instanceof java.util.List) {
                            java.util.List<byte[]> parts = (java.util.List<byte[]>) pathObj;
                            StringBuilder sb = new StringBuilder();
                            for (byte[] p : parts) {
                                if (sb.length() > 0) {
                                    sb.append("/");
                                }
                                sb.append(new String(p, StandardCharsets.UTF_8));
                            }
                            return sb.toString();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    /**
    * 将对象转换为字符串（支持 byte[] 和 字符串）。
    *
    * @param obj 值对象
    * @return 字符串，不匹配返回 空
     */
    private String str(Object obj) {
        if (obj instanceof byte[]) {
            return new String((byte[]) obj, StandardCharsets.UTF_8);
        }
        if (obj instanceof String) {
            return (String) obj;
        }
        return null;
    }

    /**
    * 取消正在进行的下载。
     */
    public void cancel() {
        this.cancelled = true;
    }

    /**
    * 元数据下载结果。
    * @author CH
    * @since 4.0.0
     */
    public static class MetadataResult {

        /**
        * 是否下载成功。
         */
        public final boolean ok;

        /**
        * 错误信息（失败时非空）。
         */
        public final String error;

        /**
        * 种子名称（成功时非空）。
         */
        public final String name;

        /**
        * 原始元数据字节数组（成功时非空）。
         */
        public final byte[] raw;

        /**
        * 构造下载结果。
        *
        * @param ok    是否成功
        * @param error 错误信息
        * @param name  种子名称
        * @param raw   原始元数据
         */
        private MetadataResult(boolean ok, String error, String name, byte[] raw) {
            this.ok = ok;
            this.error = error;
            this.name = name;
            this.raw = raw;
        }

        /**
        * 创建成功结果。
        *
        * @param name 种子名称
        * @param raw  原始元数据
        * @return MetadataResult 实例
         */
        static MetadataResult ok(String name, byte[] raw) {
            return new MetadataResult(true, null, name, raw);
        }

        /**
        * 创建失败结果。
        *
        * @param error 错误信息
        * @return MetadataResult 实例
         */
        static MetadataResult err(String error) {
            return new MetadataResult(false, error, null, null);
        }
    }
}
