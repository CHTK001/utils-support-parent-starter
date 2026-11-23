package com.chua.common.support.network.ntp;

/**
 * NTP 报文（RFC 2030，SNTPv4，48 字节定长）。
 *
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |LI | VN  |Mode |    Stratum    |     Poll      |   Precision   |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                          Root Delay                           |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                       Root Dispersion                         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                     Reference Identifier                      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                   Reference Timestamp (64)                    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                   Originate Timestamp (64)                    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                    Receive Timestamp (64)                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                   Transmit Timestamp (64)                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 *
 * <p>NTP 64 位时间戳：高 32 位为自 1900-01-01 起的整秒数，
 * 低 32 位为秒的小数部分（2^-32 秒为单位）。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public class NtpMessage {

    /**
     * 报文固定长度（字节）
     */
    public static final int LENGTH = 48;

    /**
     * NTP 纪元偏移：1900-01-01 至 1970-01-01 的秒数
     */
    public static final long NTP_EPOCH_OFFSET = 2_208_988_800L;

    /**
     * 模式：客户机
     */
    public static final int MODE_CLIENT = 3;
    /**
     * 模式：服务器
     */
    public static final int MODE_SERVER = 4;

    /**
     * 闰秒指示（0 无告警，3 未知/告警）
     */
    private int leapIndicator;
    /**
     * 协议版本
     */
    private int version;
    /**
     * 模式（3=客户机，4=服务器，5=广播）
     */
    private int mode;
    /**
     * 层级（0=未知/终止，1=主时钟，2-15=次级）
     */
    private int stratum;
    /**
     * 轮询间隔（2 的幂秒）
     */
    private int poll;
    /**
     * 时钟精度（2 的幂秒，有符号）
     */
    private int precision;
    /**
     * 根延迟（原始 32 位定点数）
     */
    private long rootDelay;
    /**
     * 根弥散（原始 32 位定点数）
     */
    private long rootDispersion;
    /**
     * 参考时钟标识（原始 32 位，通常为 ASCII 4 字符或 IPv4 地址）
     */
    private long referenceId;
    /**
     * 参考时间戳（64 位 NTP 时间戳）
     */
    private long referenceTimestamp;
    /**
     * 原始时间戳（客户机发出请求时刻，64 位 NTP 时间戳）
     */
    private long originateTimestamp;
    /**
     * 接收时间戳（服务器收到请求时刻，64 位 NTP 时间戳）
     */
    private long receiveTimestamp;
    /**
     * 发送时间戳（服务器/客户机发出响应时刻，64 位 NTP 时间戳）
     */
    private long transmitTimestamp;

    /**
     * 将 Unix 毫秒时间戳编码为 64 位 NTP 时间戳。
     *
     * @param epochMillis Unix 毫秒时间戳
     * @return NTP 64 位时间戳
     */
    public static long toNtpTimestamp(long epochMillis) {
        long seconds = epochMillis / 1000L + NTP_EPOCH_OFFSET;
        long millis = epochMillis % 1000L;
        long fraction = (millis << 32) / 1000L;
        return (seconds << 32) | (fraction & 0xFFFFFFFFL);
    }

    /**
     * 将 64 位 NTP 时间戳解码为 Unix 毫秒时间戳。
     *
     * @param ntpTimestamp NTP 64 位时间戳
     * @return Unix 毫秒时间戳
     */
    public static long toEpochMillis(long ntpTimestamp) {
        long seconds = (ntpTimestamp >>> 32) - NTP_EPOCH_OFFSET;
        long fraction = ntpTimestamp & 0xFFFFFFFFL;
        long millis = (fraction * 1000L) >>> 32;
        return seconds * 1000L + millis;
    }

    /**
     * 编码为 48 字节报文。
     *
     * @return NTP 报文字节
     */
    public byte[] encode() {
        byte[] buf = new byte[LENGTH];
        buf[0] = (byte) (((leapIndicator & 0x3) << 6) | ((version & 0x7) << 3) | (mode & 0x7));
        buf[1] = (byte) stratum;
        buf[2] = (byte) poll;
        buf[3] = (byte) precision;
        writeInt32(buf, 4, rootDelay);
        writeInt32(buf, 8, rootDispersion);
        writeInt32(buf, 12, referenceId);
        writeInt64(buf, 16, referenceTimestamp);
        writeInt64(buf, 24, originateTimestamp);
        writeInt64(buf, 32, receiveTimestamp);
        writeInt64(buf, 40, transmitTimestamp);
        return buf;
    }

    /**
     * 从字节解码 NTP 报文。
     *
     * @param data 报文字节（长度至少 48）
     * @return 解码后的报文
     */
    public static NtpMessage decode(byte[] data) {
        if (data == null || data.length < LENGTH) {
            throw new IllegalArgumentException("NTP 报文长度不足 48 字节: "
                    + (data == null ? 0 : data.length));
        }
        NtpMessage message = new NtpMessage();
        message.leapIndicator = (data[0] >> 6) & 0x3;
        message.version = (data[0] >> 3) & 0x7;
        message.mode = data[0] & 0x7;
        message.stratum = data[1] & 0xFF;
        message.poll = data[2] & 0xFF;
        message.precision = data[3];
        message.rootDelay = readInt32(data, 4);
        message.rootDispersion = readInt32(data, 8);
        message.referenceId = readInt32(data, 12);
        message.referenceTimestamp = readInt64(data, 16);
        message.originateTimestamp = readInt64(data, 24);
        message.receiveTimestamp = readInt64(data, 32);
        message.transmitTimestamp = readInt64(data, 40);
        return message;
    }

    /**
     * 参考时钟标识的 ASCII 形式（非 ASCII 字节以点号替代）。
     *
     * @return 可读标识
     */
    public String referenceIdText() {
        StringBuilder text = new StringBuilder(4);
        for (int i = 3; i >= 0; i--) {
            int b = (int) ((referenceId >>> (i * 8)) & 0xFF);
            text.append(b >= 32 && b < 127 ? (char) b : '.');
        }
        return text.toString();
    }

    private static void writeInt32(byte[] buf, int offset, long value) {
        buf[offset] = (byte) (value >>> 24);
        buf[offset + 1] = (byte) (value >>> 16);
        buf[offset + 2] = (byte) (value >>> 8);
        buf[offset + 3] = (byte) value;
    }

    private static long readInt32(byte[] buf, int offset) {
        return ((buf[offset] & 0xFFL) << 24) | ((buf[offset + 1] & 0xFFL) << 16)
                | ((buf[offset + 2] & 0xFFL) << 8) | (buf[offset + 3] & 0xFFL);
    }

    private static void writeInt64(byte[] buf, int offset, long value) {
        for (int i = 0; i < 8; i++) {
            buf[offset + i] = (byte) (value >>> (56 - i * 8));
        }
    }

    private static long readInt64(byte[] buf, int offset) {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (buf[offset + i] & 0xFFL);
        }
        return value;
    }

    public int getLeapIndicator() {
        return leapIndicator;
    }

    public void setLeapIndicator(int leapIndicator) {
        this.leapIndicator = leapIndicator;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public int getMode() {
        return mode;
    }

    public void setMode(int mode) {
        this.mode = mode;
    }

    public int getStratum() {
        return stratum;
    }

    public void setStratum(int stratum) {
        this.stratum = stratum;
    }

    public int getPoll() {
        return poll;
    }

    public void setPoll(int poll) {
        this.poll = poll;
    }

    public int getPrecision() {
        return precision;
    }

    public void setPrecision(int precision) {
        this.precision = precision;
    }

    public long getRootDelay() {
        return rootDelay;
    }

    public void setRootDelay(long rootDelay) {
        this.rootDelay = rootDelay;
    }

    public long getRootDispersion() {
        return rootDispersion;
    }

    public void setRootDispersion(long rootDispersion) {
        this.rootDispersion = rootDispersion;
    }

    public long getReferenceId() {
        return referenceId;
    }

    public void setReferenceId(long referenceId) {
        this.referenceId = referenceId;
    }

    public long getReferenceTimestamp() {
        return referenceTimestamp;
    }

    public void setReferenceTimestamp(long referenceTimestamp) {
        this.referenceTimestamp = referenceTimestamp;
    }

    public long getOriginateTimestamp() {
        return originateTimestamp;
    }

    public void setOriginateTimestamp(long originateTimestamp) {
        this.originateTimestamp = originateTimestamp;
    }

    public long getReceiveTimestamp() {
        return receiveTimestamp;
    }

    public void setReceiveTimestamp(long receiveTimestamp) {
        this.receiveTimestamp = receiveTimestamp;
    }

    public long getTransmitTimestamp() {
        return transmitTimestamp;
    }

    public void setTransmitTimestamp(long transmitTimestamp) {
        this.transmitTimestamp = transmitTimestamp;
    }
}
