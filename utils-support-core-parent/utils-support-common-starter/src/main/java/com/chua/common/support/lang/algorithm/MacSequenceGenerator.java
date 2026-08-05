package com.chua.common.support.lang.algorithm;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * MAC 地址自增序列算法 ID 生成器，基于机器 MAC 地址自动生成分布式全局唯一 ID。
 *
 * <p>64 位 Long 型 ID 的位分配如下：</p>
 * <pre>
 * 1 bit sign | 28 bits timestamp | 24 bits macId | 11 bits sequence
 * (始终为 0) | (秒级时间戳) | (MAC 地址哈希) | (自增序号)
 * </pre>
 *
 * <ul>
 * <li>28 位秒级时间戳：相对于纪元起始，可使用约 8.7 年</li>
 * <li>24 位 MAC 地址标识：由机器网卡 MAC 地址经 XOR 折叠得到，最多支持 1600 万节点</li>
 * <li>11 位序列号：每秒最多生成 2048 个 ID</li>
 * <li>无需手动配置节点 ID，自动识别机器唯一标识</li>
 * </ul>
 *
 * <p>MAC 地址获取策略：</p>
 * <ul>
 * <li>优先使用非回环、非虚拟网卡的硬件 MAC 地址</li>
 * <li>自动过滤虚拟机和容器网卡（如 docker、veth 等）</li>
 * <li>若获取失败则退化为随机数 + 主机名哈希的组合</li>
 * </ul>
 *
 * <p>使用方法：</p>
 * <pre>{@code
 * // 创建生成器（自动读取本机 MAC 地址）
 * MacSequenceGenerator generator = new MacSequenceGenerator();
 *
 * // 生成 ID
 * long id = generator.nextId();
 * String idStr = generator.nextIdString();
 * }</pre>
 *
 * @author CH
 * @since 1.0.0
 */
public class MacSequenceGenerator {

 // ==================== 默认位分配 ====================

 /** 时间戳占用位数（28 位，秒级） */
 private static final long DEFAULT_TIMESTAMP_BITS = 28L;

 /** MAC 地址标识占用位数 */
 private static final long DEFAULT_MAC_ID_BITS = 24L;

 /** 序列号占用位数 */
 private static final long DEFAULT_SEQUENCE_BITS = 11L;

 // ==================== 移位偏移量 ====================

 /** 序列号移位偏移 */
 private static final long SEQUENCE_SHIFT = 0L;

 /** MAC 地址标识移位偏移 */
 private final long macIdShift;

 /** 时间戳移位偏移 */
 private final long timestampShift;

 // ==================== 掩码 ====================

 /** 序列号掩码 */
 private final long sequenceMask;

 /** MAC 地址标识掩码 */
 private final long macIdMask;

 /** 时间戳最大值 */
 private final long maxTimestamp;

 // ==================== 默认值 ====================

 /** 默认纪元起始时间（2020-01-01 00:00:00 UTC，单位秒） */
 private static final long DEFAULT_EPOCH = 1577836800L;

 // ==================== 实例状态 ====================

 /** 纪元起始时间（秒） */
 private final long epoch;

 /** MAC 地址标识 */
 private final long macId;

 /** 上次生成 ID 的时间戳（秒） */
 private volatile long lastTimestamp = -1L;

 /** 当前秒内的序列号 */
 private volatile long sequence = 0L;

 /** 序列号同步锁 */
 private final Object lock = new Object();

 // ==================== 构造方法 ====================

 /**
 * 使用默认配置创建 MAC 地址自增序列 ID 生成器
 *
 * <p>自动读取本机 MAC 地址，纪元起始时间为 2020-01-01。</p>
 */
 public MacSequenceGenerator() {
 this(DEFAULT_EPOCH, DEFAULT_MAC_ID_BITS, DEFAULT_TIMESTAMP_BITS, DEFAULT_SEQUENCE_BITS);
 }

