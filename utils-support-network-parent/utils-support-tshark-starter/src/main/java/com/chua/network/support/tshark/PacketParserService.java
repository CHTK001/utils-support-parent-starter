package com.chua.network.support.tshark;

import com.chua.common.support.network.protocol.ProtocolRestorer;
import com.chua.common.support.spi.ServiceProvider;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * tshark JSON 数据包解析工具。
 *
 * <p>负责将 TShark 命令行输出的 JSON 格式单包数据解析为结构化对象。</p>
 * <p>支持帧层、IP层、传输层协议检测，以及 TCP flags/lifecycle 计算。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class PacketParserService {

    /**
     * JSON 对象映射器（线程安全，可共享）
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 协议还原器 SPI（懒加载）
     */
    private static final List<ProtocolRestorer> RESTORERS;

    static {
        List<ProtocolRestorer> restorers = new ArrayList<>(ServiceProvider.of(ProtocolRestorer.class).collect());
        // 还原器按 getPriority() 升序尝试：SPI 登记顺序不代表协议优先级，
        // 否则字节嗅探型还原器（如 telnet）会抢走后续专有协议的报文
        restorers.sort(Comparator.comparingInt(ProtocolRestorer::getPriority));
        RESTORERS = List.copyOf(restorers);
    }

    /**
     * 协议：HTTP
     */
    private static final String PROTOCOL_HTTP = "HTTP";

    /**
     * 协议：TLS
     */
    private static final String PROTOCOL_TLS = "TLS";

    /**
     * 协议：DNS
     */
    private static final String PROTOCOL_DNS = "DNS";

    /**
     * 协议：TCP
     */
    private static final String PROTOCOL_TCP = "TCP";

    /**
     * 协议：UDP
     */
    private static final String PROTOCOL_UDP = "UDP";

    /**
     * 协议：ICMP
     */
    private static final String PROTOCOL_ICMP = "ICMP";

    /**
     * 协议：ARP
     */
    private static final String PROTOCOL_ARP = "ARP";

    /**
     * 协议：其他
     */
    private static final String PROTOCOL_OTHER = "OTHER";

    /**
     * TCP flag：SYN
     */
    private static final String FLAG_SYN = "SYN ";

    /**
     * TCP flag：ACK
     */
    private static final String FLAG_ACK = "ACK ";

    /**
     * TCP flag：FIN
     */
    private static final String FLAG_FIN = "FIN ";

    /**
     * TCP flag：RST
     */
    private static final String FLAG_RST = "RST ";

    /**
     * TCP flag：PSH
     */
    private static final String FLAG_PSH = "PSH ";

    /**
     * TCP flag：URG
     */
    private static final String FLAG_URG = "URG ";

    /**
     * 应用层协议层名表：tshark 层名 -&gt; 协议名称。
     *
     * <p>表内顺序即协议识别优先级，越具体的协议越靠前。tshark 已完成 dissector 分析的层
     * 比字节嗅探更可信，因此协议名判定与还原器选型都以本表为准。新增专有协议还原器时
     * 在此登记层名即可，无需改动解析逻辑。</p>
     */
    private static final Map<String, String> APPLICATION_LAYERS;

    /**
     * 还原器层名别名表：还原器协议名 -&gt; 额外的 tshark 层名。
     *
     * <p>仅登记协议名与 tshark 层名不一致的情况（如 HTTPS 还原器对应 {@code tls} 层），
     * 未登记的还原器按 {@link ProtocolRestorer#getProtocolName()} 直接匹配层名。</p>
     */
    private static final Map<String, List<String>> RESTORER_LAYER_ALIASES;

    static {
        Map<String, String> layers = new LinkedHashMap<>();
        layers.put("http2", "HTTP2");
        layers.put("websocket", "WEBSOCKET");
        layers.put(PROTOCOL_HTTP.toLowerCase(), PROTOCOL_HTTP);
        layers.put(PROTOCOL_TLS.toLowerCase(), PROTOCOL_TLS);
        layers.put("mqtt", "MQTT");
        layers.put("kafka", "KAFKA");
        layers.put("redis", "REDIS");
        layers.put("amqp", "AMQP");
        layers.put(PROTOCOL_DNS.toLowerCase(), PROTOCOL_DNS);
        layers.put("mdns", "MDNS");
        layers.put("mysql", "MYSQL");
        layers.put("pgsql", "POSTGRESQL");
        layers.put("mongodb", "MONGODB");
        layers.put("smtp", "SMTP");
        layers.put("imap", "IMAP");
        layers.put("pop", "POP");
        layers.put("ftp", "FTP");
        layers.put("ssh", "SSH");
        layers.put("telnet", "TELNET");
        layers.put("ntp", "NTP");
        layers.put("dhcp", "DHCP");
        layers.put("sip", "SIP");
        layers.put("rtsp", "RTSP");
        layers.put("rtp", "RTP");
        layers.put("modbus", "MODBUS");
        layers.put("coap", "COAP");
        layers.put("smb", "SMB");
        // 以下为无专用还原器、但业务侧需要识别的常见应用层协议。
        // 只登记 tshark 已 dissect 出的层名，不做字节嗅探，避免误判。
        layers.put("quic", "QUIC");
        layers.put("dhcpv6", "DHCPV6");
        layers.put("lldp", "LLDP");
        layers.put("snmp", "SNMP");
        layers.put("ldap", "LDAP");
        layers.put("kerberos", "KERBEROS");
        layers.put("radius", "RADIUS");
        layers.put("socks", "SOCKS");
        layers.put("rdp", "RDP");
        layers.put("smb2", "SMB2");
        layers.put("netbios", "NETBIOS");
        layers.put("irc", "IRC");
        layers.put("xmpp", "XMPP");
        layers.put("stun", "STUN");
        layers.put("bgp", "BGP");
        layers.put("rip", "RIP");
        layers.put("eap", "EAP");
        layers.put("pop3", "POP3");
        APPLICATION_LAYERS = Collections.unmodifiableMap(layers);

        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("https", List.of(PROTOCOL_TLS.toLowerCase()));
        aliases.put("postgresql", List.of("pgsql"));
        aliases.put("email", List.of("smtp", "imap", "pop"));
        RESTORER_LAYER_ALIASES = Collections.unmodifiableMap(aliases);
    }

    /**
     * 工具类
     */
    private PacketParserService() {
  // 工具 类
    }

    /**
     * 流式读取时轮询数据的暂停间隔（毫秒）
     */
    private static final long PAUSE_INTERVAL_MILLIS = 20L;

    /**
     * 短暂暂停，被中断时返回 false 以便调用方立即退出等待。
     *
     * <p>不用 {@code Thread.sleep} 直接调用是因为它抛受检异常，
     * 而这里的语义是"没等到就退出"，吞掉中断并返回 false 即可，
     * 线程的中断状态由上层停止逻辑处理。</p>
     *
     * @param millis 暂停毫秒数
     * @return 正常暂停完成返回 true，被中断返回 false
     */
    private static boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 解析 tshark 单条 JSON 数据为结构化对象。
     *
     * @param jsonLine tshark 输出的单包 JSON 字符串
     * @return 解析后的数据包记录，解析失败返回 空
     */
    public static PacketRecord parse(String jsonLine) {
        try {
            return parseNode(OBJECT_MAPPER.readTree(jsonLine));
        } catch (Exception e) {
            log.debug("Failed to parse packet: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 直接从已解析的 JSON 节点构造数据包记录。
     *
     * <p>本方法是 {@link #parse(String)} 与 {@link #parseAll(InputStream)} 的共同入口。
     * 相比"先把对象转回字符串再解析"的写法，这里省掉一次完整的序列化与反序列化：
     * 抓包场景下每包都走一次往返，等于把 JSON 解析成本翻倍。</p>
     *
     * @param node 单包 JSON 节点，可为空
     * @return 解析后的数据包记录，节点结构不合法返回 {@code null}
     */
    @Nullable
    public static PacketRecord parseNode(@Nullable JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        try {
            Map<String, Object> layers = toMap(node);
            if (layers == null || layers.isEmpty()) {
                return null;
            }

            String sourceIp = extractIp(layers, "src");
            String destinationIp = extractIp(layers, "dst");
            Integer sourcePort = extractPort(layers, "src");
            Integer destinationPort = extractPort(layers, "dst");
            String protocol = detectProtocol(layers);
            Integer packetLength = extractLength(layers);
            String info = buildInfo(sourceIp, sourcePort, destinationIp, destinationPort, protocol, layers);
            String lifecycleJson = buildLifecycleJson(layers);
            PacketMeta meta = buildMeta(layers, protocol);
            byte[] payload = TsharkFields.payload(layers);

            List<String> details = restoreAll(layers);
            String restoredText = details.isEmpty() ? null : details.get(0);

            return new PacketRecord(sourceIp, destinationIp, sourcePort, destinationPort, protocol,
                    packetLength, info, node.toString(), lifecycleJson, restoredText, meta,
                    payload == null ? new byte[0] : payload, details);
        } catch (Exception e) {
            log.debug("Failed to build packet record: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 以流式方式解析 tshark 的 {@code -T json} 输出。
     *
     * <p><b>为什么必须流式</b>：tshark 的 JSON 输出是一个包含全部报文的<b>数组</b>。
     * 传统做法是把整个输出读进内存再解析，峰值占用可达 JSON 文本的两倍
     * （字符串缓冲 + 解析树），几百 MB 的抓包就会打满堆。
     * 本方法用 Jackson 的流式游标逐个读取数组元素，内存占用只与<b>单包</b>大小相关，
     * 与报文总数无关。</p>
     *
     * <p><b>用于实时抓包</b>：抓包进程的 stdout 就是一个持续增长的 JSON 数组
     * （{@code [ {包1}, {包2} ...}），流式游标在读到第一包时即可返回，
     * 不必等数组闭合。因此可以一边抓一边解析，无需落盘再回读。</p>
     *
     * @param inputStream tshark 输出流，方法内不关闭，由调用方管理
     * @return 解析出的数据包记录列表
     * @throws IOException 读取或解析失败
     */
    @Nonnull
    public static List<PacketRecord> parseAll(@Nonnull InputStream inputStream) throws IOException {
        List<PacketRecord> records = new ArrayList<>();
        try (JsonParser parser = OBJECT_MAPPER.getFactory().createParser(inputStream)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                log.debug("tshark 输出不是 JSON 数组，忽略");
                return records;
            }
            // readTree 消费完一个对象后会把 currentToken 清空（置 null），
            // 因此下一轮必须先 nextToken() 才能拿到下一个对象的起始标记。
            // 数组结束时 nextToken() 返回 null，循环自然终止。
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                PacketRecord record = parseNode(OBJECT_MAPPER.readTree(parser));
                if (record != null) {
                    records.add(record);
                }
            }
        }
        return records;
    }

    /**
     * 以流式方式解析 {@code -T json} 输出的文本。
     *
     * @param json tshark 输出的完整 JSON 文本
     * @return 解析出的数据包记录列表
     * @throws IOException 解析失败
     */
    @Nonnull
    public static List<PacketRecord> parseAll(@Nonnull String json) throws IOException {
        return parseAll(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }
    /**
     * 增量读取流式解析结果，供实时抓包边到边处理。
     *
     * <p>{@link #parseAll(InputStream)} 会一直读到数组闭合，对实时抓包不适用
     * （抓包进程不会主动结束）。本类把解析放到独立后台线程，
     * 解析出的报文放入有界队列，由调用方按需 {@link #poll(long)} 取走。</p>
     *
     * <h3>为什么必须后台线程 + 队列</h3>
     * <p>不能用"先看 {@code InputStream.available()} 再决定是否解析"来判断是否还有数据：
     * JSON 游标会<b>主动预读</b>并把字节读进自己的缓冲区，
     * 于是底层流已无字节可读、{@code available()} 返回 0，
     * 而游标内部其实还攥着半个甚至多个完整报文。
     * 基于 {@code available()} 的探测会周期性地把"其实有数据"误判成"没数据"，
     * 表现为抓包看起来时断时续。</p>
     * <p>而直接在调用线程里阻塞式 {@code nextToken()} 又无法设上界——
     * 暂停与停止会因此失去响应。两者结合：只有后台线程阻塞读取（有界的是队列，不是游标），
     * 调用线程只在队列上等待，超时精确可控。</p>
     *
     * <h3>背压</h3>
     * <p>队列有界。消费不过来时丢弃最旧的报文并计数（{@link #droppedPackets()}），
     * 而不是让队列无限增长把堆吃光——抓包场景下"丢旧包"比"打满堆"可接受得多。</p>
     *
     * <p>tshark 需配合 {@code -l}（逐包刷新标准输出）使用，
     * 否则其输出会按块缓冲，读侧将长时间收不到数据。</p>
     */
    public static final class StreamReader implements Closeable {

        /**
         * 队列默认容量
         */
        public static final int DEFAULT_QUEUE_CAPACITY = 4096;

        /**
         * 单次 poll 默认最多返回的报文数
         */
        public static final int DEFAULT_MAX_BATCH = 512;

        /**
         * tshark 输出流
         */
        private final InputStream inputStream;

        /**
         * 已解析报文队列
         */
        private final BlockingQueue<PacketRecord> queue;

        /**
         * 单次 poll 的批量上限
         */
        private final int maxBatch;

        /**
         * 因队列满而丢弃的报文数
         */
        private final AtomicLong dropped = new AtomicLong();

        /**
         * 后台解析线程的错误
         */
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        /**
         * 是否已读到流末尾
         */
        private final AtomicBoolean finished = new AtomicBoolean();

        /**
         * 后台解析线程
         */
        private volatile Thread worker;

        /**
         * 构造流式读取器。
         *
         * @param inputStream tshark 输出流
         * @throws IOException 无
         */
        public StreamReader(@Nonnull InputStream inputStream) throws IOException {
            this(inputStream, DEFAULT_QUEUE_CAPACITY, DEFAULT_MAX_BATCH);
        }

        /**
         * 构造流式读取器。
         *
         * @param inputStream   tshark 输出流
         * @param queueCapacity 队列容量，非正值取默认值
         * @param maxBatch      单次 poll 的批量上限，非正值取默认值
         */
        public StreamReader(@Nonnull InputStream inputStream, int queueCapacity, int maxBatch) {
            this.inputStream = inputStream;
            this.queue = new LinkedBlockingQueue<>(queueCapacity > 0 ? queueCapacity : DEFAULT_QUEUE_CAPACITY);
            this.maxBatch = maxBatch > 0 ? maxBatch : DEFAULT_MAX_BATCH;
        }

        /**
         * 读取当前已到达的报文。
         *
         * @param waitMillis 无新报文时的最长等待（毫秒），0 表示立即返回
         * @return 新读到的报文列表，可能为空
         */
        @Nonnull
        public List<PacketRecord> poll(long waitMillis) {
            List<PacketRecord> records = new ArrayList<>();
            ensureStarted();
            PacketRecord first = queue.poll();
            if (first == null && waitMillis > 0) {
                try {
                    first = queue.poll(waitMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (first == null) {
                return records;
            }
            records.add(first);
            queue.drainTo(records, maxBatch - 1);
            return records;
        }

        /**
         * 因队列满而丢弃的报文数。
         *
         * @return 累计丢弃报文数
         */
        public long droppedPackets() {
            return dropped.get();
        }

        /**
         * 后台解析线程的错误。
         *
         * @return 异常，尚未出错返回 {@code null}
         */
        @Nullable
        public Throwable failure() {
            return failure.get();
        }

        /**
         * 流是否已读完（进程退出或数组闭合）。
         *
         * @return 已读完返回 true
         */
        public boolean finished() {
            return finished.get();
        }

        @Override
        public void close() throws IOException {
            finished.set(true);
            Thread current = worker;
            if (current != null) {
                current.interrupt();
            }
            queue.clear();
            inputStream.close();
        }

        /**
         * 首次使用时启动后台解析线程。
         */
        private void ensureStarted() {
            if (worker != null) {
                return;
            }
            synchronized (this) {
                if (worker != null) {
                    return;
                }
                Thread thread = new Thread(this::pump, "TsharkJsonPump");
                thread.setDaemon(true);
                worker = thread;
                thread.start();
            }
        }

        /**
         * 后台解析循环：读满整个 JSON 数组，逐包入队。
         */
        private void pump() {
            try (JsonParser parser = OBJECT_MAPPER.getFactory().createParser(inputStream)) {
                if (parser.nextToken() != JsonToken.START_ARRAY) {
                    log.debug("tshark 输出不是 JSON 数组，停止解析");
                    return;
                }
                while (parser.nextToken() == JsonToken.START_OBJECT) {
                    PacketRecord record = parseNode(OBJECT_MAPPER.readTree(parser));
                    if (record != null) {
                        offer(record);
                    }
                }
            } catch (Exception e) {
                if (!finished.get()) {
                    failure.set(e);
                }
            } finally {
                finished.set(true);
            }
        }

        /**
         * 入队；队列满时丢弃最旧的一条腾位置。
         *
         * @param record 报文
         */
        private void offer(@Nonnull PacketRecord record) {
        if (queue.offer(record)) {
            return;
        }
        if (queue.poll() != null) {
            dropped.incrementAndGet();
        }
        if (!queue.offer(record)) {
            dropped.incrementAndGet();
        }
        }
    }

    // ==================== 协议还原 ====================

    /**
     * 对<b>没有 tshark 层信息</b>的原始字节做协议还原。
     *
     * <p>供 TCP 流重组使用：重组产出的字节是完整应用层消息，
     * 但它来自多个 TCP 段，没有对应的单包层结构可查，
     * 只能按"全部还原器按优先级字节嗅探"的退化模式处理。
     * 还原器所需的 {@code rawData} 由此处注入，其余字段缺省——
     * 大多数还原器只依赖 {@code rawData}，
     * 少数依赖层字段的（如链路层的 ARP/ICMP）会自动跳过。</p>
     *
     * @param payload 应用层字节
     * @return 还原结果列表，按优先级升序；无结果返回空列表
     */
    @Nonnull
    public static List<String> restoreBytes(@Nullable byte[] payload) {
        if (payload == null || payload.length == 0 || RESTORERS.isEmpty()) {
            return List.of();
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("rawData", payload);
        List<String> results = new ArrayList<>();
        for (ProtocolRestorer restorer : RESTORERS) {
            try {
                if (!restorer.canRestore(context, payload)) {
                    continue;
                }
                String restored = restorer.restore(context, payload);
                if (restored != null && !restored.isBlank()) {
                    results.add(restored);
                }
            } catch (RuntimeException e) {
                log.debug("还原器 {} 跳过: {}", restorer.getProtocolName(), e.getMessage());
            }
        }
        return results.isEmpty() ? List.of() : List.copyOf(results);
    }

    /**
     * 对原始字节做协议还原并取优先级最高的一条。
     *
     * @param payload 应用层字节
     * @return 还原文本，无结果返回 {@code null}
     */
    @Nullable
    public static String restoreBytesAsText(@Nullable byte[] payload) {
        List<String> results = restoreBytes(payload);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * 调用 SPI 注册的 {@link ProtocolRestorer} 对单个 数据包 进行协议还原，
     * 返回全部可命中的还原结果。
     *
     * <p>候选还原器由 {@link #selectRestorers(Map)} 选出：tshark 已解析出某还原器声称的
     * 协议层时进入“层引导”模式，只允许该层的还原器处理本包，字节嗅探型还原器不会抢走
     * 专有协议的报文；无层命中时才按 {@link ProtocolRestorer#getPriority()} 升序嗅探。</p>
     *
     * <p>第二个入参是传输层载荷而非整帧——所有还原器都按 {@code rawData[0]} 起算自己的
     * 协议头部，喂整帧会让它们把以太网/IP 头当协议头解析。</p>
     *
     * <p><b>返回全部而非首个</b>：一帧里可能同时存在多层可还原信息
     * （如 HTTP2 之上的 TLS 记录、TCP 之上的数据库协议）。只保留优先级最高的那个会丢掉其余信息，
     * 会话聚合需要它们做多轮配对，因此这里全部返回，由调用方取首条或全用。</p>
     *
     * @param layers tshark JSON 中 {@code _source.layers} 节点
     * @return 还原结果列表，按优先级升序；无可还原内容返回空列表
     */
    @Nonnull
    private static List<String> restoreAll(@Nullable Map<String, Object> layers) {
        if (RESTORERS.isEmpty() || layers == null || layers.isEmpty()) {
            return List.of();
        }
        byte[] rawBytes = TsharkFields.payload(layers);
        if (rawBytes == null || rawBytes.length == 0) {
            return List.of();
        }
        List<ProtocolRestorer> candidates = selectRestorers(layers);
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<String, Object> context = restoreContext(layers, rawBytes);
        List<String> results = new ArrayList<>();
        for (ProtocolRestorer restorer : candidates) {
            try {
                if (!restorer.canRestore(context, rawBytes)) {
                    continue;
                }
                String restored = restorer.restore(context, rawBytes);
                if (restored != null && !restored.isBlank()) {
                    results.add(restored);
                }
            } catch (RuntimeException e) {
                log.debug("还原器 {} 跳过: {}", restorer.getProtocolName(), e.getMessage());
            }
        }
        return results.isEmpty() ? List.of() : List.copyOf(results);
    }

    /**
     * 选出候选还原器：层引导优先，无层命中时退回全量字节嗅探。
     *
     * @param layers 所有层数据
     * @return 候选还原器列表（已按优先级升序）
     */
    private static List<ProtocolRestorer> selectRestorers(Map<String, Object> layers) {
        List<ProtocolRestorer> guided = new ArrayList<>();
        for (ProtocolRestorer restorer : RESTORERS) {
            if (hasLayer(layers, restorer.getProtocolName())) {
                guided.add(restorer);
            }
        }
        return guided.isEmpty() ? RESTORERS : guided;
    }

    /**
     * 判断数据包是否含指定协议的 tshark 层。
     *
     * @param layers       所有层数据
     * @param protocolName 还原器协议名
     * @return true 表示该协议已被 dissect 出来
     */
    private static boolean hasLayer(Map<String, Object> layers, String protocolName) {
        if (protocolName == null || protocolName.isEmpty()) {
            return false;
        }
        for (String layer : layerNames(protocolName)) {
            if (layers.containsKey(layer)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取协议名对应的全部 tshark 层名（协议名本身 + 登记的别名）。
     *
     * @param protocolName 还原器协议名
     * @return 层名列表
     */
    private static List<String> layerNames(String protocolName) {
        String name = protocolName.toLowerCase(Locale.ROOT);
        List<String> alias = RESTORER_LAYER_ALIASES.get(name);
        if (alias == null || alias.isEmpty()) {
            return List.of(name);
        }
        List<String> names = new ArrayList<>(alias.size() + 1);
        names.add(name);
        names.addAll(alias);
        return names;
    }

    /**
     * 构造传给还原器的协议信息：原始层数据附加 {@code rawData} 键。
     *
     * <p>{@link com.chua.network.support.tshark.restorer.AbstractProtocolRestorer} 的取值
     * 助手按 {@code rawData} 读取载荷，缺失时还原器只能拿到空数组。</p>
     *
     * @param layers   所有层数据
     * @param rawBytes 传输层载荷
     * @return 还原器上下文
     */
    private static Map<String, Object> restoreContext(Map<String, Object> layers, byte[] rawBytes) {
        Map<String, Object> context = new LinkedHashMap<>(layers);
        context.put("rawData", rawBytes);
        return context;
    }

    // ==================== 层解析 ====================

    /**
     * 把 JSON 节点转换为层映射。
     *
     * <p>只取层子树而非整包：整包还含索引元数据，转换开销与包大小成正比，
     * 而还原器只看层。</p>
     *
     * <p>同时兼容两种 tshark 输出形态：{@code -T json} 把层放在
     * {@code _source.layers}，{@code -T ek} 则直接放在 {@code layers}。</p>
     *
     * @param node 单包节点
     * @return 层映射，缺失返回 {@code null}
     */
    @Nullable
    private static Map<String, Object> toMap(@Nonnull JsonNode node) {
        JsonNode source = node.get("_source");
        JsonNode layers = source == null ? node.get("layers") : source.get("layers");
        if (layers == null || !layers.isObject()) {
            return null;
        }
        return OBJECT_MAPPER.convertValue(layers, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    /**
     * 从层数据中提取 IP 地址。
     *
     * @param layers 所有层数据
     * @param role   "src" 或 "dst"
     * @return IP 地址字符串
     */
    @SuppressWarnings("unchecked")
    private static String extractIp(Map<String, Object> layers, String role) {
        Map<String, Object> ipv4 = asMap(layers.get("ip"));
        Map<String, Object> ipv6 = asMap(layers.get("ipv6"));
        Map<String, Object> ipLayer = ipv4 != null ? ipv4 : ipv6;
        if (ipLayer == null) {
            return null;
        }
        String key = ipv4 != null ? "ip." + role : "ipv6." + role;
        Object value = ipLayer.get(key);
        return value == null ? null : value.toString();
    }

    /**
     * 从层数据中提取端口号。
     *
     * @param layers 所有层数据
     * @param role "src" 或 "dst"
     * @return 端口号，不存在返回 空
     */
    private static Integer extractPort(Map<String, Object> layers, String role) {
        Map<String, Object> tcp = asMap(layers.get("tcp"));
        Map<String, Object> udp = asMap(layers.get("udp"));
        Map<String, Object> transportLayer = tcp != null ? tcp : udp;
        if (transportLayer == null) {
            return null;
        }
        String key = tcp != null ? "tcp." + role + "port" : "udp." + role + "port";
        return parseInteger(transportLayer.get(key));
    }

    /**
     * 从帧层提取数据包长度。
     *
     * <p>优先取线缆长度 {@code frame.len}，缺失时退回实际捕获长度
     * {@code frame.cap_len}（抓包设置了 snaplen 时二者不等，
     * 报实际捕获长度更贴近"收到了多少字节"）。</p>
     *
     * @param layers 所有层数据
     * @return 长度值，不存在返回 空
     */
    @Nullable
    private static Integer extractLength(@Nonnull Map<String, Object> layers) {
        Map<String, Object> frame = asMap(layers.get("frame"));
        if (frame == null) {
            return null;
        }
        Integer length = parseInteger(frame.get("frame.len"));
        return length != null ? length : parseInteger(frame.get("frame.cap_len"));
    }

    /**
     * 安全取出层节点映射。
     *
     * <p>tshark 在层重复或字段退化时可能输出数组/字符串，强转会抛
     * {@link ClassCastException} 并使整包被丢弃，此处降级为“该层缺失”。</p>
     *
     * @param value 层节点值
     * @return 映射，非映射返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    // ==================== 元数据构建 ====================

    /**
     * 构建链路层与传输层元数据。
     *
     * @param layers   所有层数据
     * @param protocol 已识别的应用层协议名
     * @return 元数据，层数据缺失时字段为 {@code null}
     */
    @Nonnull
    private static PacketMeta buildMeta(@Nonnull Map<String, Object> layers, @Nullable String protocol) {
        Map<String, Object> frame = asMap(layers.get("frame"));
        Map<String, Object> ip = asMap(layers.get("ip"));
        Map<String, Object> ipv6 = asMap(layers.get("ipv6"));
        Map<String, Object> network = ip != null ? ip : ipv6;
        Map<String, Object> tcp = asMap(layers.get("tcp"));
        String prefix = ip != null ? "ip." : "ipv6.";

        String timestamp = frame == null ? null : stringValue(frame.get("frame.time"));
        // frame.time_epoch 与 frame.time 在 tshark 4.x 下形态相同（ISO-8601），
        // 但旧版本只有小数秒形态，因此优先用 epoch、再退回 time_utc、最后用 time
        Long epochMillis = parseEpochMillis(frame == null ? null : frame.get("frame.time_epoch"));
        if (epochMillis == null && frame != null) {
            epochMillis = parseEpochMillis(frame.get("frame.time_utc"));
        }
        if (epochMillis == null && frame != null) {
            epochMillis = parseEpochMillis(frame.get("frame.time"));
        }
        Long frameNumber = parseLong(frame == null ? null : frame.get("frame.number"));
        // interface_name 位于 frame.interface_id_tree 这个嵌套树里，不是平铺键
        String interfaceName = frame == null ? null : stringValue(frame.get("frame.interface_name"));
        if (interfaceName == null && frame != null) {
            interfaceName = stringValue(TsharkFields.findDeep(frame, "frame.interface_name"));
        }
        if (interfaceName == null && frame != null) {
            interfaceName = stringValue(frame.get("frame.interface_id"));
        }
        Integer ttl = parseInteger(network == null ? null : network.get(prefix + "ttl"));
        Integer ipProtocol = parseInteger(network == null ? null : network.get(prefix + "proto"));

        String streamId = null;
        Long tcpSeq = null;
        Long tcpAck = null;
        String tcpFlags = null;
        if (tcp != null) {
            // tshark 对 tcp.stream 有时给数字、有时给字符串，统一按字符串处理
            streamId = stringValue(tcp.get("tcp.stream"));
            tcpSeq = parseLong(tcp.get("tcp.seq"));
            tcpAck = parseLong(tcp.get("tcp.ack"));
            tcpFlags = buildTcpFlags(tcp);
            if (tcpFlags.isEmpty()) {
                tcpFlags = null;
            }
        }
        return new PacketMeta(frameNumber, timestamp, epochMillis, interfaceName, streamId,
                tcpSeq, tcpAck, ipProtocol, protocol, tcpFlags, ttl);
    }

    /**
     * 安全读取字符串字段。
     *
     * @param value 原始值
     * @return 字符串值，空值返回 {@code null}
     */
    @Nullable
    private static String stringValue(@Nullable Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * 解析抓包时间戳。
     *
     * <p>tshark 的时间字段有多种形态，必须逐个尝试，任一种解析失败都会让时间戳变成
     * {@code null}——而会话聚合的先后顺序与往返时延全部依赖它，一旦为 null
     * 就会把"请求与响应的先后"退化成到达顺序：</p>
     * <ul>
     *   <li><b>ISO-8601</b> — Wireshark 4.x 的 {@code frame.time_epoch}、
     *       {@code frame.time_utc}、{@code frame.time} 均为
     *       {@code 2026-09-26T05:44:18.038517100Z} 这种形态；</li>
     *   <li><b>小数秒</b> — 早期版本与 {@code frame.time_relative} 的形态；</li>
     *   <li><b>整数</b> — 已是毫秒时直接使用。</li>
     * </ul>
     *
     * @param value 原始值
     * @return 毫秒时间戳，解析失败返回 {@code null}
     */
    @Nullable
    private static Long parseEpochMillis(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (DateTimeParseException e) {
            // 非 ISO-8601，继续尝试小数秒
        }
        try {
            return (long) (Double.parseDouble(text) * 1000.0d);
        } catch (NumberFormatException e) {
            // 非小数秒，继续尝试整数
        }
        return parseLong(text);
    }

    /**
     * 安全解析长整型字符串。
     *
     * @param value 原始值
     * @return 解析结果，解析失败返回 {@code null}
     */
    @Nullable
    private static Long parseLong(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 协议与元数据构建 ====================

    /**
     * 根据层类型推断协议名称。
     *
     * @param layers 所有层数据
     * @return 协议名称
     */
    private static String detectProtocol(Map<String, Object> layers) {
        for (Map.Entry<String, String> entry : APPLICATION_LAYERS.entrySet()) {
            if (layers.containsKey(entry.getKey())) {
                return entry.getValue();
            }
        }
        if (layers.containsKey("tcp")) {
            return PROTOCOL_TCP;
        }
        if (layers.containsKey("udp")) {
            return PROTOCOL_UDP;
        }
        if (layers.containsKey("icmp")) {
            return PROTOCOL_ICMP;
        }
        if (layers.containsKey("arp")) {
            return PROTOCOL_ARP;
        }
        return PROTOCOL_OTHER;
    }

    /**
     * 构建TCP flags字符串。
     *
     * @param tcp TCP层数据
     * @return flags字符串（空格分隔），无flags返回空字符串
     */
    @SuppressWarnings("unchecked")
    private static String buildTcpFlags(Map<String, Object> tcp) {
        StringBuilder sb = new StringBuilder();
        if ("1".equals(tcp.get("tcp.flags.syn"))) {
            sb.append(FLAG_SYN);
        }
        if ("1".equals(tcp.get("tcp.flags.ack"))) {
            sb.append(FLAG_ACK);
        }
        if ("1".equals(tcp.get("tcp.flags.fin"))) {
            sb.append(FLAG_FIN);
        }
        if ("1".equals(tcp.get("tcp.flags.reset"))) {
            sb.append(FLAG_RST);
        }
        if ("1".equals(tcp.get("tcp.flags.push"))) {
            sb.append(FLAG_PSH);
        }
        if ("1".equals(tcp.get("tcp.flags.urg"))) {
            sb.append(FLAG_URG);
        }
        return sb.toString().trim();
    }

    /**
     * 计算TCP连接生命周期阶段。
     *
     * @param tcp TCP层数据
     * @return 生命周期阶段名称（SYN/SYN-ACK/FIN/RST/ACK/DATA）
     */
    private static String calculateTcpLifecycle(Map<String, Object> tcp) {
        boolean syn = "1".equals(tcp.get("tcp.flags.syn"));
        boolean ack = "1".equals(tcp.get("tcp.flags.ack"));
        boolean fin = "1".equals(tcp.get("tcp.flags.fin"));
        boolean rst = "1".equals(tcp.get("tcp.flags.reset"));

        if (syn && !ack) {
            return "SYN";
        }
        if (syn && ack) {
            return "SYN-ACK";
        }
        if (fin) {
            return "FIN";
        }
        if (rst) {
            return "RST";
        }
        if (ack) {
            return "ACK";
        }
        return "DATA";
    }

    /**
     * 构建数据包摘要信息字符串。
     *
     * @param sourceIp       源IP
     * @param sourcePort     源端口
     * @param destinationIp  目的IP
     * @param destinationPort 目的端口
     * @param protocol       协议名称
     * @param layers         所有层数据
     * @return 摘要字符串
     */
    @SuppressWarnings("unchecked")
    private static String buildInfo(
            String sourceIp, Integer sourcePort,
            String destinationIp, Integer destinationPort,
            String protocol, Map<String, Object> layers) {
        StringBuilder sb = new StringBuilder();
        appendAddress(sb, sourceIp, sourcePort);
        sb.append(" -> ");
        appendAddress(sb, destinationIp, destinationPort);
        sb.append(" [").append(protocol).append("]");
        appendHttpInfo(sb, layers);
        return sb.toString();
    }

    /**
     * 拼接地址:端口到字符串构建器。
     *
     * @param sb   目标字符串构建器
     * @param ip   IP地址
     * @param port 端口号
     */
    private static void appendAddress(StringBuilder sb, String ip, Integer port) {
        if (ip != null) {
            sb.append(ip);
            if (port != null) {
                sb.append(":").append(port);
            }
        }
    }

    /**
     * 追加HTTP协议信息（方法/URI/状态码）。
     *
     * @param sb     目标字符串构建器
     * @param layers 所有层数据
     */
    private static void appendHttpInfo(StringBuilder sb, Map<String, Object> layers) {
        Map<String, Object> http = asMap(layers.get("http"));
        if (http == null) {
            return;
        }
        Object method = http.get("http.request.method");
        Object uri = http.get("http.request.uri");
        if (method != null) {
            sb.append(" ").append(method).append(' ').append(uri == null ? "" : uri);
        }
        Object responseCode = http.get("http.response.code");
        if (responseCode != null) {
            sb.append(" ").append(responseCode);
        }
    }

    /**
     * 构建生命周期JSON字符串。
     *
     * @param layers 所有层数据
     * @return JSON字符串，包含tcp_flags和lifecycle字段
     */
    private static String buildLifecycleJson(Map<String, Object> layers) {
        Map<String, Object> tcp = asMap(layers.get("tcp"));
        if (tcp == null) {
            return "{}";
        }
        Map<String, String> lifecycleMap = new LinkedHashMap<>();
        lifecycleMap.put("tcp_flags", buildTcpFlags(tcp));
        lifecycleMap.put("lifecycle", calculateTcpLifecycle(tcp));
        try {
            return OBJECT_MAPPER.writeValueAsString(lifecycleMap);
        } catch (Exception e) {
            log.debug("Failed to build lifecycle JSON: {}", e.getMessage());
            return "{}";
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 安全解析整数字符串。
     *
     * @param value 原始值
     * @return 解析后的整数，解析失败返回 空
     */
    private static Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
