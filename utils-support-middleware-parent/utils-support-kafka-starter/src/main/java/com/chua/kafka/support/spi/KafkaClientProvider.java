package com.chua.kafka.support.spi;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.middleware.MiddlewareCapability;
import com.chua.datasource.support.middleware.MiddlewareClientProvider;
import com.chua.datasource.support.middleware.MiddlewareConnectRequest;
import com.chua.datasource.support.middleware.MiddlewareDocument;
import com.chua.datasource.support.middleware.MiddlewareNotInstalledException;
import com.chua.datasource.support.middleware.MiddlewareQueryResult;
import com.chua.datasource.support.middleware.MiddlewareTestResult;
import com.chua.datasource.support.middleware.MiddlewareTreeNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.DescribeConsumerGroupsResult;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicListing;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.UnsupportedVersionException;

/**
 * Kafka 中间件管理面 SPI 实现（全部能力）。
 *
 * <p><b>能力范围</b>：连接测试、Topic 列表与详情（分区/副本/配置）、
 * 分区子节点、消费组与 lag、Broker 列表、只读消息预览（按 offset 从队首取）。</p>
 *
 * <p><b>为什么不复用 {@code KafkaClient}</b>：那是面向业务收发的客户端（producer/consumer），
 * 管理面要的是 {@link AdminClient} 的元数据能力（Broker、消费组 offset、lag），
 * 两者职责不同。这里直接用 {@code kafka-clients} 原生 API，不引入 Spring Kafka。</p>
 *
 * <p><b>安全边界</b>：只读。创建 Topic、删除 Topic、生产消息、重置 offset
 * 一律不实现 —— 管理面的定位是「看清现状」，写操作留给业务侧。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = "kafka", order = 200)
public class KafkaClientProvider implements MiddlewareClientProvider {

    /** SPI 名称，与接口 {@code SPI_NAME} 一致（注册文件名） */
    public static final String SPI_NAME = "com.chua.datasource.support.middleware.MiddlewareClientProvider";

    /** 连接与元数据操作超时：管理面要秒级返回，不能用驱动默认的 30s+。
     *  取 3s：本机/内网探测足够，又不会让「连不上」的反馈拖到 10s。 */
    private static final long ADMIN_TIMEOUT_SECONDS = 3L;

    /** 消息预览默认条数 */
    private static final int DEFAULT_PREVIEW = 20;

    /** 单次消息预览上限：避免把大消息刷满响应体 */
    private static final int MAX_PREVIEW = 200;

    /** 单条消息体截断长度 */
    private static final int VALUE_TEXT_LIMIT = 4000;

    @Override
    public String name() {
        return "kafka";
    }

    @Override
    public String displayName() {
        return "Apache Kafka";
    }

    @Override
    public int defaultPort() {
        return 9092;
    }

    @Override
    public MiddlewareCapability capability() {
        return new MiddlewareCapability(
                true, true, true, true,
                "主题 (Topics)", "分区 (Partitions)",
                "输入查询：topics / brokers / groups / describe <topic> / preview <topic>",
                "只读查询支持 topics（主题列表）、brokers（Broker 列表）、groups（消费组与 lag）、"
                        + "describe &lt;topic&gt;（主题详情）、preview &lt;topic&gt;（消息预览）");
    }

    @Override
    public MiddlewareTestResult test(MiddlewareConnectRequest request) {
        try (AdminClient admin = adminClient(request)) {
            // describeCluster 一次拿到 Broker 数、控制器、集群 ID 与版本信息
            String clusterId = admin.describeCluster().clusterId().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Collection<Node> nodes = admin.describeCluster().nodes().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Node controller = null;
            try {
                controller = admin.describeCluster().controller().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception ignore) {
                // 老版本 Broker 不支持 controller 查询，不影响连通性判定
            }
            Set<String> topics = admin.listTopics().names().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String detail = "Broker " + nodes.size() + " 个，Topic " + topics.size() + " 个"
                    + (controller == null ? "" : "，控制器 node-" + controller.id());
            return MiddlewareTestResult.ok(clusterId == null ? "" : clusterId, detail);
        } catch (Exception e) {
            return MiddlewareTestResult.fail(rootMessage(e));
        }
    }

    @Override
    public List<MiddlewareTreeNode> listTopNodes(MiddlewareConnectRequest request) {
        List<MiddlewareTreeNode> out = new ArrayList<>();
        try (AdminClient admin = adminClient(request)) {
            for (TopicListing listing : admin.listTopics().listings().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                boolean internal = listing.isInternal();
                out.add(new MiddlewareTreeNode(listing.name(), listing.name(),
                        internal ? "内部主题" : "", null));
            }
        } catch (Exception e) {
            // 读不到就返回空列表，前端据此显示空态而不是报错
            return List.of();
        }
        out.sort((a, b) -> a.key().compareTo(b.key()));
        return out;
    }

    @Override
    public List<MiddlewareTreeNode> listChildren(MiddlewareConnectRequest request, String parentKey) {
        if (parentKey == null || parentKey.isBlank()) {
            return List.of();
        }
        List<MiddlewareTreeNode> out = new ArrayList<>();
        try (AdminClient admin = adminClient(request)) {
            DescribeTopicsResult result = admin.describeTopics(Set.of(parentKey));
            result.topicNameValues().get(parentKey).get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .partitions().forEach(p -> out.add(new MiddlewareTreeNode(
                            parentKey + "-" + p.partition(),
                            "分区 " + p.partition(),
                            "leader=node-" + (p.leader() == null ? "-" : p.leader().id())
                                    + " 副本=" + p.replicas().size(),
                            null)));
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    @Override
    public MiddlewareQueryResult query(MiddlewareConnectRequest request, String expression, int limit) {
        if (expression == null || expression.isBlank()) {
            return MiddlewareQueryResult.fail("查询表达式为空。支持：topics / brokers / groups / describe <topic> / preview <topic>");
        }
        String cmd = expression.trim();
        long t0 = System.currentTimeMillis();
        try (AdminClient admin = adminClient(request)) {
            if (cmd.equalsIgnoreCase("topics")) {
                return table(admin, t0, limit);
            }
            if (cmd.equalsIgnoreCase("brokers")) {
                return brokers(admin, t0);
            }
            if (cmd.equalsIgnoreCase("groups")) {
                return groups(admin, t0);
            }
            String lower = cmd.toLowerCase();
            if (lower.startsWith("describe ")) {
                return describe(admin, t0, cmd.substring("describe ".length()).trim());
            }
            if (lower.startsWith("preview ")) {
                return preview(request, t0, cmd.substring("preview ".length()).trim(), limit);
            }
            return MiddlewareQueryResult.fail("不支持的查询: " + cmd
                    + "。只读查询仅支持 topics / brokers / groups / describe <topic> / preview <topic>");
        } catch (Exception e) {
            return MiddlewareQueryResult.fail(rootMessage(e));
        }
    }

    @Override
    public MiddlewareDocument document(MiddlewareConnectRequest request, String scope, String key) {
        if (scope == null || scope.isBlank()) {
            return null;
        }
        // scope = 主题名，key = 分区号或 "group:<组名>" / "broker:<id>"
        try (AdminClient admin = adminClient(request)) {
            if (key != null && key.startsWith("group:")) {
                return groupDocument(admin, key.substring("group:".length()));
            }
            if (key != null && key.startsWith("broker:")) {
                return brokerDocument(admin, key.substring("broker:".length()));
            }
            return topicDocument(admin, scope);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 按数据源行建管理客户端。
     *
     * <p>Kafka 的账号密码是 SASL 机制（PLAIN/SCRAM），由 {@code username} 决定是否开启；
     * {@code database} 字段在这里没有语义，忽略。</p>
     */
    private AdminClient adminClient(MiddlewareConnectRequest request) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, request.address());
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) TimeUnit.SECONDS.toMillis(ADMIN_TIMEOUT_SECONDS));
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) TimeUnit.SECONDS.toMillis(ADMIN_TIMEOUT_SECONDS));
        String username = request.username();
        if (username != null && !username.isBlank()) {
            props.put("security.protocol", "SASL_PLAINTEXT");
            props.put("sasl.mechanism", "PLAIN");
            props.put("sasl.jaas.config", "org.apache.kafka.common.security.plain.PlainLoginModule required "
                    + "username=\"" + username + "\" password=\"" + request.password() + "\";");
        }
        return AdminClient.create(props);
    }

    /** topics 查询：主题名 + 分区数 + 副本因子 */
    private MiddlewareQueryResult table(AdminClient admin, long t0, int limit) throws Exception {
        Set<String> names = admin.listTopics().names().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(String::compareTo);
        long total = sorted.size();
        List<Map<String, Object>> rows = new ArrayList<>();
        int n = 0;
        for (String name : sorted) {
            if (n >= Math.min(limit, MAX_PREVIEW)) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("topic", name);
            try {
                row.put("partitions", admin.describeTopics(Set.of(name)).topicNameValues().get(name)
                        .get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS).partitions().size());
            } catch (Exception e) {
                row.put("partitions", "-");
            }
            rows.add(row);
            n++;
        }
        return MiddlewareQueryResult.of(List.of("topic", "partitions"), rows, total > rows.size(), total,
                System.currentTimeMillis() - t0);
    }

    /** brokers 查询：节点 ID + 主机端口 */
    private MiddlewareQueryResult brokers(AdminClient admin, long t0) throws Exception {
        Collection<Node> nodes = admin.describeCluster().nodes().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Node node : nodes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("broker", "node-" + node.id());
            row.put("host", node.host() + ":" + node.port());
            row.put("rack", node.hasRack() ? node.rack() : "-");
            rows.add(row);
        }
        return MiddlewareQueryResult.of(List.of("broker", "host", "rack"), rows, false, rows.size(),
                System.currentTimeMillis() - t0);
    }

    /** groups 查询：消费组 + 主题 + 分区 + 已提交 offset - 末尾 offset（负值取 0） */
    private MiddlewareQueryResult groups(AdminClient admin, long t0) throws Exception {
        Collection<ConsumerGroupListing> listings = admin.listConsumerGroups().all()
                .get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ConsumerGroupListing listing : listings) {
            String group = listing.groupId();
            Map<TopicPartition, OffsetAndMetadata> committed;
            try {
                committed = admin.listConsumerGroupOffsets(group)
                        .partitionsToOffsetAndMetadata().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                committed = Map.of();
            }
            for (Map.Entry<TopicPartition, OffsetAndMetadata> e : committed.entrySet()) {
                TopicPartition tp = e.getKey();
                Long committedOffset = e.getValue() == null ? null : e.getValue().offset();
                long end;
                try {
                    end = admin.listOffsets(Map.of(tp, OffsetSpec.latest())).partitionResult(tp)
                            .get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS).offset();
                } catch (Exception ex) {
                    end = -1L;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("group", group);
                row.put("topic", tp.topic());
                row.put("partition", tp.partition());
                row.put("committed", committedOffset == null ? "-" : committedOffset);
                row.put("end", end < 0 ? "-" : end);
                row.put("lag", end < 0 || committedOffset == null ? "-" : Math.max(0L, end - committedOffset));
                rows.add(row);
            }
        }
        return MiddlewareQueryResult.of(
                List.of("group", "topic", "partition", "committed", "end", "lag"), rows, false, rows.size(),
                System.currentTimeMillis() - t0);
    }

    /** describe &lt;topic&gt;：主题的分区明细 */
    private MiddlewareQueryResult describe(AdminClient admin, long t0, String topic) throws Exception {
        if (topic.isEmpty()) {
            return MiddlewareQueryResult.fail("describe 需要主题名，如 describe orders");
        }
        var desc = admin.describeTopics(Set.of(topic)).topicNameValues().get(topic)
                .get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var p : desc.partitions()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("topic", topic);
            row.put("partition", p.partition());
            row.put("leader", p.leader() == null ? "-" : "node-" + p.leader().id());
            row.put("replicas", p.replicas().size());
            row.put("isr", p.isr().size());
            rows.add(row);
        }
        return MiddlewareQueryResult.of(
                List.of("topic", "partition", "leader", "replicas", "isr"), rows, false, rows.size(),
                System.currentTimeMillis() - t0);
    }

    /**
     * preview &lt;topic&gt;：从每个分区的**队首**读几条消息（只读预览）。
     *
     * <p>用独立的短生命周期 {@link KafkaConsumer}，{@code enable.auto.commit=false}、
     * {@code auto.offset.reset=earliest}，读完立即关闭 —— 绝不提交 offset，
     * 不影响真实消费者的消费位点。</p>
     */
    private MiddlewareQueryResult preview(MiddlewareConnectRequest request, long t0, String topic, int limit) {
        if (topic.isEmpty()) {
            return MiddlewareQueryResult.fail("preview 需要主题名，如 preview orders");
        }
        int n = Math.max(1, Math.min(limit <= 0 ? DEFAULT_PREVIEW : limit, MAX_PREVIEW));
        Properties props = new Properties();
        // 用字符串字面量而不是 ConsumerConfig 常量：该模块编译期走 module path，
        // 个别常量的解析不稳定，而 key 的取值是 Kafka 协议固定值，写死更可靠。
        props.put("bootstrap.servers", request.address());
        props.put("group.id", "middleware-preview-" + System.currentTimeMillis());
        props.put("enable.auto.commit", "false");
        props.put("auto.offset.reset", "earliest");
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("max.poll.records", String.valueOf(n));        List<Map<String, Object>> rows = new ArrayList<>();
        long total = 0L;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> partitions = new ArrayList<>();
            try (AdminClient admin = adminClient(request)) {
                var desc = admin.describeTopics(Set.of(topic)).topicNameValues().get(topic)
                        .get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                for (var p : desc.partitions()) {
                    partitions.add(new TopicPartition(topic, p.partition()));
                }
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));
            for (ConsumerRecord<String, String> rec : records) {
                if (rows.size() >= n) {
                    break;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("partition", rec.partition());
                row.put("offset", rec.offset());
                row.put("key", truncate(rec.key()));
                row.put("value", truncate(rec.value()));
                row.put("timestamp", rec.timestamp());
                rows.add(row);
            }
            total = rows.size();
        } catch (UnsupportedVersionException e) {
            return MiddlewareQueryResult.fail("Broker 版本过旧，不支持该查询");
        } catch (Exception e) {
            return MiddlewareQueryResult.fail(rootMessage(e));
        }
        return MiddlewareQueryResult.of(
                List.of("partition", "offset", "key", "value", "timestamp"), rows, false, total,
                System.currentTimeMillis() - t0);
    }

    /** 主题详情文档：分区 + leader + 副本 + ISR */
    private MiddlewareDocument topicDocument(AdminClient admin, String topic) throws Exception {
        var desc = admin.describeTopics(Set.of(topic)).topicNameValues().get(topic)
                .get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        StringBuilder sb = new StringBuilder();
        Map<String, String> attrs = new LinkedHashMap<>();
        for (var p : desc.partitions()) {
            sb.append("分区 ").append(p.partition())
                    .append("  leader=node-").append(p.leader() == null ? "-" : p.leader().id())
                    .append("  副本=").append(p.replicas().size())
                    .append("  ISR=").append(p.isr().size())
                    .append('\n');
            attrs.put("partition-" + p.partition(), "leader=node-"
                    + (p.leader() == null ? "-" : p.leader().id())
                    + " replicas=" + p.replicas().size() + " isr=" + p.isr().size());
        }
        return new MiddlewareDocument("主题 " + topic, sb.toString(), attrs);
    }

    /** 消费组详情文档：成员与各分区 lag */
    private MiddlewareDocument groupDocument(AdminClient admin, String group) throws Exception {
        DescribeConsumerGroupsResult result = admin.describeConsumerGroups(Set.of(group));
        var described = result.describedGroups().get(group).get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(group)
                .partitionsToOffsetAndMetadata().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        StringBuilder sb = new StringBuilder();
        long lag = 0L;
        for (Map.Entry<TopicPartition, OffsetAndMetadata> e : committed.entrySet()) {
            TopicPartition tp = e.getKey();
            Long committedOffset = e.getValue() == null ? null : e.getValue().offset();
            long end = admin.listOffsets(Map.of(tp, OffsetSpec.latest())).partitionResult(tp)
                    .get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS).offset();
            long l = committedOffset == null ? 0L : Math.max(0L, end - committedOffset);
            lag += l;
            sb.append(tp.topic()).append("-").append(tp.partition())
                    .append("  committed=").append(committedOffset)
                    .append("  end=").append(end)
                    .append("  lag=").append(l).append('\n');
        }
        sb.append("成员数=").append(described.members().size()).append("  总 lag=").append(lag);
        return new MiddlewareDocument("消费组 " + group, sb.toString(), Map.of());
    }

    /** Broker 详情文档 */
    private MiddlewareDocument brokerDocument(AdminClient admin, String idText) throws Exception {
        int id = Integer.parseInt(idText.trim());
        Collection<Node> nodes = admin.describeCluster().nodes().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        for (Node node : nodes) {
            if (node.id() == id) {
                return new MiddlewareDocument("Broker node-" + node.id(),
                        "host=" + node.host() + ":" + node.port()
                                + "\nrack=" + (node.hasRack() ? node.rack() : "-"),
                        Map.of("host", node.host() + ":" + node.port()));
            }
        }
        return null;
    }

    /** 截断超长消息体，避免响应体被单条大消息撑爆 */
    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= VALUE_TEXT_LIMIT ? s : s.substring(0, VALUE_TEXT_LIMIT) + "…(已截断)";
    }

    /** 取根因消息：Kafka 的异常常包好几层，最里层才有用 */
    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t instanceof ExecutionException && t.getCause() != null) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
}
