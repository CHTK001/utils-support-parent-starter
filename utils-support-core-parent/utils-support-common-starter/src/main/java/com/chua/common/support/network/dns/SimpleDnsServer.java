package com.chua.common.support.network.dns;

import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import com.chua.common.support.utils.StringUtils;

/**
 * 内置轻量 DNS 服务端（UDP，内存 zone）。
 *
 * <p>零第三方依赖：单线程 UDP 收包，按内存记录表应答 A/AAAA/TXT/CNAME/MX/NS/SRV
 * 等记录类型；支持查询名压缩指针解析与应答指针压缩（0xC00C）。
 * 线程模型：单接收线程 + 每请求同步应答（DNS 查询为短报文，足够轻量）。</p>
 *
 * <p>记录存储为 {@code name|type -> records} 的内存表；同一 name+type 下按 content
 * 去重（RRset 语义：同 content 重复添加幂等，不同 content 共存）。
 * {@link #removeRecord(String, String)} 按 name+type 删除该名下全部记录，
 * {@link #removeRecord(DnsRecord)} 按 content 精确删除单条。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
@Spi(DnsServerProvider.SIMPLE)
public class SimpleDnsServer implements DnsServerProvider {

    /**
     * 记录类型编码
     */
    private static final int TYPE_A = 1;
    private static final int TYPE_NS = 2;
    private static final int TYPE_CNAME = 5;
    private static final int TYPE_SOA = 6;
    private static final int TYPE_PTR = 12;
    private static final int TYPE_MX = 15;
    private static final int TYPE_TXT = 16;
    private static final int TYPE_AAAA = 28;
    private static final int TYPE_SRV = 33;
    private static final int TYPE_ANY = 255;

    /**
     * 类别：IN
     */
    private static final int CLASS_IN = 1;

    /**
     * 响应码：无错误 / 域名不存在 / 未实现
     */
    private static final int RCODE_NOERROR = 0;
    private static final int RCODE_NXDOMAIN = 3;
    private static final int RCODE_NOT_IMP = 4;

    /**
     * 默认 TTL（秒）：配置未显式给 TTL 时的兜底值
     */
    private static final int DEFAULT_TTL = 30;

    private final Map<String, List<DnsRecord>> store = new ConcurrentHashMap<>();
    private final AtomicLong soaSerial = new AtomicLong(System.currentTimeMillis() / 1000L);

    private volatile DnsConfig config = DnsConfig.builder().build();
    private volatile String bindHost = "0.0.0.0";
    private volatile int port = 53;

    private DatagramSocket socket;
    private Thread worker;
    private volatile boolean running;

    /**
     * 实际绑定的端口（启动后有效；未启动返回 -1）
     */
    private volatile int boundPort = -1;

    @Override
    public DnsServerProvider config(DnsConfig config) {
        if (config != null) {
            this.config = config;
        }
        return this;
    }

    @Override
    public DnsServerProvider port(int port) {
        this.port = port;
        return this;
    }

    @Override
    public DnsServerProvider bindHost(String host) {
        if (host != null && !host.trim().isEmpty()) {
            this.bindHost = host.trim();
        }
        return this;
    }

    @Override
    public synchronized DnsServerProvider addRecord(DnsRecord record) {
        if (record == null || isBlank(record.getName()) || isBlank(record.getType())) {
            return this;
        }
        String key = key(record.getName(), record.getType());
        List<DnsRecord> records = store.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        DnsRecord normalized = normalize(record);
        records.removeIf(existing -> sameIdentity(existing, normalized));
        records.add(normalized);
        return this;
    }

    @Override
    public synchronized DnsServerProvider removeRecord(String name, String type) {
        if (isBlank(name) || isBlank(type)) {
            return this;
        }
        store.remove(key(name, type));
        return this;
    }

    /**
     * 按 name + type + content 精确删除单条记录；content 为空时退化为按 name + type 删除。
     */
    @Override
    public synchronized DnsServerProvider removeRecord(DnsRecord record) {
        if (record == null || isBlank(record.getName()) || isBlank(record.getType())) {
            return this;
        }
        if (isBlank(record.getContent())) {
            return removeRecord(record.getName(), record.getType());
        }
        String recordKey = key(record.getName(), record.getType());
        List<DnsRecord> records = store.get(recordKey);
        if (records != null) {
            records.removeIf(existing -> contentOf(existing).equals(contentOf(record)));
            if (records.isEmpty()) {
                store.remove(recordKey);
            }
        }
        return this;
    }

    @Override
    public List<DnsRecord> listRecords() {
        List<DnsRecord> all = new ArrayList<>();
        for (List<DnsRecord> records : store.values()) {
            all.addAll(records);
        }
        return all;
    }

    @Override
    public synchronized void start() throws Exception {
        if (running) {
            return;
        }
        socket = new DatagramSocket(new InetSocketAddress(bindHost, port));
        running = true;
        boundPort = socket.getLocalPort();
        worker = new Thread(this::loop, "dns-server-" + boundPort);
        worker.setDaemon(true);
        worker.start();
        log.info("DNS 服务端已启动: {}:{} (zone={})", bindHost, boundPort, config.getZoneName());
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        Thread thread = worker;
        worker = null;
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
        }
        boundPort = -1;
        log.info("DNS 服务端已停止");
    }

    @Override
    public int getPort() {
        return boundPort;
    }

    /**
     * 接收循环：逐包处理查询，套接字关闭后退出。
     */
    private void loop() {
        byte[] buffer = new byte[1500];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (SocketException e) {
                break;
            } catch (Exception e) {
                if (running) {
                    log.debug("DNS 接收异常: {}", e.getMessage());
                }
                continue;
            }
            respond(packet);
        }
    }

    /**
     * 处理单个查询并应答。
     *
     * @param packet 收到的数据报
     */
    private void respond(DatagramPacket packet) {
        try {
            byte[] query = Arrays.copyOf(packet.getData(), packet.getLength());
            if (query.length < 12) {
                return;
            }
            int id = ((query[0] & 0xFF) << 8) | (query[1] & 0xFF);
            int flags = ((query[2] & 0xFF) << 8) | (query[3] & 0xFF);
            int questionCount = ((query[4] & 0xFF) << 8) | (query[5] & 0xFF);
            if (questionCount < 1) {
                return;
            }
            int[] offset = {12};
            String name = readName(query, offset);
            if (name == null || offset[0] + 4 > query.length) {
                return;
            }
            int queryType = ((query[offset[0]] & 0xFF) << 8) | (query[offset[0] + 1] & 0xFF);
            int queryClass = ((query[offset[0] + 2] & 0xFF) << 8) | (query[offset[0] + 3] & 0xFF);
            boolean recursionDesired = (flags & 0x0100) != 0;

            byte[] response;
            if (queryClass != CLASS_IN) {
                response = buildHeaderOnly(id, recursionDesired, RCODE_NOT_IMP);
            } else {
                response = buildResponse(id, recursionDesired, name, queryType);
            }
            socket.send(new DatagramPacket(response, response.length, packet.getSocketAddress()));
        } catch (Exception e) {
            log.debug("DNS 应答失败: {} - {}", packet.getSocketAddress(), e.getMessage());
        }
    }

    /**
     * 构建查询应答（Question 原样回带 + Answer 区）。
     *
     * @param id 事务 ID
     * @param recursionDesired 是否保留 RD 标志
     * @param name 查询名
     * @param queryType 查询类型
     * @return 应答报文
     */
    private byte[] buildResponse(int id, boolean recursionDesired, String name, int queryType) throws IOException {
        String key = normalize(name);
        boolean nameExists = nameExists(key);
        List<DnsRecord> answers;
        int rcode;
        if (queryType == TYPE_ANY) {
            answers = allRecordsOf(key);
            rcode = answers.isEmpty() && !nameExists ? RCODE_NXDOMAIN : RCODE_NOERROR;
        } else {
            String typeName = typeName(queryType);
            answers = typeName == null ? Collections.emptyList() : lookup(key, typeName);
            if (typeName == null) {
                rcode = RCODE_NOERROR;
            } else if (answers.isEmpty()) {
                rcode = nameExists ? RCODE_NOERROR : RCODE_NXDOMAIN;
            } else {
                rcode = RCODE_NOERROR;
            }
        }

        ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream(512);
        DataOutputStream out = new DataOutputStream(byteBuffer);
        int flags = 0x8000            // QR = response
                | 0x0400              // AA = authoritative
                | (recursionDesired ? 0x0080 : 0)
                | (rcode & 0x0F);
        out.writeShort(id);
        out.writeShort(flags);
        out.writeShort(1);
        out.writeShort(answers.size());
        out.writeShort(0);
        out.writeShort(0);
        // Question
        writeName(out, key);
        out.writeShort(queryType);
        out.writeShort(CLASS_IN);
        // Answers（name 用压缩指针指向 offset 12 的 Question 名）
        for (DnsRecord record : answers) {
            out.writeShort(0xC000 | 12);
            out.writeShort(typeCode(record.getType()));
            out.writeShort(CLASS_IN);
            out.writeInt(record.getTtl() > 0 ? record.getTtl() : DEFAULT_TTL);
            byte[] rdata = rdata(record);
            out.writeShort(rdata.length);
            out.write(rdata);
        }
        return byteBuffer.toByteArray();
    }

    /**
     * 仅构建头部（不支持的查询类别等场景）。
     *
     * @param id 事务 ID
     * @param recursionDesired 是否保留 RD 标志
     * @param rcode 响应码
     * @return 应答报文
     */
    private byte[] buildHeaderOnly(int id, boolean recursionDesired, int rcode) throws IOException {
        ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream(12);
        DataOutputStream out = new DataOutputStream(byteBuffer);
        int flags = 0x8000 | (recursionDesired ? 0x0080 : 0) | (rcode & 0x0F);
        out.writeShort(id);
        out.writeShort(flags);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0);
        return byteBuffer.toByteArray();
    }

    /**
     * 按记录类型编码 RDATA。
     *
     * @param record DNS 记录
     * @return RDATA 字节
     */
    private byte[] rdata(DnsRecord record) throws IOException {
        String type = record.getType().toUpperCase(Locale.ROOT);
        String content = record.getContent() == null ? "" : record.getContent().trim();
        ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream(64);
        DataOutputStream out = new DataOutputStream(byteBuffer);
        switch (type) {
            case "A":
                out.write(ipv4Bytes(content));
                break;
            case "AAAA":
                out.write(ipv6Bytes(content));
                break;
            case "TXT":
                byte[] text = content.getBytes(StandardCharsets.UTF_8);
                // 单字符串 ≤255 字节；超长按 255 分片
                for (int i = 0; i < text.length; i += 255) {
                    int len = Math.min(255, text.length - i);
                    out.writeByte(len);
                    out.write(text, i, len);
                }
                if (text.length == 0) {
                    out.writeByte(0);
                }
                break;
            case "CNAME":
            case "NS":
            case "PTR":
                writeName(out, normalize(content));
                break;
            case "MX":
                out.writeShort(record.getPriority() == null ? 0 : record.getPriority());
                writeName(out, normalize(content));
                break;
            case "SRV":
                int[] parts = parseSrv(content);
                out.writeShort(parts[0]);
                out.writeShort(parts[1]);
                out.writeShort(parts[2]);
                writeName(out, normalize(srvTarget(content)));
                break;
            case "SOA":
                String zone = isBlank(config.getZoneName()) ? normalize(content) : normalize(config.getZoneName());
                writeName(out, "ns1." + zone);
                writeName(out, "admin." + zone);
                out.writeInt((int) (soaSerial.incrementAndGet()));
                out.writeInt(3600);
                out.writeInt(600);
                out.writeInt(604800);
                out.writeInt(DEFAULT_TTL);
                break;
            default:
                byte[] raw = content.getBytes(StandardCharsets.UTF_8);
                out.write(raw, 0, Math.min(raw.length, 255));
                break;
        }
        return byteBuffer.toByteArray();
    }

    /**
     * 解析 SRV content："priority weight port target"。
     *
     * @param content SRV 记录内容
     * @return [priority, weight, port]
     */
    private static int[] parseSrv(String content) {
        int[] parts = {0, 0, 0};
        String[] fields = content.trim().split("\\s+");
        if (fields.length >= 3) {
            try {
                parts[0] = Integer.parseInt(fields[0]);
                parts[1] = Integer.parseInt(fields[1]);
                parts[2] = Integer.parseInt(fields[2]);
            } catch (NumberFormatException ignored) {
                // 保持默认 0
            }
        }
        return parts;
    }

    /**
     * SRV content 的 target 部分（第 4 段起，允许 target 含点）。
     *
     * @param content SRV 记录内容
     * @return target 域名
     */
    private static String srvTarget(String content) {
        String[] fields = content.trim().split("\\s+", 4);
        return fields.length >= 4 ? fields[3] : "";
    }

    /**
     * 写域名（label 长度前缀 + 0 结尾；不使用压缩，长度始终合法）。
     *
     * @param out 输出流
     * @param name 域名（不含末尾点）
     */
    private static void writeName(DataOutputStream out, String name) throws IOException {
        if (StringUtils.isEmpty(name)) {
            out.writeByte(0);
            return;
        }
        String[] labels = name.split("\\.");
        for (String label : labels) {
            if (label.isEmpty()) {
                continue;
            }
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            int len = Math.min(63, bytes.length);
            out.writeByte(len);
            out.write(bytes, 0, len);
        }
        out.writeByte(0);
    }

    /**
     * 读取域名（支持压缩指针 0xC0），offset 回填为「名字之后」的位置。
     *
     * @param message 报文
     * @param offset 起始偏移（单元素数组，方法内回填）
     * @return 域名（不含末尾点）；解析失败返回 null
     */
    private static String readName(byte[] message, int[] offset) {
        StringBuilder name = new StringBuilder();
        int position = offset[0];
        boolean jumped = false;
        int jumps = 0;
        while (true) {
            if (position < 0 || position >= message.length) {
                return null;
            }
            int length = message[position] & 0xFF;
            if (length == 0) {
                position++;
                break;
            }
            if ((length & 0xC0) == 0xC0) {
                if (position + 1 >= message.length) {
                    return null;
                }
                if (!jumped) {
                    offset[0] = position + 2;
                    jumped = true;
                }
                position = ((length & 0x3F) << 8) | (message[position + 1] & 0xFF);
                if (++jumps > 32) {
                    return null;
                }
                continue;
            }
            if (position + 1 + length > message.length) {
                return null;
            }
            name.append(new String(message, position + 1, length, StandardCharsets.US_ASCII)).append('.');
            position += 1 + length;
        }
        if (!jumped) {
            offset[0] = position;
        }
        String text = name.toString();
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    /**
     * 查询记录（精确 name+type）。
     *
     * @param name 归一化名称
     * @param type 记录类型
     * @return 记录列表（可能为空）
     */
    private List<DnsRecord> lookup(String name, String type) {
        List<DnsRecord> records = store.get(key(name, type));
        return records == null ? Collections.emptyList() : new ArrayList<>(records);
    }

    /**
     * 名称下是否存在任意类型记录（用于区分 NXDOMAIN 与空应答）。
     *
     * @param name 归一化名称
     * @return 是否存在
     */
    private boolean nameExists(String name) {
        for (Map.Entry<String, List<DnsRecord>> entry : store.entrySet()) {
            if (entry.getKey().startsWith(name + "|") && !entry.getValue().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 名称下全部类型记录（ANY 查询）。
     *
     * @param name 归一化名称
     * @return 记录列表
     */
    private List<DnsRecord> allRecordsOf(String name) {
        List<DnsRecord> all = new ArrayList<>();
        String prefix = name + "|";
        for (Map.Entry<String, List<DnsRecord>> entry : store.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                all.addAll(entry.getValue());
            }
        }
        return all;
    }

    /**
     * 记录表键：name|type（小写归一）。
     *
     * @param name 记录名
     * @param type 记录类型
     * @return 键
     */
    private static String key(String name, String type) {
        return normalize(name) + "|" + type.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 归一化记录（小写名、剥末尾点、补 TTL）。
     *
     * @param record 原始记录
     * @return 归一化记录
     */
    private static DnsRecord normalize(DnsRecord record) {
        return DnsRecord.builder()
                .name(normalize(record.getName()))
                .type(record.getType().trim().toUpperCase(Locale.ROOT))
                .content(record.getContent())
                .ttl(record.getTtl() > 0 ? record.getTtl() : DEFAULT_TTL)
                .proxied(record.isProxied())
                .priority(record.getPriority())
                .id(record.getId())
                .build();
    }

    /**
     * 同一性判断：name+type+content 均相同才视为同一条记录
     * （RRset 语义：同名同型不同 content 共存，重复发布同 content 幂等覆盖）。
     *
     * @param a 记录一
     * @param b 记录二
     * @return 是否同一身份
     */
    private static boolean sameIdentity(DnsRecord a, DnsRecord b) {
        return key(a.getName(), a.getType()).equals(key(b.getName(), b.getType()))
                && contentOf(a).equals(contentOf(b));
    }

    /**
     * 取记录 content（trim、null 归一为空串），用于同一性比较与精确删除。
     *
     * @param record DNS 记录
     * @return 归一化 content
     */
    private static String contentOf(DnsRecord record) {
        return record.getContent() == null ? "" : record.getContent().trim();
    }

    /**
     * 归一化域名：小写、去空白、剥末尾点。
     *
     * @param name 原始域名
     * @return 归一化域名
     */
    private static String normalize(String name) {
        String text = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        while (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /**
     * 记录类型文本转编码。
     *
     * @param type 类型文本
     * @return 编码；未知返回 -1
     */
    private static int typeCode(String type) {
        switch (type == null ? "" : type.toUpperCase(Locale.ROOT)) {
            case "A": return TYPE_A;
            case "NS": return TYPE_NS;
            case "CNAME": return TYPE_CNAME;
            case "SOA": return TYPE_SOA;
            case "PTR": return TYPE_PTR;
            case "MX": return TYPE_MX;
            case "TXT": return TYPE_TXT;
            case "AAAA": return TYPE_AAAA;
            case "SRV": return TYPE_SRV;
            default: return -1;
        }
    }

    /**
     * 类型编码转文本。
     *
     * @param code 类型编码
     * @return 类型文本；未知返回 null
     */
    private static String typeName(int code) {
        switch (code) {
            case TYPE_A: return "A";
            case TYPE_NS: return "NS";
            case TYPE_CNAME: return "CNAME";
            case TYPE_SOA: return "SOA";
            case TYPE_PTR: return "PTR";
            case TYPE_MX: return "MX";
            case TYPE_TXT: return "TXT";
            case TYPE_AAAA: return "AAAA";
            case TYPE_SRV: return "SRV";
            default: return null;
        }
    }

    /**
     * IPv4 字面量转 4 字节（非法字面量返回 0.0.0.0）。
     *
     * @param content IPv4 文本
     * @return 4 字节
     */
    private static byte[] ipv4Bytes(String content) {
        String[] parts = content.split("\\.");
        if (parts.length != 4) {
            return new byte[4];
        }
        byte[] address = new byte[4];
        try {
            for (int i = 0; i < 4; i++) {
                int value = Integer.parseInt(parts[i].trim());
                address[i] = (byte) (value & 0xFF);
            }
        } catch (NumberFormatException e) {
            return new byte[4];
        }
        return address;
    }

    /**
     * IPv6 字面量转 16 字节（仅接受字面量，不做真实解析；失败返回全零）。
     *
     * @param content IPv6 文本
     * @return 16 字节
     */
    private static byte[] ipv6Bytes(String content) {
        try {
            byte[] address = InetAddress.getByName(content).getAddress();
            return address.length == 16 ? address : new byte[16];
        } catch (UnknownHostException e) {
            return new byte[16];
        }
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }
}