 /**
 * 使用自定义位分配创建 MAC 地址自增序列 ID 生成器
 *
 * @param epoch 纪元起始时间（秒）
 * @param macIdBits MAC 地址标识占用位数
 * @param timestampBits 时间戳占用位数
 * @param sequenceBits 序列号占用位数
 * @throws IllegalArgumentException 当参数超出范围时
 */
 public MacSequenceGenerator(long epoch, long macIdBits, long timestampBits, long sequenceBits) {
 // 计算掩码
 this.macIdMask = ~(-1L << macIdBits);
 this.sequenceMask = ~(-1L << sequenceBits);
 this.maxTimestamp = ~(-1L << timestampBits);

 // 计算移位偏移
 this.macIdShift = sequenceBits;
 this.timestampShift = macIdBits + sequenceBits;

 // 获取 MAC 地址标识
 this.macId = resolveMacId(macIdMask);

 this.epoch = epoch;
 }

 // ==================== ID 生成方法 ====================

 /**
 * 生成下一个唯一 ID
 *
 * @return 64 位 Long 型唯一 ID
 * @throws IllegalStateException 如果系统时钟回拨（时钟倒退）
 */
 public long nextId() {
 synchronized (lock) {
 long currentTimestamp = timestamp();

 if (currentTimestamp < lastTimestamp) {
 throw new IllegalStateException(String.format(
 "系统时钟回拨，拒绝生成 ID。上次时间戳: %d, 当前时间戳: %d",
 lastTimestamp, currentTimestamp));
 }

 if (currentTimestamp == lastTimestamp) {
 // 同一秒内，序列号自增
 sequence = (sequence + 1) & sequenceMask;
 if (sequence == 0) {
 // 序列号耗尽，等待下一秒
 currentTimestamp = waitNextSecond(currentTimestamp);
 }
 } else {
 // 不同秒，序列号重置
 sequence = 0L;
 }

 lastTimestamp = currentTimestamp;
 return buildId(currentTimestamp);
 }
 }

 /**
 * 生成下一个唯一 ID 的字符串形式
 *
 * @return 十进制字符串表示的 ID
 */
 public String nextIdString() {
 return String.valueOf(nextId());
 }

 /**
 * 解析 MAC 序列 ID，返回其组成部件信息
 *
 * @param id MAC 序列 ID
 * @return 包含时间戳、MAC 地址标识、序列号的数组 [timestamp, macId, sequence]
 */
 public long[] parse(long id) {
 long sequence = id & sequenceMask;
 long macId = (id >>> macIdShift) & macIdMask;
 long timestamp = (id >>> timestampShift) + epoch;

 return new long[]{timestamp, macId, sequence};
 }

 /**
 * 获取当前机器的 MAC 地址标识值
 *
 * @return MAC 地址标识
 */
 public long getMacId() {
 return macId;
 }

 // ==================== 内部方法 ====================

 /**
 * 组装 64 位 ID
 *
 * @param currentTimestamp 当前时间戳（秒）
 * @return 组装后的 64 位 ID
 */
 private long buildId(long currentTimestamp) {
 long relativeTimestamp = currentTimestamp - epoch;

 if (relativeTimestamp < 0) {
 throw new IllegalStateException(String.format(
 "系统时间早于纪元起始时间。当前时间戳: %d, 纪元: %d",
 currentTimestamp, epoch));
 }

 if (relativeTimestamp > maxTimestamp) {
 throw new IllegalStateException(String.format(
 "时间戳超出最大值，无法生成 ID。相对时间戳: %d, 最大值: %d",
 relativeTimestamp, maxTimestamp));
 }

 return (relativeTimestamp << timestampShift)
 | (macId << macIdShift)
 | sequence;
 }

 /**
 * 获取当前系统时间戳（秒）
 *
 * @return 当前时间戳（秒）
 */
 private long timestamp() {
 return System.currentTimeMillis() / 1000;
 }

 /**
 * 自旋等待直到下一秒
 *
 * @param lastTimestamp 上次生成 ID 的时间戳（秒）
 * @return 下一秒的时间戳（秒）
 */
 private long waitNextSecond(long lastTimestamp) {
 long currentTimestamp = timestamp();
 while (currentTimestamp <= lastTimestamp) {
 currentTimestamp = timestamp();
 }
 return currentTimestamp;
 }

 // ==================== MAC 地址解析 ====================

