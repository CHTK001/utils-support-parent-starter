package com.chua.common.support.host;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 主机身份编解码：把一级不可变信息压成一个可校验的唯一 ID。
 *
 * <p>解决的问题是「一台机器一个稳定标识，且事后能凭这个 ID 反查它是否还是同一台机器」，
 * 而不是「把机器伪装成另一台」——本类只读硬件信息，不做任何写操作。</p>
 *
 * <h2>编码格式</h2>
 * <pre>
 * CH1-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX
 *  │   │
 *  │   └── 32 个 Crockford Base32 字符 = 20 字节载荷
 *  └────── 固定前缀 + 版本号（CH + 1）
 *
 * 载荷 20 字节 = [版本 1B][槽位 0..5 各 3B][校验 1B]
 * 槽位固定按 {@link #SLOTS} 顺序排布，取不到的锚点写全 0，
 * 因此不同机器、不同锚点数量得到的 ID 长度完全一致，可直接按位解析。
 * </pre>
 *
 * <h2>槽位内容</h2>
 * <p>每个槽位存的是锚点值的 3 字节截断 HMAC（不是原值），原因有两点：
 * 一是 ID 要短；二是序列号、UUID 都属于可枚举的取值空间，直接嵌入原值等于把
 * 硬件指纹明文写进授权文件。截断到 24 位后单槽碰撞概率约 1/1677 万，
 * 配合「多槽同时匹配」的判定策略，整体误判率可以忽略。</p>
 *
 * <h2>判定策略</h2>
 * <p>{@link #match(String)} 只比较「ID 里有、当前机器也有」的槽位：
 * <ul>
 *   <li>换网卡 / 改主机名不影响结果（它们本来就不进 ID）；</li>
 *   <li>换一块硬盘只掉一个槽，仍判定为同一台；</li>
 *   <li>换主板会同时掉主板序列号、BIOS 序列号、整机 UUID，
 *       匹配率跌破阈值，判定为不同机器——这正是期望行为。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.50
 */
public final class HostIdentity {

    /**
     * ID 前缀
     */
    private static final String PREFIX = "CH";

    /**
     * 当前编码版本
     */
    private static final int VERSION = 1;

    /**
     * 每个锚点占用的字节数（3 字节 = 24 位）
     */
    private static final int HASH_BYTES = 3;

    /**
     * 参与编码的一级锚点，顺序即槽位顺序。
     *
     * <p>顺序一旦发布就不能调整：调整会让已有 ID 的槽位错位，全部失效。</p>
     */
    public static final List<HostAnchor> SLOTS = Collections.unmodifiableList(Arrays.asList(
            HostAnchor.SYSTEM_UUID,
            HostAnchor.BOARD_SERIAL,
            HostAnchor.BIOS_SERIAL,
            HostAnchor.MACHINE_ID,
            HostAnchor.DISK_SERIAL,
            HostAnchor.CPU_ID));

    /**
     * 载荷总字节数：版本 1 + 槽位 6×3 + 校验 1
     */
    private static final int PAYLOAD_BYTES = 1 + SLOTS.size() * HASH_BYTES + 1;

    /**
     * 校验字节的下标
     */
    private static final int CHECKSUM_INDEX = PAYLOAD_BYTES - 1;

    /**
     * Crockford Base32 字母表：去掉了 I / L / O / U，避免人工抄写歧义
     */
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /**
     * 默认判定阈值：可比槽位中至少 75% 匹配
     */
    public static final double DEFAULT_RATIO = 0.75d;

    /**
     * 默认最少匹配槽位数：单一锚点相同不足以认定同一台
     */
    public static final int DEFAULT_MIN_MATCH = 2;

    /**
     * 计算截断 HMAC 用的固定盐。
     *
     * <p>可用系统属性 {@value #SALT_PROPERTY} 覆盖；覆盖值必须在
     * 生成方与校验方保持一致，否则所有历史 ID 都会判定失败。</p>
     */
    public static final String SALT_PROPERTY = "chua.host.id.salt";

    /**
     * 默认盐值。保持常量不随机，保证不同进程、不同机器计算出同一结果
     */
    private static final String DEFAULT_SALT = "chua-host-identity-v1";

    /**
     * HMAC 算法
     */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * 已知的 OEM 占位串：这些值在同批机器上完全一样，必须当作「取不到」
     */
    private static final Set<String> PLACEHOLDERS = Set.of(
            "TO BE FILLED BY O.E.M.",
            "TO BE FILLED BY OEM",
            "TO BE FILLED BY O.E.M",
            "DEFAULT STRING",
            "SYSTEM SERIAL NUMBER",
            "BASE BOARD SERIAL NUMBER",
            "NONE",
            "N/A",
            "NA",
            "NOT SPECIFIED",
            "NOT AVAILABLE",
            "NOT APPLICABLE",
            "INVALID",
            "UNKNOWN",
            "NULL",
            "0",
            "00000000-0000-0000-0000-000000000000",
            "FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF",
            "03000200-0400-0500-0006-000700080009");

    /**
     * 私有构造，禁止实例化
     */
    private HostIdentity() {
    }

    // ==================== 归一化 ====================

    /**
     * 归一化锚点取值：去空白、压内部空白、转大写，并把 OEM 占位串折算为空串。
     *
     * <p>占位串必须折算掉：{@code To be filled by O.E.M.} 这类值在同型号整批机器上
     * 完全一致，保留它会让这些机器算出同一个 ID，把不同机器判成同一台。</p>
     *
     * <p>最终归一值会去掉<b>全部</b>内部空白：命令输出（如 {@code wmic} 序列号）
     * 常在同一序列号的字符间插入空格或制表符，不去掉会把同一序列号的不同输出
     * 判成不同取值，导致同一台机器生成不同 ID。</p>
     *
     * @param raw 原始取值
     * @return 归一值；取不到或为占位串时返回空串
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        // 先压掉内部连续空白并转大写，用于占位串匹配（占位串本身含空格）
        String collapsed = value.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        if (PLACEHOLDERS.contains(collapsed)) {
            return "";
        }
        // 最终归一值去掉全部内部空白
        value = collapsed.replace(" ", "");
        // 全 0 / 全 F 的序列号（长度 8 以上）同样是占位，按取不到处理
        if (value.length() >= 8 && (isAllOf(value, '0') || isAllOf(value, 'F'))) {
            return "";
        }
        return value;
    }

    /**
     * 判断字符串是否全部由指定字符组成。
     *
     * @param value 取值
     * @param c     目标字符
     * @return 全部相同返回 true
     */
    private static boolean isAllOf(String value, char c) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != c) {
                return false;
            }
        }
        return true;
    }

    // ==================== 生成 ====================

    /**
     * 采集当前机器的一级信息并生成 ID。
     *
     * @return 唯一 ID
     * @throws IllegalStateException 当前环境取不到任何一级锚点时抛出
     */
    public static String generate() {
        return generate(HostInfoRouter.current());
    }

    /**
     * 用给定快照生成 ID。
     *
     * @param facts 主机信息快照，不可为 null
     * @return 唯一 ID
     * @throws IllegalStateException 快照中没有任何一级锚点时抛出
     */
    public static String generate(HostFacts facts) {
        if (facts == null || facts.strongCount() == 0) {
            throw new IllegalStateException("取不到任何一级不可变信息（整机UUID/主板序列号/BIOS序列号/"
                    + "机器标识/磁盘序列号/CPU ID），无法生成主机唯一 ID");
        }
        byte[] payload = new byte[PAYLOAD_BYTES];
        payload[0] = (byte) VERSION;
        String salt = salt();
        for (int i = 0; i < SLOTS.size(); i++) {
            HostAnchor anchor = SLOTS.get(i);
            String value = facts.value(anchor);
            if (value.isEmpty()) {
                continue;
            }
            byte[] digest = hmac(salt, anchor, value);
            System.arraycopy(digest, 0, payload, 1 + i * HASH_BYTES, HASH_BYTES);
        }
        payload[CHECKSUM_INDEX] = checksum(payload);
        return format(payload);
    }

    /**
     * 读取盐值：优先系统属性，其次默认常量。
     *
     * @return 盐
     */
    private static String salt() {
        String custom = System.getProperty(SALT_PROPERTY);
        return custom == null || custom.isBlank() ? DEFAULT_SALT : custom.trim();
    }

    /**
     * 计算某个锚点取值的截断 HMAC。
     *
     * <p>把锚点名一并放进待摘要内容，避免「主板序列号恰好等于磁盘序列号」
     * 这类巧合让两个槽位互相顶替。</p>
     *
     * @param salt   盐
     * @param anchor 锚点
     * @param value  归一后的取值
     * @return 完整摘要
     */
    private static byte[] hmac(String salt, HostAnchor anchor, String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            mac.update(anchor.field().getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '|');
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("主机 ID 摘要计算失败: " + e.getMessage(), e);
        }
    }

    /**
     * 计算载荷校验字节：各字节异或后再取低 8 位，用于识别抄错/截断的 ID。
     *
     * @param payload 载荷（校验位本身按 0 参与）
     * @return 校验字节
     */
    private static byte checksum(byte[] payload) {
        int sum = 0;
        for (int i = 0; i < CHECKSUM_INDEX; i++) {
            sum = (sum + (payload[i] & 0xFF)) & 0xFF;
        }
        // 与前 20 字节的简单异或混合，避免自增序列造成的弱校验
        int mix = payload[0] & 0xFF;
        for (int i = 1; i < CHECKSUM_INDEX; i++) {
            mix ^= payload[i] & 0xFF;
        }
        return (byte) ((sum ^ mix) & 0xFF);
    }

    /**
     * 把载荷编码为带分组前缀的字符串。
     *
     * @param payload 载荷
     * @return 形如 {@code CH1-XXXX-...} 的 ID
     */
    private static String format(byte[] payload) {
        String body = base32Encode(payload);
        StringBuilder sb = new StringBuilder(PREFIX.length() + 1 + VERSION_DIGITS + body.length() + body.length() / 4);
        sb.append(PREFIX).append(VERSION).append('-');
        for (int i = 0; i < body.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append('-');
            }
            sb.append(body.charAt(i));
        }
        return sb.toString();
    }

    /**
     * 版本号在字符串中占的字符数
     */
    private static final int VERSION_DIGITS = 1;

    // ==================== 解析 ====================

    /**
     * 解析 ID 中承载的不可变信息。
     *
     * <p>解析结果只含各锚点的截断摘要，不含原值——原值不可逆，这是设计上有意的：
     * ID 可以公开传递，不泄露硬件序列号。</p>
     *
     * @param id 唯一 ID
     * @return 解析结果；格式非法时 {@link Parsed#valid()} 返回 false
     */
    public static Parsed parse(String id) {
        if (id == null) {
            return Parsed.invalid("ID 为空");
        }
        String compact = id.replace("-", "").replace(" ", "").trim().toUpperCase(Locale.ROOT);
        if (!compact.startsWith(PREFIX)) {
            return Parsed.invalid("缺少前缀 " + PREFIX);
        }
        int versionIndex = PREFIX.length();
        if (compact.length() <= versionIndex) {
            return Parsed.invalid("缺少版本号");
        }
        int version;
        try {
            version = Integer.parseInt(compact.substring(versionIndex, versionIndex + VERSION_DIGITS));
        } catch (NumberFormatException e) {
            return Parsed.invalid("版本号不是数字");
        }
        if (version != VERSION) {
            return Parsed.invalid("不支持的版本 " + version);
        }
        String body = compact.substring(versionIndex + VERSION_DIGITS);
        byte[] payload;
        try {
            payload = base32Decode(body);
        } catch (IllegalArgumentException e) {
            return Parsed.invalid("Base32 解码失败: " + e.getMessage());
        }
        if (payload.length != PAYLOAD_BYTES) {
            return Parsed.invalid("载荷长度不符，期望 " + PAYLOAD_BYTES + " 字节，实际 " + payload.length);
        }
        byte expect = checksum(payload);
        if (expect != payload[CHECKSUM_INDEX]) {
            return Parsed.invalid("校验字节不匹配，ID 可能被截断或抄错");
        }
        Map<HostAnchor, byte[]> slots = new EnumMap<>(HostAnchor.class);
        for (int i = 0; i < SLOTS.size(); i++) {
            int offset = 1 + i * HASH_BYTES;
            byte[] slot = Arrays.copyOfRange(payload, offset, offset + HASH_BYTES);
            if (!isZero(slot)) {
                slots.put(SLOTS.get(i), slot);
            }
        }
        return new Parsed(version, slots, true, "");
    }

    /**
     * 判断字节数组是否全 0。
     *
     * @param slot 槽位
     * @return 全 0 返回 true
     */
    private static boolean isZero(byte[] slot) {
        for (byte b : slot) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * ID 解析结果。
     *
     * @param version 编码版本
     * @param slots   槽位摘要：锚点 → 3 字节截断摘要
     * @param valid   是否解析成功
     * @param reason  失败原因；成功时为空串
     */
    public record Parsed(int version, Map<HostAnchor, byte[]> slots, boolean valid, String reason) {

        /**
         * 构造失败结果。
         *
         * @param reason 失败原因
         * @return 失败结果
         */
        static Parsed invalid(String reason) {
            return new Parsed(-1, Collections.emptyMap(), false, reason);
        }

        /**
         * 获取 ID 中记录的全部锚点。
         *
         * @return 锚点集合
         */
        public Set<HostAnchor> anchors() {
            return slots.isEmpty() ? Collections.emptySet()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(slots.keySet()));
        }

        /**
         * 判断 ID 中是否记录了指定锚点。
         *
         * @param anchor 锚点
         * @return 已记录返回 true
         */
        public boolean has(HostAnchor anchor) {
            return slots.containsKey(anchor);
        }
    }

    // ==================== 校验 ====================

    /**
     * 用默认策略校验 ID 是否仍属当前机器。
     *
     * @param id 唯一 ID
     * @return 判定结果
     */
    public static Match match(String id) {
        return match(id, HostInfoRouter.current(), DEFAULT_RATIO, DEFAULT_MIN_MATCH);
    }

    /**
     * 用指定快照与默认阈值判定。
     *
     * @param id    唯一 ID
     * @param facts 当前机器快照
     * @return 判定结果
     */
    public static Match match(String id, HostFacts facts) {
        return match(id, facts, DEFAULT_RATIO, DEFAULT_MIN_MATCH);
    }

    /**
     * 用指定阈值判定 ID 是否仍属同一台机器。
     *
     * <p>判定只看「ID 与当前机器都拿得到」的锚点：
     * 当前机器缺失某项（例如容器内没有 DMI）不计入分母，
     * 否则容器/精简系统会把合法机器判成换了机器。</p>
     *
     * @param id       唯一 ID
     * @param facts    当前机器快照
     * @param ratio    匹配率下限
     * @param minMatch 匹配数量下限
     * @return 判定结果
     */
    public static Match match(String id, HostFacts facts, double ratio, int minMatch) {
        Parsed parsed = parse(id);
        if (!parsed.valid()) {
            return new Match(false, 0, 0, 0d, parsed.reason(),
                    Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
        }
        if (facts == null) {
            return new Match(false, 0, 0, 0d, "当前机器信息为空",
                    Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
        }
        String salt = salt();
        Set<HostAnchor> matched = new LinkedHashSet<>();
        Set<HostAnchor> mismatched = new LinkedHashSet<>();
        Set<HostAnchor> comparable = new LinkedHashSet<>();
        Set<HostAnchor> absent = new LinkedHashSet<>(parsed.anchors());
        for (Map.Entry<HostAnchor, byte[]> entry : parsed.slots().entrySet()) {
            HostAnchor anchor = entry.getKey();
            String current = facts.value(anchor);
            if (current.isEmpty()) {
                continue;
            }
            absent.remove(anchor);
            comparable.add(anchor);
            byte[] digest = hmac(salt, anchor, current);
            if (Arrays.equals(Arrays.copyOf(digest, HASH_BYTES), entry.getValue())) {
                matched.add(anchor);
            } else {
                mismatched.add(anchor);
            }
        }
        int comparableCount = comparable.size();
        if (comparableCount == 0) {
            return new Match(false, 0, 0, 0d,
                    "ID 中记录的锚点在当前机器上一个都取不到，无法判定",
                    matched, mismatched, absent);
        }
        double actual = (double) matched.size() / comparableCount;
        String reason;
        boolean same = actual >= ratio && matched.size() >= Math.min(minMatch, comparableCount);
        if (same) {
            reason = "匹配 " + matched.size() + "/" + comparableCount + " 项一级信息";
        } else if (matched.isEmpty()) {
            reason = "一级信息全部不一致，不是同一台机器";
        } else {
            reason = "仅匹配 " + matched.size() + "/" + comparableCount + " 项一级信息（阈值 "
                    + ratio + "，至少 " + minMatch + " 项）";
        }
        return new Match(same, matched.size(), comparableCount, actual, reason, matched, mismatched, absent);
    }

    /**
     * 是否仍是同一台机器（默认策略）。
     *
     * @param id 唯一 ID
     * @return 是同一台返回 true
     */
    public static boolean verify(String id) {
        return match(id).same();
    }

    /**
     * 判定结果。
     *
     * @param same       是否同一台机器
     * @param matched    匹配的锚点数量
     * @param comparable 参与比对的锚点数量
     * @param ratio      实际匹配率
     * @param reason     判定说明，便于排查与展示
     * @param matchedAnchors    匹配的锚点
     * @param mismatchedAnchors 不一致的锚点
     * @param absentAnchors     ID 中有、当前机器取不到的锚点
     */
    public record Match(boolean same, int matched, int comparable, double ratio, String reason,
                        Set<HostAnchor> matchedAnchors, Set<HostAnchor> mismatchedAnchors,
                        Set<HostAnchor> absentAnchors) {

        /**
         * 获取不一致锚点的字段名，供日志与接口返回。
         *
         * @return 字段名列表
         */
        public List<String> mismatchedFields() {
            List<String> list = new ArrayList<>(mismatchedAnchors.size());
            for (HostAnchor anchor : mismatchedAnchors) {
                list.add(anchor.field());
            }
            return list;
        }
    }

    // ==================== Base32 ====================

    /**
     * Crockford Base32 编码：每 5 位一组，无填充。
     *
     * @param data 原始字节
     * @return 编码结果
     */
    private static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                sb.append(ALPHABET.charAt((buffer >> bits) & 0x1F));
            }
        }
        if (bits > 0) {
            sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }

    /**
     * Crockford Base32 解码，接受 I/L→1、O→0 的人工抄写变体。
     *
     * @param text 编码文本
     * @return 原始字节
     * @throws IllegalArgumentException 含非法字符时抛出
     */
    private static byte[] base32Decode(String text) {
        int buffer = 0;
        int bits = 0;
        byte[] out = new byte[text.length() * 5 / 8];
        int index = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int value;
            if (c == 'I' || c == 'L') {
                value = 1;
            } else if (c == 'O') {
                value = 0;
            } else {
                value = ALPHABET.indexOf(c);
            }
            if (value < 0) {
                throw new IllegalArgumentException("非法字符 '" + c + "'");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out[index++] = (byte) ((buffer >> bits) & 0xFF);
            }
        }
        return index == out.length ? out : Arrays.copyOf(out, index);
    }
}
