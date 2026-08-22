package com.chua.common.support.utils;

import com.chua.common.support.lang.algorithm.KafkaSequenceGenerator;
import com.chua.common.support.lang.algorithm.MacSequenceGenerator;
import com.chua.common.support.lang.algorithm.SnowflakeIdGenerator;

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
 * 工具类。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class IdUtils {

    /** 雪花算法 ID 生成器实例 */
    private static final SnowflakeIdGenerator SNOWFLAKE_ID_GENERATOR = new SnowflakeIdGenerator();

    /** Kafka 自增序列 ID 生成器实例 */
    private static final KafkaSequenceGenerator KAFKA_SEQUENCE_GENERATOR = new KafkaSequenceGenerator();

    /** MAC 地址自增序列 ID 生成器实例 */
    private static final MacSequenceGenerator MAC_SEQUENCE_GENERATOR = new MacSequenceGenerator();

    /** 生成同步锁，用于线程安全的时间 ID 生成 */
    private static final Object LOCK = new Object();

    /** 时间 ID 格式化模式 */
    private static final String TIME_FORMAT = "yyyyMMddHHmmss";

    /** 日期序列 ID 日期格式化器 */
    private static final DateTimeFormatter DAILY_SEQ_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 日期序列 ID 最大序列值 */
    private static final long MAX_DAILY_SEQ = 999_999_999L;

    /** 日期序列 ID 生成同步锁 */
    private static final Object DAILY_SEQUENCE_LOCK = new Object();

    /** 日期序列 ID 当前日期（每日重置判断） */
    private static volatile String dailySeqDate = "";

    /** 日期序列 ID 当前序列值 */
    private static long dailySeq = 0;

    /**
     * 创建版本号字符串，将当前数值按指定进制拆分为多段版本号
     *
     * <p>
     * createVersion(3, 10, 11) = 0.1.1
     * </p>
     * <p>
     * createVersion(3, 11, 11) = 0.0.11
     * </p>
     * <p>
     * createVersion(3, 2, 11) = 1.2.1
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
     * UUID
     * <p>
     * b17f24ff026d40949c85a24f4f375d42
     * </p>
     *
     * @return UUID
     */
    public static String simpleUuid() {
        return createSimpleUuid();
    }

    /**
     * UUID
     * <p>
     * {@code createUlid()}
     * </p>
     *
     * @return UUID
     */
    public static String fastUuid() {
        return createSimpleUuid();
    }

    /**
     * 生成基于时间戳的唯一 ID 字符串
     *
     * @return 时间 ID 字符串
     */
    public static String timeId() {
        return createTimeId();
    }

    /**
     * UUID
     * <p>
     * a5c8a5e8-df2b-4706-bea4-08d0939410e3
     * </p>
     *
     * @return UUID
     */
    public static String uuid() {
        return createUuid();
    }

    /**
     * UUID
     * <p>
     * a5c8a5e8-df2b-4706-bea4-08d0939410e3
     * </p>
     *
     * @return UUID
     */
    public static String createUuid() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        UUID uuid = new UUID(random.nextLong(), random.nextLong());
        return uuid.toString();
    }

    /**
     * 创建数据指纹（MD5 后再 Base64 编码）
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
     * MD5
     *
     * @param value 待计算 MD5 的字符串
     * @return MD5 十六进制字符串，计算失败返回 null
     */
    public static String createMd5(final String value) {
        try {
            return DigestUtils.md5(value);
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * UUID
     * <p>
     * b17f24ff026d40949c85a24f4f375d42
     * </p>
     *
     * @return UUID
     */
    public static String createSimpleUuid() {
        return createUuid().replace("-", "");
    }

    /**
     * 创建基于时间戳的唯一 ID 字符串
     *
     * @return 时间 ID 字符串
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
     * 创建指定长度的基于时间戳的唯一 ID 字符串
     *
     * @param length 目标长度
     * @return 指定长度的时间 ID 字符串
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
     * 2 + yyyyMMddHHmmss + ID 4 + 2
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
     * [ ] + yyyyMMddHHmmss + ID 4 + 2
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
     * 生成雪花算法 ID
     *
     * <p>
     * 基于雪花算法生成分布式全局唯一 64 位 Long 型 ID。
     * </p>
     *
     * @return 雪花算法 ID
     */
    public static long createSnowflakeId() {
        return SNOWFLAKE_ID_GENERATOR.nextId();
    }

    /**
     * 生成 Kafka 自增序列 ID
     *
     * <p>
     * 基于 Kafka 自增序列算法生成分布式全局唯一 64 位 Long 型 ID，
     * 支持数据中心维度和批量预生成。
     * </p>
     *
     * @return Kafka 自增序列 ID
     */
    public static long createKafkaSequenceId() {
        return KAFKA_SEQUENCE_GENERATOR.nextId();
    }

    /**
     * 生成 MAC 地址自增序列 ID
     *
     * <p>
     * 基于本机 MAC 地址自动生成分布式全局唯一 64 位 Long 型 ID，
     * 无需手动配置节点 ID，适用于零配置部署场景。
     * </p>
     *
     * @return MAC 地址自增序列 ID
     */
    public static long createMacSequenceId() {
        return MAC_SEQUENCE_GENERATOR.nextId();
    }

    /**
     * 日期序列 ID
     *
     * <p>
     * 默认前缀 "GAT"。
     * </p>
     *
     * @return 日期序列 ID
     */
    public static String dailySequenceId() {
        return createDailySequenceId();
    }

    /**
     * 创建基于日期的自增序列 ID
     *
     * <p>
     * 格式：{@code 前缀 + yyyyMMdd + 9位自增序列}
     * <br>
     * 示例：{@code GAT20260713000000001}
     *
     * <p>
     * 序列每日从 1 开始，线程安全。
     *
     * @return 日期序列 ID
     */
    public static String createDailySequenceId() {
        return createDailySequenceId("GAT");
    }

    /**
     * 创建指定前缀的日期自增序列 ID
     *
     * <p>
     * 使用 {@code 前缀 + yyyyMMdd + 9位自增序列} 格式。
     *
     * @param prefix 前缀，如 "GAT"、"ORD"
     * @return 日期序列 ID
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

    /** CalculateSimpleChecksum */
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
     * 相同数据内容的对象会产生相同的 ID。</p>
     *
     * @param obj 目标对象，可为 {@code null}
     * @return 32 位小写十六进制 MD5 字符串；对象为 {@code null} 时返回 null
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
     * 获取对象的 partial ID（基于部分字段的 MD5）。
     *
     * <p>使用字段名哈希值做确定性采样：将每个字段名做 hash 后对 {@code Integer.MAX_VALUE} 取模，
     * 只保留哈希值落在前 {@code ratio * 100}% 范围内的字段。
     * 采样均匀、无字母序偏置，同一字段集合始终稳定选中。</p>
     *
     * @param obj   目标对象，可为 {@code null}
     * @param ratio 采样比例，范围 (0.0, 1.0]，如 0.6 表示取约 60% 字段
     * @return 32 位小写十六进制 MD5 字符串；对象为 {@code null} 时返回 null
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
            field.setAccessible(true);
            try {
                Object value = field.get(obj);
                sb.append(field.getName()).append('=').append(normalizeValue(value)).append('|');
            } catch (IllegalAccessException e) {
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
     * @return 如果两者类型相同且 partial ID 相等返回 {@code true}
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
     */
    private static String buildSignature(Object obj) {
        List<Field> fields = getAllFields(obj);
        if (fields.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Field field : fields) {
            field.setAccessible(true);
            try {
                Object value = field.get(obj);
                sb.append(field.getName()).append('=').append(normalizeValue(value)).append('|');
            } catch (IllegalAccessException e) {
                sb.append(field.getName()).append("=ERROR|");
            }
        }
        return sb.toString();
    }

    /**
     * 获取类及其所有父类的非静态、非瞬态字段列表，按字段名排序。
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
     * 规范化字段值：数组/List/Collection 展平，null 转为空字符串。
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
