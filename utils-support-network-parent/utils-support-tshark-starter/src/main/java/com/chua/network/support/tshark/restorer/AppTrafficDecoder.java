package com.chua.network.support.tshark.restorer;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 应用层载荷的通用解码工具：给「自研/私有协议」的还原器共用。
 *
 * <p><b>为什么需要它</b>：微信（MMTLS）、QQ（OICQ）、钉钉（TLS/WSS）这类协议不是
 * 纯文本，一个还原器如果只会「看首字节 + 打印长度」，等于没还原。能做的三件事是：</p>
 * <ol>
 *   <li><b>分帧</b>——很多私有协议用 varint 长度前缀串多条消息，先切出帧才能看结构；</li>
 *   <li><b>取字段</b>——载荷若是 protobuf，按 wire format 走过一遍，把
 *       length-delimited 里的可读串取出来（微信/QQ 的业务层大量是 protobuf）；</li>
 *   <li><b>诚实判定</b>——加密后的字节既分不出帧也解不出串，这时只能给长度与
 *       「已加密」的结论，不能把随机字节硬解释成字段。</li>
 * </ol>
 *
 * <p>所有方法都对畸形输入返回空集合，不抛异常：抓包数据本来就可能是半截的。</p>
 *
 * @author CH
 * @since 4.0.0.60
 */
public final class AppTrafficDecoder {

    /**
     * 单次最多取多少个可读串（还原文本要落库，不能无限长）
     */
    private static final int MAX_STRINGS = 12;

    /**
     * 可读串的最小字节数：更短的片段多是长度/版本号噪声
     */
    private static final int MIN_TEXT_BYTES = 4;

    /**
     * 单个可读串的最大字符数
     */
    private static final int MAX_TEXT_CHARS = 160;

    /**
     * 最多切多少帧
     */
    private static final int MAX_FRAMES = 32;

    /**
     * varint 最多 5 字节（32 位长度）
     */
    private static final int MAX_VARINT_BYTES = 5;

    /**
     * 工具类
     */
    private AppTrafficDecoder() {
    }