 /**
 * 解析本机 MAC 地址并折叠到指定掩码范围内
 *
 * <p>依次尝试以下策略：</p>
 * <ol>
 * <li>遍历所有网卡，优先选择第一个非回环、非虚拟网卡的硬件 MAC 地址</li>
 * <li>若获取失败，退化为 {@code hostName.hashCode() ^ randomLong()}</li>
 * </ol>
 *
 * @param mask MAC 标识掩码
 * @return 折叠后的 MAC 地址标识
 */
 private static long resolveMacId(long mask) {
 try {
 Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
 if (interfaces != null) {
 for (NetworkInterface ni : Collections.list(interfaces)) {
 if (isValidInterface(ni)) {
 byte[] mac = ni.getHardwareAddress();
 if (mac != null && mac.length >= 6) {
 return foldMacAddress(mac) & mask;
 }
 }
 }
 }
 } catch (SocketException ignored) {
 // 忽略异常，使用备用策略
 }

 // 备用策略：主机名哈希 + 随机数
 return fallbackMacId(mask);
 }

 /**
 * 判断网卡是否为有效的物理网卡
 *
 * <p>过滤规则：</p>
 * <ul>
 * <li>排除回环接口（loopback）</li>
 * <li>排除虚拟网卡（名称包含 docker、veth、vmnet、vbox、bridger 等关键词）</li>
 * <li>要求网卡必须启用且具有硬件地址</li>
 * </ul>
 *
 * @param ni 网卡接口
 * @return 如果是有效的物理网卡返回 true
 * @throws SocketException 如果访问网卡信息失败
 */
 private static boolean isValidInterface(NetworkInterface ni) throws SocketException {
 if (ni.isLoopback() || !ni.isUp()) {
 return false;
 }

 String name = ni.getName().toLowerCase();
 String displayName = ni.getDisplayName().toLowerCase();

 // 过滤常见虚拟网卡
 if (name.contains("docker") || name.contains("veth") ||
 name.contains("vmnet") || name.contains("vbox") ||
 name.contains("bridge") || name.contains("tun") ||
 name.contains("tap") || name.contains("kube") ||
 name.contains("virtual") || name.contains("vmware")) {
 return false;
 }

 // 过滤常见虚拟网卡（显示名称）
 if (displayName.contains("virtual") || displayName.contains("vmware") ||
 displayName.contains("virtualbox") || displayName.contains("hyper-v")) {
 return false;
 }

 return ni.getHardwareAddress() != null && ni.getHardwareAddress().length >= 6;
 }

 /**
 * XOR 折叠 48 位 MAC 地址到指定宽度
 *
 * <p>将 6 字节 MAC 地址通过 XOR 操作折叠：</p>
 * <pre>
 * macLong = (byte[0] << 40) | (byte[1] << 32) | (byte[2] << 24) |
 * (byte[3] << 16) | (byte[4] << 8) | byte[5]
 * result = (macLong >>> 24) ^ (macLong & 0xFFFFFF)
 * </pre>
 *
 * @param mac MAC 地址字节数组（6 字节）
 * @return 折叠后的 24 位 MAC 标识
 */
 private static long foldMacAddress(byte[] mac) {
 long macLong = ((long) (mac[0] & 0xFF) << 40)
 | ((long) (mac[1] & 0xFF) << 32)
 | ((long) (mac[2] & 0xFF) << 24)
 | ((long) (mac[3] & 0xFF) << 16)
 | ((long) (mac[4] & 0xFF) << 8)
 | ((long) (mac[5] & 0xFF));

 // 将高 24 位与低 24 位 XOR 折叠
 return (macLong >>> 24) ^ (macLong & 0xFFFFFF);
 }

 /**
 * 备用 MAC 标识生成策略
 *
 * <p>当无法获取硬件 MAC 地址时，使用主机名哈希与随机数的组合。</p>
 *
 * @param mask MAC 标识掩码
 * @return 生成的 MAC 标识
 */
 private static long fallbackMacId(long mask) {
 String hostName = System.getProperty("user.name", "unknown");
 int hostHash = hostName.hashCode();
 long randomPart = ThreadLocalRandom.current().nextLong();
 return ((long) hostHash ^ randomPart) & mask;
 }
}
