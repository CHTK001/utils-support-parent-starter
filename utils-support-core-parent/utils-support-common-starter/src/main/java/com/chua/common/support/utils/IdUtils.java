package com.chua.common.support.utils;

import com.chua.common.support.lang.algorithm.KafkaSequenceGenerator;
import com.chua.common.support.lang.algorithm.MacSequenceGenerator;
import com.chua.common.support.lang.algorithm.SnowflakeIdGenerator;
import com.chua.common.support.reflection.ReflectUtils;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 标识 工具类，提供多种分布式唯一 标识 生成策略。
 *
 * <p>支持的 ID 生成方式：
 * <ul>
 *   <li>{@link #createUuid()} / {@link #createSimpleUuid()} — UUID（带分隔符 / 无分隔符）</li>
 *   <li>{@link #createUuidv7()} — UUIDv7，时间有序，符合 RFC 9562</li>
 *   <li>{@link #createSnowflakeId()} — 雪花算法 Long 型 ID</li>
 *   <li>{@link #createKafkaSequenceId()} — Kafka 自增序列 ID</li>
 *   <li>{@link #createMacSequenceId()} — 基于 MAC 地址的自增序列 ID</li>
 *   <li>{@link #createTimeId()} / {@link #createTimeId(int)} — 时间戳组合 ID</li>
 *   <li>{@link #createDailySequenceId()} — 日期自增序列 ID（格式：前缀+yyyyMMdd+9位序列）</li>
 *   <li>{@link #getId(Object)} — 基于对象字段值的 MD5 唯一标识</li>
 *   <li>{@link #createMd5(String)} — 通用 MD5 计算</li>
 * </ul>
 *
 * <p>也提供设备编码生成（{@link #createDeviceCode()}）与对象数据比对功能（{@link #isSameData(Object, Object)}）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class IdUtils {

    /**
     * 雪花算法 标识 生成器实例
    */
    private static final SnowflakeIdGenerator SNOWFLAKE_ID_GENERATOR = new SnowflakeIdGenerator();

    /**
     * Kafka 自增序列 标识 生成器实例
    */
    private static final KafkaSequenceGenerator KAFKA_SEQUENCE_GENERATOR = new KafkaSequenceGenerator();

    /**
     * MAC 地址自增序列 标识 生成器实例
    */
    private static final MacSequenceGenerator MAC_SEQUENCE_GENERATOR = new MacSequenceGenerator();

    /**
     * 生成同步锁，用于线程安全的时间 标识 生成
    */
    private static final Object LOCK = new Object();

    /**
     * 时间 标识 格式化模式
    */
    private static final String TIME_FORMAT = "yyyyMMddHHmmss";

    /**
     * 日期序列 标识 日期格式化器
    */
    private static final DateTimeFormatter DAILY_SEQ_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 日期序列 标识 最大序列值
    */
    private static final long MAX_DAILY_SEQ = 999_999_999L;

    /**
     * 日期序列 标识 生成同步锁
    */
    private static final Object DAILY_SEQUENCE_LOCK = new Object();

    /**
     * 日期序列 标识 当前日期（每日重置判断）
    */
    private static volatile String dailySeqDate = "";

    /**
     * 日期序列 标识 当前序列值
    */
    private static long dailySeq = 0;

    /**
     * 创建版本号字符串，将当前数值按指定进制拆分为多段版本号
     *
     * <p>
     * 创建版本(3, 10, 11) = 0.1.1
     * </p>
     * <p>
     * 创建版本(3, 11, 11) = 0.0.11
     * </p>
     * <p>
     * 创建版本(3, 2, 11) = 1.2.1
     * </p>
     *
     * @param versionNumber 版本段数
     * @param maxNumber     每段最大值（进制基数）
     * @param currentNumber 当前数值
     * @return 以点号分隔的版本号字符串
     */
    public static String createVersion(int versionNumber, long maxNumber, long currentNumber) {
        List<Long> temp = new ArrayList<>();
        for (int i = 1; i < versionNumber; i++) {
            temp.add(currentNumber % maxNumber);
            currentNumber = currentNumber / maxNumber;
        }
        temp.add(currentNumber);
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = versionNumber - 1; i > -1; i--) {
            stringBuilder.append(".").append(temp.get(i));

        }
        return stringBuilder.substring(1);
    }

    /**
     * 生成无分隔符 UUID（32位十六进制字符串）。
     *
     * @return 无分隔符 UUID，如 "b17f24ff026d40949c85a24f4f375d42"
     */
    public static String simpleUuid() {
        return createSimpleUuid();
    }

    /**
     * 生成无分隔符 UUID（别名方法）。
     *
     * @return 无分隔符 UUID
     */
    public static String fastUuid() {
        return createSimpleUuid();
    }

    /**
     * 生成基于时间戳的唯一 标识 字符串（别名方法）。
     *
     * @return 时间 标识 字符串
     */
    public static String timeId() {
        return createTimeId();
    }

    /**
     * 生成带分隔符的标准 UUID 字符串。
     *
     * @return UUID 字符串，如 "a5c8a5e8-df2b-4706-bea4-08d0939410e3"
     */
    public static String uuid() {
        return createUuid();
    }

    /**
     * 生成 uuidv7（时间有序 UUID，RFC 9562）。
     *
     * @return UUIDv7 字符串
     */
    public static String uuidv7() {
        return createUuidv7();
    }

    /**
     * 创建 uuidv7（时间有序 UUID，RFC 9562）
     * <p>
     * 前 48 位为 Unix 毫秒时间戳，保证生成的 UUID 在时间上单调递增，
     * 适用于分布式 标识、数据库主键、日志追踪等需要有序唯一标识的场景。
     * </p>
     *
     * @return UUIDv7 字符串
     */
    public static String createUuidv7() {
        long timestamp = System.currentTimeMillis();
 // uuid7 时间戳占高 48 位，左移 16 位到 Most.js.jssig钻头 的高 48 位
        long mostSigBits = (timestamp & 0xFFFFFFFFFFFFL) << 16;
        // 设置版本：bits 48-51 = 7（即 mostSigBits 的第 12-15 位）
        mostSigBits |= (7L << 12);
 // 生成完整随机 long，从中提取 rand_a（12 钻头）和 rand_b（62 钻头）
        long random = ThreadLocalRandom.current().nextLong();
 // rand_a: 取 随机 的低 12 钻头，放到 Most.js.jssig钻头 的低 12 位（版本 已占 4 钻头，rand_a 在其后）
        long randA = random & 0xFFFL;
        mostSigBits |= randA;
 // rand_b: 取 随机 的高 62 钻头，放到 leastsig钻头 的低 62 位
        // variant: bits 64-65 = 10（RFC 4122），即 leastSigBits 的最高两位为 10
        long randB = (random >>> 12) & 0x3FFFFFFFFFFFFFL;
        long leastSigBits = randB | 0x8000000000000000L;
        return new UUID(mostSigBits, leastSigBits).toString();
    }

    /**
     * 生成带分隔符的标准 UUID 字符串（别名方法）。
     *
     * @return UUID 字符串
     */
    public static String createUuid() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        UUID uuid = new UUID(random.nextLong(), random.nextLong());
        return uuid.toString();
    }

    /**
     * 创建数据指纹（MD5 后再 基础64 编码）
     *
     * @return Base64 编码的数据指纹
     */
    public static String createDataFinger() {
        String md5 = createMd5();
        Base64.Encoder encoder = Base64.getEncoder();
        return encoder.encodeToString(md5.getBytes());
    }

    /**
     * MD5
     *
     * @return MD5
     */
    public static String createMd5() {
        return createMd5(createUuid() + System.nanoTime());
    }

    /**
     * 计算字符串的 MD5 哈希值，返回小写十六进制字符串。
     *
     * @param value 待计算 MD5 的字符串
     * @return MD5 十六进制字符串，计算失败返回 空
     */
    public static String createMd5(final String value) {
        try {
            return DigestUtils.md5(value);
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 生成无分隔符 UUID（32位十六进制字符串）。
     *
     * @return 无分隔符 UUID
     */
    public static String createSimpleUuid() {
        return createUuid().replace("-", "");
    }

    /**
     * 创建基于时间戳的唯一 标识 字符串
     *
     * @return 时间 标识 字符串
     */
    public static String createTimeId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        LocalDateTime localDateTime = LocalDateTime.now();
        StringBuilder sb = new StringBuilder();
        sb.append(localDateTime.format(DateTimeFormatter.ofPattern(TIME_FORMAT)));
        synchronized (LOCK) {
            sb.append(System.nanoTime());
            sb.append(StringUtils.padAfter(random.nextInt() + "", 8, '0'));
        }
        return sb.toString();
    }

    /**
     * 创建指定长度的基于时间戳的唯一 标识 字符串
     *
     * @param length 目标长度
     * @return 指定长度的时间 标识 字符串
     */
    public static String createTimeId(int length) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        LocalDateTime localDateTime = LocalDateTime.now();
        StringBuilder sb = new StringBuilder();
        sb.append(localDateTime.format(DateTimeFormatter.ofPattern(TIME_FORMAT)));
        synchronized (LOCK) {
            sb.append(System.nanoTime());
            sb.append(StringUtils.padAfter(random.nextInt() + "", 8, '0'));
        }
        if (length < sb.length()) {
            return sb.substring(0, length);
        }
        return sb + StringUtils.repeat("0", length - sb.length());
    }

    /**
     * 生成设备编码
     *
     * <p>
     * 2 + yyyymmddhhmmss + 标识 4 + 2
     * DV20241220143025123456
     * </p>
     *
     * @return 设备编码字符串
     */
    public static String createDeviceCode() {
        return createDeviceCode("DV");
    }

    /**
     * 生成指定类型的设备编码
     *
     * <p>
     * [ ] + yyyymmddhhmmss + 标识 4 + 2
     * PH20241220143025123456
     * </p>
     *
     * @param deviceType 设备类型前缀，如 "PH"、"PC"、"DV"，最多 4 个字符
     * @return 设备编码字符串
     */
    public static String createDeviceCode(String deviceType) {
        var prefix = (deviceType != null && !deviceType.isEmpty()) ? deviceType.toUpperCase() : "DV";
        if (prefix.length() > 4) {
            prefix = prefix.substring(0, 4);
        }
        var localDateTime = LocalDateTime.now();
        var timePart = localDateTime.format(DateTimeFormatter.ofPattern(TIME_FORMAT));
        var snowflakeId = createSnowflakeId();
        var idPart = String.valueOf(snowflakeId);
        var idSuffix = idPart.length() > 4 ? idPart.substring(idPart.length() - 4)
                : StringUtils.leftPad(idPart, 4, "0");
        var dataPart = prefix + timePart + idSuffix;
        var checksum = calculateSimpleChecksum(dataPart);
        return dataPart + checksum;
    }

    /**
     * 生成雪花算法 标识
     *
     * <p>
     * 基于雪花算法生成分布式全局唯一 64 位 Long 型 标识。
     * </p>
     *
     * @return 雪花算法 标识
     */
    public static long createSnowflakeId() {
        return SNOWFLAKE_ID_GENERATOR.nextId();
    }

    /**
     * 生成 Kafka 自增序列 标识
     *
     * <p>
     * 基于 Kafka 自增序列算法生成分布式全局唯一 64 位 Long 型 标识，
     * 支持数据中心维度和批量预生成。
     * </p>
     *
     * @return Kafka 自增序列 标识
     */
    public static long createKafkaSequenceId() {
        return KAFKA_SEQUENCE_GENERATOR.nextId();
    }

    /**
     * 生成 MAC 地址自增序列 标识
     *
     * <p>
     * 基于本机 MAC 地址自动生成分布式全局唯一 64 位 Long 型 标识，
     * 无需手动配置节点 标识，适用于零配置部署场景。
     * </p>
     *
     * @return MAC 地址自增序列 标识
     */
    public static long createMacSequenceId() {
        return MAC_SEQUENCE_GENERATOR.nextId();
    }

    /**
     * 日期序列 标识
     *
     * <p>
     * 默认前缀 "GAT"。
     * </p>
     *
     * @return 日期序列 标识
     */
    public static String dailySequenceId() {
        return createDailySequenceId();
    }

    /**
     * 创建基于日期的自增序列 标识
     *
     * <p>
     * 格式：{@code 前缀 + yyyyMMdd + 9位自增序列}
     * <br>
     * 示例：{@code GAT20260713000000001}
     *
     * <p>
     * 序列每日从 1 开始，线程安全。
     *
     * @return 日期序列 标识
     */
    public static String createDailySequenceId() {
        return createDailySequenceId("GAT");
    }

    /**
     * 创建指定前缀的日期自增序列 标识
     *
     * <p>
     * 使用 {@code 前缀 + yyyyMMdd + 9位自增序列} 格式。
     *
     * @param prefix 前缀，如 "GAT"、"ORD"
     * @return 日期序列 标识
     */
    public static String createDailySequenceId(String prefix) {
        synchronized (DAILY_SEQUENCE_LOCK) {
            String today = LocalDate.now().format(DAILY_SEQ_DATE_FORMAT);
            if (!today.equals(dailySeqDate)) {
                dailySeqDate = today;
                dailySeq = 0;
            }
            dailySeq++;
            if (dailySeq > MAX_DAILY_SEQ) {
                dailySeq = 1;
            }
            return String.format("%s%s%09d", prefix, today, dailySeq);
        }
    }

    /**
     * calculate简单校验和
     *
     * @param data 数据
     * @return calculate简单校验和的结果
     */
    private static String calculateSimpleChecksum(String data) {
        var hash = 0;
        for (char c : data.toCharArray()) {
            hash = (hash * 31 + c) % 10000;
        }
        return String.format("%02d", Math.abs(hash) % 100);
    }

    // ==================== 对象唯一标识 ====================

    /**
     * 获取对象的唯一标识（基于全部字段值的 MD5）。
     *
     * <p>使用反射提取对象所有非静态字段，按字段名排序后拼接值，计算 MD5 得到稳定标识。
     * 相同数据内容的对象会产生相同的 标识。</p>
     *
     * @param obj 目标对象，可为 {@code null}
     * @return 32 位小写十六进制 MD5 字符串；对象为 {@code null} 时返回 空
     */
    public static String getId(Object obj) {
        if (obj == null) {
            return null;
        }
        String signature = buildSignature(obj);
        if (signature == null || signature.isEmpty()) {
            return Integer.toHexString(obj.hashCode());
        }
        return DigestUtils.md5(signature);
    }

    /**
     * 获取对象的 部分 标识（基于部分字段的 MD5）。
     *
     * <p>使用字段名哈希值做确定性采样：将每个字段名做 hash 后对 {@code Integer.MAX_VALUE} 取模，
     * 只保留哈希值落在前 {@code ratio * 100}% 范围内的字段。
     * 采样均匀、无字母序偏置，同一字段集合始终稳定选中。</p>
     *
     * @param obj   目标对象，可为 {@code null}
     * @param ratio 采样比例，范围 (0.0, 1.0]，如 0.6 表示取约 60% 字段
     * @return 32 位小写十六进制 MD5 字符串；对象为 {@code null} 时返回 空
     * @throws IllegalArgumentException 如果 ratio 不在 (0, 1] 范围内
     */
    public static String getPartialId(Object obj, double ratio) {
        if (ratio <= 0.0 || ratio > 1.0) {
            throw new IllegalArgumentException("ratio must be in (0, 1.0], got: " + ratio);
        }
        if (obj == null) {
            return null;
        }
        List<Field> fields = getAllFields(obj);
        if (fields.isEmpty()) {
            return Integer.toHexString(obj.hashCode());
        }
        int threshold = (int) (ratio * Integer.MAX_VALUE);
        StringBuilder sb = new StringBuilder();
        for (Field field : fields) {
            int h = field.getName().hashCode();
            if (h < 0) {
                h = -h;
            }
            if (h > threshold) {
                continue;
            }
            try {
                Object value = ReflectUtils.getField(obj, field.getName());
                sb.append(field.getName()).append('=').append(normalizeValue(value)).append('|');
            } catch (Exception e) {
                sb.append(field.getName()).append("=ACCESS_ERROR|");
            }
        }
        String signature = sb.toString();
        return DigestUtils.md5(signature);
    }

    /**
     * 判断两个对象的数据是否相同（基于全部字段的 MD5 比对）。
     *
     * @param a 对象 A
     * @param b 对象 B
     * @return 如果两者数据类型相同且 MD5 标识相等返回 {@code true}
     */
    public static boolean isSameData(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (!a.getClass().equals(b.getClass())) {
            return false;
        }
        String idA = getId(a);
        String idB = getId(b);
        return idA != null && idA.equals(idB);
    }

    /**
     * 判断两个对象的部分数据是否相同（基于 60% 字段的 MD5 比对）。
     *
     * @param a     对象 A
     * @param b     对象 B
     * @param ratio 采样比例，如 0.6 表示取 60% 字段
     * @return 如果两者类型相同且 部分 标识 相等返回 {@code true}
     */
    public static boolean isSamePartialData(Object a, Object b, double ratio) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (!a.getClass().equals(b.getClass())) {
            return false;
        }
        String idA = getPartialId(a, ratio);
        String idB = getPartialId(b, ratio);
        return idA != null && idA.equals(idB);
    }

    /**
     * 构建对象的签名字符串（全部字段）。
     * @param obj obj
     * @return 构建签名的结果
     */
    private static String buildSignature(Object obj) {
        List<Field> fields = getAllFields(obj);
        if (fields.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Field field : fields) {
            try {
                Object value = ReflectUtils.getField(obj, field.getName());
                sb.append(field.getName()).append('=').append(normalizeValue(value)).append('|');
            } catch (Exception e) {
                sb.append(field.getName()).append("=ERROR|");
            }
        }
        return sb.toString();
    }

    /**
     * 获取类及其所有父类的非静态、非瞬态字段列表，按字段名排序。
     * @param obj obj
     * @return 获取全部字段的结果
     */
    private static List<Field> getAllFields(Object obj) {
        Class<?> clazz = obj.getClass();
        List<Field> fields = new ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (java.lang.reflect.Modifier.isTransient(field.getModifiers())) {
                    continue;
                }
                if (field.isSynthetic()) {
                    continue;
                }
                fields.add(field);
            }
            clazz = clazz.getSuperclass();
        }
        fields.sort(Comparator.comparing(Field::getName));
        return fields;
    }

    /**
     * 规范化字段值：数组/列表/集合 展平，空 转为空字符串。
     * @param value 值
     * @return normalize值的结果
     */
    private static String normalizeValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof boolean[]) {
            return Arrays.toString((boolean[]) value);
        }
        if (value instanceof byte[]) {
            return Arrays.toString((byte[]) value);
        }
        if (value instanceof char[]) {
            return Arrays.toString((char[]) value);
        }
        if (value instanceof short[]) {
            return Arrays.toString((short[]) value);
        }
        if (value instanceof int[]) {
            return Arrays.toString((int[]) value);
        }
        if (value instanceof long[]) {
            return Arrays.toString((long[]) value);
        }
        if (value instanceof float[]) {
            return Arrays.toString((float[]) value);
        }
        if (value instanceof double[]) {
            return Arrays.toString((double[]) value);
        }
        if (value instanceof Object[]) {
            return Arrays.toString((Object[]) value);
        }
        if (value instanceof Collection) {
            return Arrays.toString(((Collection<?>) value).toArray());
        }
        return value.toString();
    }
}