    /**
     * 从原始字节里取出连续可读串（UTF-8 严格解码，失败即丢弃）。
     *
     * @param data 载荷
     * @param max  最多返回条数，非正数用默认值
     * @return 可读串列表，可能为空
     */
    public static List<String> strings(byte[] data, int max) {
        List<String> out = new ArrayList<>();
        if (data == null || data.length == 0) {
            return out;
        }
        int limit = max > 0 ? max : MAX_STRINGS;
        int start = -1;
        for (int i = 0; i <= data.length; i++) {
            boolean printable = i < data.length && isPrintable(data[i] & 0xff);
            if (printable && start < 0) {
                start = i;
            } else if (!printable && start >= 0) {
                addText(out, data, start, i - start);
                start = -1;
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 按 varint 长度前缀切帧。
     *
     * <p>只有在<b>整段字节恰好被切完、且至少两帧</b>时才认；否则说明这不是长度前缀协议
     * （多半是加密流），返回空集合让调用方按「无法解析」处理，而不是硬切出一堆垃圾帧。</p>
     *
     * @param data      载荷
     * @param maxFrames 最多帧数
     * @return 帧列表（不含长度前缀），不可分帧返回空
     */
    public static List<byte[]> frames(byte[] data, int maxFrames) {
        List<byte[]> out = new ArrayList<>();
        if (data == null || data.length < 4) {
            return out;
        }
        int limit = maxFrames > 0 ? maxFrames : MAX_FRAMES;
        int pos = 0;
        while (pos < data.length) {
            long[] head = readVarint(data, pos);
            if (head == null) {
                return List.of();
            }
            int length = (int) head[0];
            int next = pos + (int) head[1];
            if (length <= 0 || next + length > data.length) {
                return List.of();
            }
            out.add(Arrays.copyOfRange(data, next, next + length));
            pos = next + length;
            if (out.size() > limit) {
                return List.of();
            }
        }
        return out.size() >= 2 ? out : List.of();
    }

    /**
     * 按 protobuf wire format 取 length-delimited 字段里的可读串。
     *
     * <p>只走一层结构 + 一层嵌套（很多 IM 报文是「外层包一个 bytes，里面才是业务」），
     * 遇到不认识的 wire type 或长度越界就停：宁可少取，也不把二进制当文本。</p>
     *
     * @param data protobuf 字节
     * @param max  最多返回条数
     * @return 可读串列表
     */
    public static List<String> protobufStrings(byte[] data, int max) {
        List<String> out = new ArrayList<>();
        if (data == null || data.length == 0) {
            return out;
        }
        collectProtobuf(data, max > 0 ? max : MAX_STRINGS, out, 0);
        return out;
    }

    /**
     * 可打印字节比例（0~1），用来判断「这段是不是文本」。
     *
     * @param data 字节
     * @return 比例，空数据返回 0
     */
    public static double printableRatio(byte[] data) {
        if (data == null || data.length == 0) {
            return 0D;
        }
        int printable = 0;
        for (byte b : data) {
            if (isPrintable(b & 0xff)) {
                printable++;
            }
        }
        return (double) printable / data.length;
    }

    /**
     * 取协议信息里的 TLS SNI（大小写归一）。
     *
     * @param protocolInfo 还原上下文（tshark 层 + rawData）
     * @return SNI，缺失返回空串
     */
    public static String serverName(Map<String, Object> protocolInfo) {
        Object value = findValue(protocolInfo, "tls.handshake.extensions_server_name");
        return value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 取 HTTP Host（明文 HTTP 的同一用途）。
     *
     * @param protocolInfo 还原上下文
     * @return Host，缺失返回空串
     */
    public static String httpHost(Map<String, Object> protocolInfo) {
        Object value = findValue(protocolInfo, "http.host");
        return value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 把可读串拼成一行（用于还原结果）。
     *
     * @param values 串列表
     * @return 形如 {@code "a" | "b"}；空列表返回空串
     */
    public static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join(" | ", values);
    }

    /**
     * 把「帧数 / 明文字段 / 已加密」结论追加到还原文本。
     *
     * <p>私有协议还原器的收尾动作几乎一样，集中在这里避免每个还原器各写一遍，
     * 也保证「取不到就说已加密」的口径统一。</p>
     *
     * @param sb        输出
     * @param data      载荷
     * @param maxFields 最多几个字段
     */
    public static void appendFields(StringBuilder sb, byte[] data, int maxFields) {
        if (data == null || data.length == 0) {
            return;
        }
        List<byte[]> frames = frames(data, 8);
        if (!frames.isEmpty()) {
            sb.append(", frames=").append(frames.size());
        }
        List<String> values = frames.isEmpty() ? protobufStrings(data, maxFields) : strings(data, maxFields);
        if (!values.isEmpty()) {
            sb.append(", fields=").append(join(values));
        } else {
            sb.append(", payload=encrypted");
        }
    }

    /**
     * 判定域名是否属于给定后缀之一。
     *
     * @param host     主机名（已小写）
     * @param suffixes 域名后缀
     * @return true 表示命中
     */
    public static boolean hostMatches(String host, List<String> suffixes) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        for (String suffix : suffixes) {
            if (host.equals(suffix) || host.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 深度查找字段（复用 TsharkFields 的实现，避免两套查找规则）。
     *
     * @param info 上下文
     * @param key  字段名
     * @return 值，缺失为 null
     */
    private static Object findValue(Map<String, Object> info, String key) {
        if (info == null) {
            return null;
        }
        Object value = info.get(key);
        if (value != null) {
            return value;
        }
        return com.chua.network.support.tshark.TsharkFields.findDeep(info, key);
    }

    /**
     * 递归收集 protobuf 里的可读串。
     *
     * @param data   字节
     * @param max    上限
     * @param out    输出
     * @param depth  当前深度
     */
    private static void collectProtobuf(byte[] data, int max, List<String> out, int depth) {
        if (depth > 2 || out.size() >= max) {
            return;
        }
        int pos = 0;
        while (pos < data.length && out.size() < max) {
            long[] key = readVarint(data, pos);
            if (key == null) {
                return;
            }
            int wireType = (int) (key[0] & 0x07);
            pos += (int) key[1];
            switch (wireType) {
                case 0 -> {
                    long[] value = readVarint(data, pos);
                    if (value == null) {
                        return;
                    }
                    pos += (int) value[1];
                }
                case 1 -> pos += 8;
                case 5 -> pos += 4;
                case 2 -> {
                    long[] size = readVarint(data, pos);
                    if (size == null) {
                        return;
                    }
                    int length = (int) size[0];
                    pos += (int) size[1];
                    if (length < 0 || pos + length > data.length) {
                        return;
                    }
                    byte[] field = Arrays.copyOfRange(data, pos, pos + length);
                    pos += length;
                    String text = strictText(field);
                    if (text != null) {
                        out.add(text);
                    } else if (depth < 2) {
                        collectProtobuf(field, max, out, depth + 1);
                    }
                }
                default -> {
                    return;
                }
            }
            if (pos > data.length) {
                return;
            }
        }
    }

    /**
     * 读一个 varint。
     *
     * @param data 字节
     * @param pos  起点
     * @return {值, 占用字节数}；不合法返回 null
     */
    private static long[] readVarint(byte[] data, int pos) {
        long value = 0L;
        for (int i = 0; i < MAX_VARINT_BYTES; i++) {
            int index = pos + i;
            if (index >= data.length) {
                return null;
            }
            int b = data[index] & 0xff;
            value |= (long) (b & 0x7f) << (7 * i);
            if ((b & 0x80) == 0) {
                return new long[]{value, i + 1L};
            }
        }
        return null;
    }

    /**
     * 一段字节是否整段可读（严格 UTF-8 + 无控制字符）。
     *
     * @param bytes 字节
     * @return 文本，不可读返回 null
     */
    private static String strictText(byte[] bytes) {
        String text = decodeStrict(bytes);
        if (text == null) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch < 0x20 && ch != '\t' && ch != '\r' && ch != '\n') {
                return null;
            }
        }
        return trim(text);
    }

    /**
     * 追加一段可读文本。
     *
     * @param out   输出
     * @param data  字节
     * @param start 起点
     * @param len   长度
     */
    private static void addText(List<String> out, byte[] data, int start, int len) {
        if (len < MIN_TEXT_BYTES) {
            return;
        }
        String text = decodeStrict(Arrays.copyOfRange(data, start, start + len));
        if (text == null) {
            return;
        }
        String value = trim(text);
        if (!value.isEmpty()) {
            out.add(value);
        }
    }

    /**
     * 严格 UTF-8 解码：有非法序列就返回 null（不能把乱码当业务字段）。
     *
     * @param bytes 字节
     * @return 文本或 null
     */
    private static String decodeStrict(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * 去掉首尾空白并限长。
     *
     * @param text 文本
     * @return 处理后的文本
     */
    private static String trim(String text) {
        String value = text.trim();
        if (value.length() > MAX_TEXT_CHARS) {
            return value.substring(0, MAX_TEXT_CHARS) + "…";
        }
        return value;
    }

    /**
     * 可打印字节：可见 ASCII 或 UTF-8 多字节的高位字节。
     *
     * @param b 字节（无符号）
     * @return true 表示可读
     */
    private static boolean isPrintable(int b) {
        return (b >= 0x20 && b < 0x7f) || b >= 0x80;
    }
}
