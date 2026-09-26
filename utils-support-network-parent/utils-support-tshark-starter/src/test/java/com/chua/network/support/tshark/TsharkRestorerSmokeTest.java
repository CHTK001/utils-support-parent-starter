package com.chua.network.support.tshark;

import com.chua.common.support.lang.directory.EventObserver;
import com.chua.common.support.lang.directory.PolledListener;
import com.chua.common.support.lang.directory.WatcherEvent;
import com.chua.common.support.network.protocol.ProtocolRestorer;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.network.support.tshark.cli.TsharkArtifact;
import com.chua.network.support.tshark.cli.TsharkArtifactInstaller;
import com.chua.network.support.tshark.cli.TsharkCliConfig;
import com.chua.network.support.tshark.cli.TsharkCliProvider;
import com.chua.network.support.tshark.cli.TsharkProvisioningReport;
import com.chua.network.support.tshark.cli.TsharkSettings;
import com.chua.network.support.tshark.session.ProtocolExchange;
import com.chua.network.support.tshark.session.SessionAggregator;
import com.chua.network.support.tshark.stream.ReassembledMessage;
import com.chua.network.support.tshark.stream.TcpStreamAssembler;
import com.chua.network.support.tshark.restorer.AbstractProtocolRestorer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * tshark 解析与协议还原冒烟测试。
 *
 * <p>遵循项目约定使用 {@code main} 方法直接运行（本模块无 JUnit 依赖）：</p>
 * <pre>
 * 运行方式：{@code java com.chua.network.support.tshark.TsharkRestorerSmokeTest}
 * 任一校验失败输出 FAIL 并以退出码 1 结束，全部通过输出 PASS。
 * </pre>
 *
 * <p>输入是本机合成的 tshark {@code -T json -x} 报文（本机无 tshark 可执行文件），
 * 覆盖三类契约：还原器收到的是传输层载荷而非整帧、专有协议不被字节嗅探抢走、
 * 层数据退化时整包不被丢弃。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TsharkRestorerSmokeTest {

    /**
     * 失败计数
     */
    private static int failureCount = 0;
    /**
     * 通过计数
     */
    private static int passCount = 0;
    /**
     * JSON 序列化器
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * main。
     *
     * @param args 参数
     * @throws Exception 构造合成报文失败
     */
    public static void main(String[] args) throws Exception {
        mqttConnectCarriesTransportPayload();
        dissectedLayerWinsOverByteSniffing();
        telnetNegotiationStillRestored();
        binaryPayloadIsNotRedis();
        httpInfoCarriesMethodAndUri();
        degradedIpNodeKeepsPacket();
        chunkedRawDumpIsDecoded();
        genuineDnsStillRestored();
        baseClassBytesSeesPayload();
        check("还原器 SPI 登记齐全", restorerCount() >= 30);
        metaIsExtractedFromLayers();
        iso8601TimestampIsParsed();
        streamingParseMatchesBatchParse();
        streamReaderDeliversIncrementally();
        byteSniffingRestoreWorksWithoutLayers();
        tcpReassemblyOrdersOutOfOrderSegments();
        tcpReassemblyDropsRetransmit();
        tcpReassemblyHandlesSequenceWraparound();
        sessionAggregationPairsRequestResponse();
        settingsResolveAndTimeoutsAreBounded();
        configKeysAreDiscoverable();
        assemblyStageOrderIsConfigurable();
        existingCaptureFilesAreOptIn();
        explicitBinarySurvivesStart();
        managedInstallIsReusedWithoutRedownload();
        failedInstallCleansEmptyDirButKeepsUsableInstall();

        System.out.println("pass=" + passCount + ", fail=" + failureCount);
        if (failureCount > 0) {
            System.out.println("FAIL");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    // ==================== 用例 ====================

    /**
     * MQTT CONNECT：还原器必须拿到 TCP 载荷，协议名必须是 MQTT。
     *
     * @throws Exception 构造失败
     */
    private static void mqttConnectCarriesTransportPayload() throws Exception {
        byte[] payload = mqttConnect();
        PacketRecord record = PacketParserService.parse(json(layers(payload, "mqtt", 1883)));
        check("MQTT CONNECT 非空", record != null);
        if (record == null) {
            return;
        }
        check("协议名识别为 MQTT", "MQTT".equals(record.protocol()));
        check("还原为 CONNECT", starts(record.restoredText(), "[MQTT] type=1 (CONNECT)"));
        check("还原含协议名与版本", contains(record.restoredText(), "protocol=MQTT v4"));
    }

    /**
     * tshark 已 dissect 出专有层时，字节嗅探型还原器不得抢占。
     *
     * @throws Exception 构造失败
     */
    private static void dissectedLayerWinsOverByteSniffing() throws Exception {
        byte[] body = new byte[]{0x00, 0x12, 'c', 'l', 'i', 'e', 'n', 't', 'I', 'd',
                (byte) 0xff, (byte) 0xfe, 0x00, 0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
        int n = body.length;
        byte[] payload = new byte[4 + n];
        payload[0] = (byte) (n >> 24);
        payload[1] = (byte) (n >> 16);
        payload[2] = (byte) (n >> 8);
        payload[3] = (byte) n;
        System.arraycopy(body, 0, payload, 4, n);
        payload[4] = 0x00;
        payload[5] = 18;
        PacketRecord record = PacketParserService.parse(json(layers(payload, "kafka", 9092)));
        check("Kafka 层报文非空", record != null);
        if (record == null) {
            return;
        }
        check("协议名识别为 KAFKA", "KAFKA".equals(record.protocol()));
        check("还原为 Kafka 而非 Telnet", starts(record.restoredText(), "[Kafka]"));
    }

    /**
     * 真实 telnet 协商包仍应还原为 telnet。
     *
     * @throws Exception 构造失败
     */
    private static void telnetNegotiationStillRestored() throws Exception {
        byte[] payload = new byte[]{(byte) 0xff, (byte) 0xfb, 0x01, (byte) 0xff, (byte) 0xfb, 0x03,
                'l', 'o', 'g', 'i', 'n', ':', ' ', 'r', 'o', 'o', 't'};
        PacketRecord record = PacketParserService.parse(json(layers(payload, null, 23)));
        check("telnet 报文非空", record != null);
        if (record != null) {
            check("还原为 Telnet", starts(record.restoredText(), "[Telnet]"));
            check("还原含 IAC 命令", contains(record.restoredText(), "IAC=WILL ECHO"));
        }
    }

    /**
     * 以 ':' 开头的二进制载荷不能被认成 Redis。
     *
     * @throws Exception 构造失败
     */
    private static void binaryPayloadIsNotRedis() throws Exception {
        byte[] payload = new byte[]{':', (byte) 0x80, (byte) 0xff, 0x01, 0x02, 0x03, 0x04, 0x05};
        PacketRecord record = PacketParserService.parse(json(layers(payload, null, 6380)));
        check("二进制冒号载荷非空", record != null);
        if (record != null) {
            check("未被误判为 Redis", !starts(record.restoredText(), "[Redis]"));
        }
    }

    /**
     * 摘要信息需带 HTTP 方法与 URI。
     *
     * @throws Exception 构造失败
     */
    private static void httpInfoCarriesMethodAndUri() throws Exception {
        byte[] payload = "GET /index.html HTTP/1.1\r\nHost: www.example.com\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8);
        Map<String, Object> layers = layers(payload, "http", 80);
        layers.put("http", map("http.request.method", "GET", "http.request.uri", "/index.html"));
        PacketRecord record = PacketParserService.parse(json(layers));
        check("HTTP 报文非空", record != null);
        if (record != null) {
            check("摘要含方法与 URI", contains(record.info(), "GET /index.html"));
        }
    }

    /**
     * ip 层退化为数组时整包不得被丢弃。
     *
     * @throws Exception 构造失败
     */
    private static void degradedIpNodeKeepsPacket() throws Exception {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> layers = new LinkedHashMap<>();
        layers.put("frame", map("frame.number", "6", "frame.len", String.valueOf(payload.length + 54)));
        List<Object> dupIp = new ArrayList<>();
        dupIp.add(map("ip.src", "10.0.0.5"));
        dupIp.add(map("ip.src", "10.0.0.6"));
        layers.put("ip", dupIp);
        layers.put("tcp", transport("tcp", "51239", "8080", payload));
        PacketRecord record = PacketParserService.parse(json(layers));
        check("ip 层退化仍保留数据包", record != null);
        if (record != null) {
            check("端口仍被解析", Integer.valueOf(51239).equals(record.sourcePort()));
        }
    }

    /**
     * 分块十六进制转储需按偏移升序拼接解码。
     *
     * @throws Exception 构造失败
     */
    private static void chunkedRawDumpIsDecoded() throws Exception {
        byte[] payload = mqttConnect();
        Map<String, Object> chunked = new LinkedHashMap<>();
        chunked.put("0000", hex(java.util.Arrays.copyOfRange(payload, 0, 10)));
        chunked.put("000a", hex(java.util.Arrays.copyOfRange(payload, 10, payload.length)));
        Map<String, Object> layers = new LinkedHashMap<>();
        layers.put("frame", map("frame.number", "7", "frame.len", String.valueOf(payload.length + 54)));
        layers.put("ip", map("ip.src", "10.0.0.5", "ip.dst", "10.0.0.9"));
        Map<String, Object> tcp = new LinkedHashMap<>();
        tcp.put("tcp.srcport", "51240");
        tcp.put("tcp.dstport", "1883");
        tcp.put("tcp_payload", chunked);
        layers.put("tcp", tcp);
        layers.put("mqtt", map("mqtt.type", "1"));
        PacketRecord record = PacketParserService.parse(json(layers));
        check("分块转储可还原", record != null && starts(record.restoredText(), "[MQTT]"));
    }

    /**
     * 收紧 DNS 判据后，真实查询与带压缩指针的应答仍须还原。
     *
     * @throws Exception 构造失败
     */
    private static void genuineDnsStillRestored() throws Exception {
        byte[] query = new byte[]{
                0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x03, 'w', 'w', 'w', 0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 0x03, 'c', 'o', 'm', 0x00,
                0x00, 0x01, 0x00, 0x01};
        PacketRecord queryRecord = PacketParserService.parse(json(udpLayers(query)));
        check("DNS 查询仍还原", queryRecord != null && starts(queryRecord.restoredText(), "[DNS]"));

        byte[] response = new byte[query.length + 16];
        System.arraycopy(query, 0, response, 0, query.length);
        response[2] = (byte) 0x81;
        response[3] = (byte) 0x80;
        response[7] = 0x01;
        response[query.length] = (byte) 0xc0;
        response[query.length + 1] = 0x0c;
        response[query.length + 2] = 0x00;
        response[query.length + 3] = 0x01;
        response[query.length + 4] = 0x00;
        response[query.length + 5] = 0x01;
        response[query.length + 10] = 0x00;
        response[query.length + 11] = 0x04;
        PacketRecord responseRecord = PacketParserService.parse(json(udpLayers(response)));
        check("DNS 应答(压缩指针)仍还原",
                responseRecord != null && starts(responseRecord.restoredText(), "[DNS]"));
    }

    /**
     * 扩展基类的 {@code bytes} 助手必须能取到传输层载荷。
     *
     * @throws Exception 构造失败
     */
    private static void baseClassBytesSeesPayload() throws Exception {
        byte[] payload = mqttConnect();
        Map<String, Object> layers = layers(payload, "mqtt", 1883);
        int length = PayloadProbe.readPayload(layers).length;
        check("基类 bytes() 取到载荷", length == payload.length);
    }

    // ==================== 流重组与会话聚合 ====================

    /**
     * 链路层与传输层元数据须被正确提取，供流重组与会话聚合使用。
     *
     * @throws Exception 构造失败
     */
    private static void metaIsExtractedFromLayers() throws Exception {
        byte[] payload = "GET / HTTP/1.1\r\nHost: a\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> layers = layers(payload, "http", 80);
        Map<String, Object> frame = map("frame.number", "12", "frame.len", "74",
                "frame.time_epoch", "1730000000.123456", "frame.interface_id", "3");
        layers.put("frame", frame);
        Map<String, Object> tcp = transport("tcp", "51234", "80", payload);
        tcp.put("tcp.stream", "7");
        tcp.put("tcp.seq", "1000");
        tcp.put("tcp.ack", "2000");
        tcp.put("tcp.flags.push", "1");
        tcp.put("tcp.flags.ack", "1");
        layers.put("tcp", tcp);
        Map<String, Object> ip = map("ip.src", "10.0.0.5", "ip.dst", "10.0.0.9", "ip.ttl", "64", "ip.proto", "6");
        layers.put("ip", ip);

        PacketRecord record = PacketParserService.parse(json(layers));
        check("元数据报文非空", record != null);
        if (record == null) {
            return;
        }
        PacketMeta meta = record.meta();
        check("帧号被提取", Long.valueOf(12L).equals(meta.frameNumber()));
        check("网卡标识被提取", "3".equals(meta.interfaceName()));
        check("流号被提取", "7".equals(meta.streamId()));
        check("序列号被提取", Long.valueOf(1000L).equals(meta.tcpSeq()));
        check("确认号被提取", Long.valueOf(2000L).equals(meta.tcpAck()));
        check("TTL 被提取", Integer.valueOf(64).equals(meta.ttl()));
        check("IP 协议号被提取", Integer.valueOf(6).equals(meta.ipProtocol()));
        check("小数秒时间戳被提取", record.epochMillis() == 1_730_000_000_123L);
        check("识别为 TCP", record.tcp());
        check("载荷被保留", record.hasPayload());
    }

    /**
     * tshark 4.x 的 {@code frame.time_epoch} 是 ISO-8601 文本而非小数秒。
     *
     * <p>这是真实抓包才暴露的缺陷：按小数秒解析会失败，时间戳恒为 null，
     * 而会话聚合的先后顺序与往返时延都依赖时间戳。</p>
     *
     * @throws Exception 构造失败
     */
    private static void iso8601TimestampIsParsed() throws Exception {
        byte[] payload = "GET / HTTP/1.1\r\nHost: a\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> layers = layers(payload, "http", 80);
        layers.put("frame", map("frame.number", "3", "frame.len", "74",
                "frame.time", "2026-09-26T05:44:18.038517100Z",
                "frame.time_utc", "2026-09-26T05:44:18.038517100Z",
                "frame.time_epoch", "2026-09-26T05:44:18.038517100Z",
                "frame.interface_id_tree", map("frame.interface_name", "\\Device\\NPF_Loopback")));
        Map<String, Object> tcp = transport("tcp", "51234", "80", payload);
        tcp.put("tcp.stream", "1");
        tcp.put("tcp.seq", "1000");
        layers.put("tcp", tcp);

        PacketRecord record = PacketParserService.parse(json(layers));
        check("ISO-8601 报文非空", record != null);
        if (record == null) {
            return;
        }
        long epoch = record.epochMillis();
        check("ISO-8601 时间戳被解析", epoch > 1_700_000_000_000L);
        check("ISO-8601 时间戳落在 2026-09-26", isoDate(epoch));
        check("嵌套树中的网卡名被提取",
                "\\Device\\NPF_Loopback".equals(record.meta().interfaceName()));
    }

    /**
     * 判断毫秒时间戳是否落在预期日期。
     *
     * @param epochMillis 毫秒时间戳
     * @return 是 2026-09-26 返回 true
     */
    private static boolean isoDate(long epochMillis) {
        return java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneOffset.UTC)
                .toLocalDate()
                .equals(java.time.LocalDate.of(2026, 9, 26));
    }

    /**
     * 流式解析的结果须与逐包解析一致。
     *
     * @throws Exception 构造失败
     */
    private static void streamingParseMatchesBatchParse() throws Exception {
        byte[] payload = mqttConnect();
        Map<String, Object> first = layers(payload, "mqtt", 1883);
        Map<String, Object> second = layers(payload, "mqtt", 1883);
        String array = "[" + json(first) + "," + json(second) + "]";

        List<PacketRecord> streamed = PacketParserService.parseAll(array);
        check("流式解析得到 2 包", streamed.size() == 2);
        check("流式解析保留还原结果", !streamed.isEmpty()
                && starts(streamed.get(0).restoredText(), "[MQTT]"));
        check("流式解析包内容一致", streamed.size() == 2
                && java.util.Arrays.equals(streamed.get(0).payload(), streamed.get(1).payload()));
    }

    /**
     * 增量读取器须能在数组尚未闭合时先取出已到达的报文。
     *
     * <p>这是实时抓包的前提：抓包进程的输出是持续增长的数组，不会闭合，
     * 若读取器必须等到数组结束才返回，就无法"边抓边还原"。</p>
     *
     * @throws Exception 构造失败
     */
    private static void streamReaderDeliversIncrementally() throws Exception {
        byte[] payload = mqttConnect();
        String first = json(layers(payload, "mqtt", 1883));
        String second = json(layers(payload, "mqtt", 1883));
        // 数组只写开头一个包，不写结束括号，模拟抓包进行中的输出
        String growing = "[" + first;
        var reader = new PacketParserService.StreamReader(
                new java.io.ByteArrayInputStream(growing.getBytes(StandardCharsets.UTF_8)));
        // 解析在后台线程上进行，等待上界放宽：本用例验证的是"未闭合数组也能取出"，
        // 不是投递延迟；机器负载高时 100ms 不足以让后台线程完成一次解析
        List<PacketRecord> firstBatch = reader.poll(5000L);
        check("未闭合数组也能取出首个包", firstBatch.size() == 1);
        check("首个包已还原", !firstBatch.isEmpty() && starts(firstBatch.get(0).restoredText(), "[MQTT]"));
        reader.close();
    }

    /**
     * 无层信息的裸字节也应能被字节嗅探还原，供 TCP 流重组后二次还原使用。
     *
     * @throws Exception 构造失败
     */
    private static void byteSniffingRestoreWorksWithoutLayers() throws Exception {
        byte[] payload = mqttConnect();
        List<String> results = PacketParserService.restoreBytes(payload);
        check("裸字节嗅探出至少一个结果", !results.isEmpty());
        check("裸字节结果中含 MQTT", contains(String.join("\n", results), "[MQTT]"));
        check("裸字节取首条不为空", PacketParserService.restoreBytesAsText(payload) != null);
        check("裸字节空输入返回空", PacketParserService.restoreBytes(new byte[0]).isEmpty());
        check("裸字节 null 输入返回空", PacketParserService.restoreBytes(null).isEmpty());
    }

    /**
     * 乱序段须被排成连续字节。
     *
     * @throws Exception 构造失败
     */
    private static void tcpReassemblyOrdersOutOfOrderSegments() throws Exception {
        TcpStreamAssembler assembler = new TcpStreamAssembler();
        // 首个到达的段用于建立序列号基准，因此"乱序"必须发生在基准建立之后
        assembler.accept(tcpPacket(1000L, "AB"));
        // seq=1006 而期望值是 1002，存在空洞，暂存不排出
        List<ReassembledMessage> gapped = assembler.accept(tcpPacket(1006L, "GH"));
        check("存在空洞时不排出", gapped.isEmpty());
        // seq=1002 填补空洞，连带把 1006 的段一起排出
        List<ReassembledMessage> out = assembler.accept(tcpPacket(1002L, "CDEF"));
        check("空洞填补后排出", out.size() == 1);
        if (out.isEmpty()) {
            return;
        }
        // "CDEF"（seq 1002）先于 "GH"（seq 1006），按序拼接为 CDEFGH
        check("合并出连续字节", "CDEFGH".equals(new String(out.get(0).payload(), StandardCharsets.UTF_8)));
        check("合并段数为 2", out.get(0).segmentCount() == 2);
        check("方向判定为客户端到服务端", out.get(0).clientToServer());
    }

    /**
     * 重传段不得污染已重组的字节。
     *
     * @throws Exception 构造失败
     */
    private static void tcpReassemblyDropsRetransmit() throws Exception {
        TcpStreamAssembler assembler = new TcpStreamAssembler();
        assembler.accept(tcpPacket(1000L, "ABCD"));
        List<ReassembledMessage> again = assembler.accept(tcpPacket(1000L, "ABCD"));
        check("重传段被丢弃", again.isEmpty());
        check("重传不产生额外字节", assembler.droppedBytes() == 0L);
    }

    /**
     * 序列号回绕不得被误判为乱序。
     *
     * <p>TCP 序列号是 32 位无符号，跨过 2^31 后数值会变小。
     * 若按有符号比较，该流会被判成"严重乱序"而永远排不出字节。</p>
     *
     * @throws Exception 构造失败
     */
    private static void tcpReassemblyHandlesSequenceWraparound() throws Exception {
        TcpStreamAssembler assembler = new TcpStreamAssembler();
        long beforeWrap = 0xFFFFFFFEL;
        assembler.accept(tcpPacket(beforeWrap, "AB"));
        long afterWrap = 0L;
        List<ReassembledMessage> out = assembler.accept(tcpPacket(afterWrap, "CD"));
        check("跨 2^31 回绕仍能排出", !out.isEmpty());
        if (!out.isEmpty()) {
            check("回绕后字节连续", "CD".equals(new String(out.get(0).payload(), StandardCharsets.UTF_8)));
        }
    }

    /**
     * 请求与响应须被配成一对并给出时延。
     *
     * @throws Exception 构造失败
     */
    private static void sessionAggregationPairsRequestResponse() throws Exception {
        SessionAggregator aggregator = new SessionAggregator();
        byte[] request = "GET /api HTTP/1.1\r\nHost: a\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8);

        List<ProtocolExchange> first = aggregator.accept(
                httpPacketAt(1000L, 51234, 80, request, 1_000L, "GET", "/api", null));
        check("请求方暂不成对", first.isEmpty());
        List<ProtocolExchange> second = aggregator.accept(
                httpPacketAt(2000L, 80, 51234, response, 1_120L, null, null, "200"));
        check("响应方完成配对", second.size() == 1);
        if (second.isEmpty()) {
            return;
        }
        ProtocolExchange exchange = second.get(0);
        check("配对已标记为完整", exchange.paired());
        check("协议名为 HTTP", "HTTP".equals(exchange.protocol()));
        check("时延为 120ms", exchange.latencyMillis() == 120L);
        check("会话数为 1", aggregator.conversationCount() == 1);
        check("累计包数为 2", aggregator.totalPackets() == 2L);
    }

    // ==================== 配置与超时 ====================

    /**
     * 配置查找须遵循"系统属性 → 环境变量 → 环境配置 → 默认值"，
     * 且所有超时都有正的上界。
     */
    private static void settingsResolveAndTimeoutsAreBounded() {
        String key = "tshark.smoketest.timeout";
        System.setProperty(key, "77");
        try {
            check("系统属性被优先采用",
                    TsharkSettings.seconds(key, null, 10L) == 77L);
            check("键名可转环境变量名",
                    "TSHARK_SMOKETEST_TIMEOUT".equals(TsharkSettings.toEnvKey(key)));
            check("非法值回退默认值",
                    TsharkSettings.seconds("tshark.smoketest.absent", null, 30L) == 30L);
            check("非正值回退默认值",
                    TsharkSettings.seconds(key + ".zero", null, 45L) == 45L);

            TsharkCliConfig config = TsharkCliConfig.defaults();
            check("执行超时为正", config.execTimeoutSeconds() > 0L);
            check("版本探测超时为正", config.versionTimeoutSeconds() > 0L);
            check("下载超时为正", config.downloadTimeoutSeconds() > 0L);
            check("安装超时为正", config.installTimeoutSeconds() > 0L);
            check("下载超时可配置", TsharkCliConfig.builder()
                    .downloadTimeoutSeconds(1234L).build().downloadTimeoutSeconds() == 1234L);
            check("镜像至少有一个", !config.mirrors().isEmpty());
            check("默认开启自动装配", config.autoInstall());
        } finally {
            System.clearProperty(key);
        }
    }

    /**
     * 抓包与装配的全部配置键都应可列举，便于诊断接口输出。
     */
    private static void configKeysAreDiscoverable() {
        String[] keys = TsharkSettings.knownKeys();
        check("配置键数量合理", keys.length >= 20);
        check("含网卡配置键", List.of(keys).contains(TsharkSettings.KEY_CAPTURE_INTERFACE));
        check("含空闲超时配置键", List.of(keys).contains(TsharkSettings.KEY_CAPTURE_IDLE_TIMEOUT_MILLIS));
        check("含下载超时配置键", List.of(keys).contains(TsharkSettings.KEY_DOWNLOAD_TIMEOUT_SECONDS));
    }

    // ==================== 装配级别与目录语义 ====================

    /**
     * 装配级别顺序须可配置，且定位级别始终被补上。
     */
    private static void assemblyStageOrderIsConfigurable() {
        check("默认三级齐全", TsharkCliConfig.defaults().stages().size() == 3);
        check("默认顺序为定位优先", TsharkCliConfig.defaults().stages().get(0)
                == TsharkCliConfig.Stage.LOCATE);

        TsharkCliConfig skipped = TsharkCliConfig.builder()
                .stages(List.of(TsharkCliConfig.Stage.DOWNLOAD)).build();
        check("可裁剪为仅下载", skipped.stages().size() == 2);
        check("定位级别被自动补到首位", skipped.stages().get(0) == TsharkCliConfig.Stage.LOCATE);
        check("下载级别被保留", skipped.stages().contains(TsharkCliConfig.Stage.DOWNLOAD));
        check("包管理器被裁掉", !skipped.stages().contains(TsharkCliConfig.Stage.PACKAGE_MANAGER));

        check("级别名可解析", TsharkCliConfig.Stage.of("PACKAGEMANAGER")
                == TsharkCliConfig.Stage.PACKAGE_MANAGER);
        check("非法级别名返回空", TsharkCliConfig.Stage.of("不存在的级别") == null);
        check("空级别名返回空", TsharkCliConfig.Stage.of(null) == null);

        // 必须使用真实配置键：TsharkSettings 读取的是 tshark.stages，
        // 自造一个相似键不会生效（这一点本身也验证了键名映射没有偏差）
        String key = TsharkSettings.KEY_STAGES;
        System.setProperty(key, "locate,download");
        try {
            TsharkCliConfig parsed = TsharkCliConfig.from(null);
            check("配置项可解析级别顺序", parsed.stages().size() == 2
                    && parsed.stages().contains(TsharkCliConfig.Stage.DOWNLOAD));
            check("级别键在已知键中", List.of(TsharkSettings.knownKeys()).contains(key));
        } finally {
            System.clearProperty(key);
        }
    }

    /**
     * 启动前已存在的抓包文件默认不处理，开启开关后才处理。
     *
     * @throws Exception 构造失败
     */
    private static void existingCaptureFilesAreOptIn() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("tshark-smoke-existing");
        try {
            java.nio.file.Files.writeString(dir.resolve("a.pcap"), "not-a-real-pcap");

            // 默认语义：start() 写入初始快照，首次 upgrade 不投递
            TsharkPolledDirectory plain = new TsharkPolledDirectory(dir.toString());
            plain.setCliConfig(TsharkCliConfig.builder()
                    .autoInstall(false)
                    .binary("tshark-not-installed-on-purpose")
                    .build());
            check("默认不处理已有文件", !plain.processExisting());
            plain.start(noOpEnvironment(), NOOP_EXECUTOR);
            plain.upgrade();
            plain.close();

            // 开启开关：已有但未投递过的文件会被当作新文件处理
            TsharkPolledDirectory optIn = new TsharkPolledDirectory(dir.toString());
            optIn.setProcessExisting(true);
            check("可开启处理已有文件", optIn.processExisting());
            optIn.setCliConfig(TsharkCliConfig.builder()
                    .autoInstall(false)
                    .binary("tshark-not-installed-on-purpose")
                    .build());
            java.util.concurrent.atomic.AtomicInteger created =
                    new java.util.concurrent.atomic.AtomicInteger();
            optIn.addListener(new PolledListener() {
                @Override
                public void onCreate(WatcherEvent event, EventObserver observer) {
                    created.incrementAndGet();
                }
            });
            optIn.start(noOpEnvironment(), NOOP_EXECUTOR);
            optIn.upgrade();
            check("已有文件被投递为 CREATE", created.get() == 1);
            // 再跑一轮不应重复投递
            optIn.upgrade();
            check("已有文件不重复投递", created.get() == 1);
            optIn.close();
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * 全新进程必须能复用本模块此前安装在托管目录中的 tshark
     *
     * <p>回归背景：本模块把 tshark 安装到 {@code ~/.chua/tshark/<版本>}，而
     * {@code ExecutableLocator} 只搜 PATH 与常见安装目录，并不包含该目录。
     * 一旦缺少托管目录兜底，每次进程启动都会判定"未安装"并重新下载上百 MB。
     * 本用例用全新的 provider 实例（不走单例缓存）覆盖该行为。</p>
     *
     * @throws Exception 临时目录创建失败
     */
    private static void managedInstallIsReusedWithoutRedownload() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("tshark-smoke-managed");
        try {
            String exe = com.chua.network.support.tshark.cli.TsharkArtifactResolver.executableName();
            for (String version : List.of("4.6.9", "10.2.0")) {
                java.nio.file.Path dir = root.resolve(version);
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Files.write(dir.resolve(exe), new byte[]{0});
            }

            // 指定版本与目录一致时，应命中精确目录
            TsharkCliProvider.getInstance().invalidate();
            TsharkProvisioningReport exact = TsharkCliProvider.getInstance().provision(
                    TsharkCliConfig.builder()
                            .installDir(root)
                            .version("4.6.9")
                            .autoInstall(false)
                            .stages(List.of(TsharkCliConfig.Stage.LOCATE))
                            .build());
            check("托管安装可被全新实例定位", exact.available());
            if (exact.available() && exact.executable().startsWith(root)) {
                check("按配置版本命中精确目录", exact.executable().toString().contains("4.6.9"));
            }

            // 指定版本不存在时，应遍历托管目录并取版本号最大的一个
            TsharkCliProvider.getInstance().invalidate();
            TsharkProvisioningReport scan = TsharkCliProvider.getInstance().provision(
                    TsharkCliConfig.builder()
                            .installDir(root)
                            .version("0.0.0")
                            .autoInstall(false)
                            .stages(List.of(TsharkCliConfig.Stage.LOCATE))
                            .build());
            check("托管目录扫描可定位", scan.available());
            if (scan.available() && scan.executable().startsWith(root)) {
                check("托管目录取版本号最大者", scan.executable().toString().contains("10.2.0"));
                check("托管目录不回落到低版本", !scan.executable().toString().contains("4.6.9"));
            }

            // 关键回归点：复用托管安装时不得触发下载级别
            check("复用托管安装不触发下载", scan.attempts().stream()
                    .noneMatch(attempt -> "下载".equals(attempt.stage())));
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * 安装失败后只清理空目录，且不得误删已有的可用安装
     *
     * <p>回归背景：安装被中断会在用户主目录留下空的
     * {@code ~/.chua/tshark/<版本>}，既是垃圾，也让"已安装"的假象留存；
     * 但清理必须只针对空目录——目录里一旦有文件就可能是上次成功安装的产物，
     * 误删会让用户丢掉一个可用的 tshark。</p>
     *
     * <p>失败用回环地址 1 端口制造：该端口不会有人监听，连接被立即拒绝，
     * 且 {@code Downloader} 不含重试逻辑，因此失败快速且不依赖外网。
     * 目录清理由 {@code TsharkArtifactInstaller} 的异常分支触发，
     * 直接调用安装器，不依赖机器上是否已装 tshark。</p>
     *
     * @throws Exception 临时目录操作失败
     */
    private static void failedInstallCleansEmptyDirButKeepsUsableInstall() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("tshark-smoke-install-cleanup");
        try {
            TsharkArtifact artifact = new TsharkArtifact("9.9.9", TsharkArtifact.Kind.INSTALLER_NSIS,
                    "http://127.0.0.1:1/tshark-fake.exe", "tshark-fake.exe", null, "tshark.exe");

            // 场景一：上次中断留下的空版本目录，失败后应被清理
            java.nio.file.Path emptyRoot = root.resolve("empty-root");
            java.nio.file.Path emptyTarget = emptyRoot.resolve("9.9.9");
            java.nio.file.Files.createDirectories(emptyTarget);
            check("失败前空版本目录存在", java.nio.file.Files.isDirectory(emptyTarget));
            check("该版本目录确为空", isEmptyDir(emptyTarget));
            check("下载失败抛出 InstallException", installFails(artifact, emptyRoot));
            check("失败后空版本目录被清理", !java.nio.file.Files.exists(emptyTarget));
            check("失败后 .download 缓存被清理", !java.nio.file.Files.exists(emptyRoot.resolve(".download")));

            // 场景二：目录里已有上次成功安装的产物，不得误删
            java.nio.file.Path keepRoot = root.resolve("keep-root");
            java.nio.file.Path keepTarget = keepRoot.resolve("9.9.9");
            java.nio.file.Files.createDirectories(keepTarget);
            java.nio.file.Path marker = keepTarget.resolve("tshark.exe");
            java.nio.file.Files.writeString(marker, "existing-install");
            check("有产物的目录同样下载失败", installFails(artifact, keepRoot));
            check("非空版本目录不被误删", java.nio.file.Files.isDirectory(keepTarget));
            check("既有安装产物未被删除", java.nio.file.Files.exists(marker));
            check("保留目录内容未被改动", "existing-install".equals(java.nio.file.Files.readString(marker)));
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * 用必然失败的下载地址执行一次安装
     *
     * @param artifact   制品描述
     * @param installDir 安装根目录
     * @return 是否以 {@code InstallException} 失败；其他异常打印后按未预期失败返回
     */
    private static boolean installFails(TsharkArtifact artifact, java.nio.file.Path installDir) {
        TsharkCliConfig config = TsharkCliConfig.builder()
                .installDir(installDir)
                .version("9.9.9")
                .downloadTimeoutSeconds(5L)
                .build();
        try {
            TsharkArtifactInstaller.install(artifact, config);
            return false;
        } catch (TsharkArtifactInstaller.InstallException e) {
            return true;
        } catch (Exception e) {
            System.out.println("  非预期异常类型: " + e.getClass().getName() + " - " + e.getMessage());
            return false;
        }
    }

    /**
     * 目录是否为空
     *
     * @param dir 目录
     * @return 空目录返回 {@code true}
     * @throws Exception 遍历失败
     */
    private static boolean isEmptyDir(java.nio.file.Path dir) throws Exception {
        try (var stream = java.nio.file.Files.list(dir)) {
            return stream.findAny().isEmpty();
        }
    }

    /**
     * 无副作用的环境对象
     *
     * @return 环境对象
     */
    private static com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment
            noOpEnvironment() {
        return new com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment(
                java.util.Set.of(WatcherEvent.CREATE), 3600, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * 不做任何事的轮询执行器。
     *
     * <p>用例只关心 {@code upgrade()} 的行为，不需要定时线程；传入本执行器可避免
     * 留下后台轮询循环。</p>
     */
    private static final com.chua.common.support.lang.directory.executor.DirectoryPollerExecutor
            NOOP_EXECUTOR = new com.chua.common.support.lang.directory.executor.DirectoryPollerExecutor() {
        @Override
        public void start() {
  // 不启动任何后台线程
        }

        @Override
        public void close() {
  // 无资源可释放
        }
    };

    /**
     * 递归删除目录。
     *
     * @param dir 目录
     */
    private static void deleteRecursively(java.nio.file.Path dir) {
        try (var stream = java.nio.file.Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try {
                    java.nio.file.Files.deleteIfExists(path);
                } catch (Exception e) {
                    // 忽略
                }
            });
        } catch (Exception e) {
            // 忽略
        }
    }

    /**
     * {@code start()} 不得丢弃显式指定的可执行文件路径。
     *
     * <p>此前 {@code start()} 用环境配置整体覆盖命令行配置，把
     * {@code setTsharkBinary} 设的路径抹掉，表现为"明明指定了路径却去下载 tshark"。</p>
     *
     * @throws Exception 构造失败
     */
    private static void explicitBinarySurvivesStart() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("tshark-smoke-binary");
        try {
            String explicit = "C:/explicit/path/tshark.exe";
            TsharkPolledDirectory poller = new TsharkPolledDirectory(dir.toString());
            poller.setTsharkBinary(explicit);
            poller.start(noOpEnvironment(), NOOP_EXECUTOR);
            check("start() 保留显式 tshark 路径", explicit.equals(poller.effectiveCliConfig().binary()));
            poller.close();

            // 同样适用于直接设置整份配置
            TsharkPolledDirectory viaConfig = new TsharkPolledDirectory(dir.toString());
            viaConfig.setCliConfig(TsharkCliConfig.builder().binary(explicit).build());
            viaConfig.start(noOpEnvironment(), NOOP_EXECUTOR);
            check("start() 保留 setCliConfig 的路径",
                    explicit.equals(viaConfig.effectiveCliConfig().binary()));
            viaConfig.close();
        } finally {
            deleteRecursively(dir);
        }
    }

    // ==================== 合成报文 ====================

    /**
     * 构造一个 TCP 数据包（客户端 51234 到服务端 80）。
     *
     * @param seq     序列号
     * @param payload 载荷文本
     * @return 数据包记录
     * @throws Exception 构造失败
     */
    private static PacketRecord tcpPacket(long seq, String payload) throws Exception {
        return tcpPacketAt(seq, 51234, 80, payload.getBytes(StandardCharsets.UTF_8), 1_000L);
    }

    /**
     * 构造一个指定端口与时间戳的 TCP 数据包。
     *
     * @param seq        序列号
     * @param sourcePort 源端口
     * @param destPort   目的端口
     * @param payload    载荷
     * @param epochMillis 时间戳
     * @return 数据包记录
     * @throws Exception 构造失败
     */
    private static PacketRecord tcpPacketAt(long seq, int sourcePort, int destPort,
                                            byte[] payload, long epochMillis) throws Exception {
        return build(seq, sourcePort, destPort, payload, epochMillis, null);
    }

    /**
     * 构造一个带 http 层的 TCP 数据包，用于验证会话聚合的协议名与时延。
     *
     * @param seq         序列号
     * @param sourcePort  源端口
     * @param destPort    目的端口
     * @param payload     载荷
     * @param epochMillis 时间戳
     * @param method      请求方法，响应包传 {@code null}
     * @param uri         请求 URI，响应包传 {@code null}
     * @param statusCode  响应状态码，请求包传 {@code null}
     * @return 数据包记录
     * @throws Exception 构造失败
     */
    private static PacketRecord httpPacketAt(long seq, int sourcePort, int destPort, byte[] payload,
                                             long epochMillis, String method, String uri,
                                             String statusCode) throws Exception {
        Map<String, Object> http = new LinkedHashMap<>();
        if (method != null) {
            http.put("http.request.method", method);
            http.put("http.request.uri", uri);
        }
        if (statusCode != null) {
            http.put("http.response.code", statusCode);
        }
        return build(seq, sourcePort, destPort, payload, epochMillis, http);
    }

    /**
     * 构造一个 TCP 数据包。
     *
     * @param seq         序列号
     * @param sourcePort  源端口
     * @param destPort    目的端口
     * @param payload     载荷
     * @param epochMillis 时间戳，0 表示沿用报文自带时间戳
     * @param appLayer    应用层字段，无应用层传 {@code null}
     * @return 数据包记录
     * @throws Exception 构造失败
     */
    private static PacketRecord build(long seq, int sourcePort, int destPort, byte[] payload,
                                      long epochMillis, Map<String, Object> appLayer) throws Exception {
        Map<String, Object> layers = new LinkedHashMap<>();
        layers.put("frame", map("frame.number", String.valueOf(seq), "frame.len",
                String.valueOf(payload.length + 54)));
        layers.put("ip", map("ip.src", sourcePort < destPort ? "10.0.0.5" : "10.0.0.9",
                "ip.dst", sourcePort < destPort ? "10.0.0.9" : "10.0.0.5"));
        Map<String, Object> tcp = transport("tcp", String.valueOf(sourcePort),
                String.valueOf(destPort), payload);
        tcp.put("tcp.stream", "1");
        tcp.put("tcp.seq", String.valueOf(seq));
        tcp.put("tcp.flags.push", "1");
        layers.put("tcp", tcp);
        if (appLayer != null) {
            layers.put("http", appLayer);
        }
        PacketRecord record = PacketParserService.parse(json(layers));
        if (record == null) {
            throw new IllegalStateException("构造 TCP 数据包失败");
        }
        return epochMillis == 0L ? record : withEpoch(record, epochMillis);
    }

    /**
     * 复制数据包并覆盖时间戳。
     *
     * @param record     原数据包
     * @param epochMillis 新时间戳
     * @return 新数据包
     */
    private static PacketRecord withEpoch(PacketRecord record, long epochMillis) {
        PacketMeta meta = record.meta();
        PacketMeta updated = new PacketMeta(meta.frameNumber(), meta.timestamp(), epochMillis,
                meta.interfaceName(), meta.streamId(), meta.tcpSeq(), meta.tcpAck(),
                meta.ipProtocol(), meta.appLayer(), meta.tcpFlags(), meta.ttl());
        return new PacketRecord(record.sourceIp(), record.destinationIp(), record.sourcePort(),
                record.destinationPort(), record.protocol(), record.length(), record.info(),
                record.rawData(), record.lifecycleJson(), record.restoredText(), updated,
                record.payload(), record.restoredDetails());
    }

    /**
     * MQTT CONNECT 载荷：协议名 MQTT、级别 4、clean session、clientId=client。
     *
     * @return 载荷字节
     */
    private static byte[] mqttConnect() {
        return new byte[]{0x10, 0x12, 0x00, 0x04, 'M', 'Q', 'T', 'T', 0x04, 0x02, 0x00, 0x3c,
                0x00, 0x06, 'c', 'l', 'i', 'e', 'n', 't'};
    }

    /**
     * 构造 TCP 报文层集合。
     *
     * @param payload  传输层载荷
     * @param appLayer 应用层名，可为空
     * @param dstPort  目的端口
     * @return 层集合
     */
    private static Map<String, Object> layers(byte[] payload, String appLayer, int dstPort) {
        Map<String, Object> layers = new LinkedHashMap<>();
        String protocols = "eth:ethertype:ip:tcp" + (appLayer == null ? "" : ":" + appLayer);
        layers.put("frame", map("frame.number", "1", "frame.len", String.valueOf(payload.length + 54),
                "frame.protocols", protocols, "frame_raw", raw(l2frame(payload))));
        layers.put("ip", map("ip.src", "10.0.0.5", "ip.dst", "10.0.0.9", "ip.version", "4"));
        layers.put("tcp", transport("tcp", "51234", String.valueOf(dstPort), payload));
        if (appLayer != null) {
            layers.put(appLayer, map(appLayer + ".type", "1"));
        }
        return layers;
    }

    /**
     * 构造 UDP 报文层集合。
     *
     * @param payload 传输层载荷
     * @return 层集合
     */
    private static Map<String, Object> udpLayers(byte[] payload) {
        Map<String, Object> layers = new LinkedHashMap<>();
        layers.put("frame", map("frame.number", "8", "frame.len", String.valueOf(payload.length + 42),
                "frame.protocols", "eth:ethertype:ip:udp:dns"));
        layers.put("ip", map("ip.src", "10.0.0.5", "ip.dst", "10.0.0.9"));
        layers.put("udp", transport("udp", "51241", "53", payload));
        return layers;
    }

    /**
     * 构造传输层节点。
     *
     * @param prefix  层前缀
     * @param srcPort 源端口
     * @param dstPort 目的端口
     * @param payload 载荷
     * @return 传输层节点
     */
    private static Map<String, Object> transport(String prefix, String srcPort, String dstPort, byte[] payload) {
        Map<String, Object> tcp = new LinkedHashMap<>();
        tcp.put(prefix + ".srcport", srcPort);
        tcp.put(prefix + ".dstport", dstPort);
        tcp.put(prefix + "_payload", raw(payload));
        return tcp;
    }

    /**
     * 以太帧 + IP + TCP 头部拼载荷。
     *
     * @param payload 传输层载荷
     * @return 整帧字节
     */
    private static byte[] l2frame(byte[] payload) {
        byte[] head = new byte[54];
        head[12] = (byte) 0x08;
        head[14] = 0x45;
        head[23] = 6;
        head[34] = 0x50;
        byte[] frame = new byte[head.length + payload.length];
        System.arraycopy(head, 0, frame, 0, head.length);
        System.arraycopy(payload, 0, frame, head.length, payload.length);
        return frame;
    }

    /**
     * 序列化为 JSON 字符串。
     *
     * @param layers 层集合
     * @return 单包 JSON
     * @throws Exception 序列化失败
     */
    private static String json(Map<String, Object> layers) throws Exception {
        return MAPPER.writeValueAsString(map("_source", map("layers", layers)));
    }

    /**
     * 构造可变映射。
     *
     * @param keysAndValues 键值交替数组
     * @return 映射
     */
    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    /**
     * tshark 原始字节字段形态。
     *
     * @param bytes 字节
     * @return 十六进制串与偏移长度
     */
    private static List<Object> raw(byte[] bytes) {
        List<Object> list = new ArrayList<>();
        list.add(hex(bytes));
        list.add("0:" + bytes.length);
        return list;
    }

    /**
     * 转十六进制。
     *
     * @param bytes 字节
     * @return 十六进制串
     */
    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ==================== 断言 ====================

    /**
     * 校验项。
     *
     * @param name   名称
     * @param passed 是否通过
     */
    private static void check(String name, boolean passed) {
        if (passed) {
            passCount++;
            System.out.println("  ok   " + name);
        } else {
            failureCount++;
            System.out.println("  FAIL " + name);
        }
    }

    /**
     * 前缀判断，空值视为不匹配。
     *
     * @param text   文本
     * @param prefix 前缀
     * @return 是否以前缀开头
     */
    private static boolean starts(String text, String prefix) {
        return text != null && text.startsWith(prefix);
    }

    /**
     * 包含判断，空值视为不匹配。
     *
     * @param text  文本
     * @param piece 片段
     * @return 是否包含
     */
    private static boolean contains(String text, String piece) {
        return text != null && text.contains(piece);
    }

    /**
     * 协议还原器登记数下限，用于确认 SPI 文件仍被加载。
     *
     * @return 登记数
     */
    static int restorerCount() {
        return ServiceProvider.of(ProtocolRestorer.class).collect().size();
    }

    /**
     * 暴露基类受保护的取值助手，用于校验扩展契约。
     */
    private static class PayloadProbe extends AbstractProtocolRestorer {

        /**
         * 读取协议信息中的传输层载荷。
         *
         * @param protocolInfo 协议信息
         * @return 载荷字节
         */
        static byte[] readPayload(Map<String, Object> protocolInfo) {
            return bytes(protocolInfo);
        }

        @Override
        /**
         * 获取协议名称
        */
        public String getProtocolName() {
            return "probe";
        }

        @Override
        /**
         * Restore
        */
        public String restore(Map<String, Object> protocolInfo, byte[] rawData) {
            return "";
        }
    }
}
