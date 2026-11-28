// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.mongodb.support.manage;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.datasource.support.middleware.mongodb.MongoCollectionInfo;
import com.chua.datasource.support.middleware.mongodb.MongoDatabaseInfo;
import com.chua.datasource.support.middleware.mongodb.MongoManageProvider;
import com.chua.datasource.support.middleware.mongodb.MongoManageTarget;
import com.chua.datasource.support.middleware.mongodb.MongoQueryResult;
import com.chua.datasource.support.middleware.mongodb.MongoTestResult;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.ListDatabasesIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

/**
 * MongoDB 管理面 SPI 实现：官方同步驱动（{@code mongodb-driver-sync}）。
 *
 * <p>驱动客户端是线程安全、自带连接池的重量级对象，按连接串缓存复用，不随请求创建销毁
 * （数据源配置变更会换连接串自然换新客户端，旧客户端留待进程退出释放）。</p>
 *
 * <p>驱动异常统一翻译成 {@link IllegalStateException} 的可读原因（宿主直接转业务错误码）；
 * 语句不合法保留 {@link IllegalArgumentException}；不返回演示数据。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
@Spi(value = "mongodb", order = 200)
public class MongoManageProviderImpl implements MongoManageProvider {

    /**
     * 查询默认返回行数（语句未显式 .limit 时）
     */
    public static final int DEFAULT_ROW_LIMIT = 50;

    /**
     * 单次查询最大返回行数
     */
    public static final int MAX_ROW_LIMIT = 500;

    /**
     * 字符串值截断长度：超长字段只截显示，避免响应体被单个文档撑爆
     */
    public static final int VALUE_TEXT_LIMIT = 4000;

    /**
     * 服务选主与建连超时：探测失败要在秒级返回，而不是驱动默认的 30 秒
     */
    private static final long CONNECT_TIMEOUT_MS = 5000L;

    /**
     * 单次读取超时（毫秒）
     */
    private static final long READ_TIMEOUT_MS = 15_000L;

    /**
     * 可读原因的最大长度
     */
    private static final int MESSAGE_LIMIT = 200;

    /**
     * 按连接串缓存的 MongoClient，键为完整连接串（含凭据），配置变更自然换新键
     */
    private final ConcurrentHashMap<String, MongoClient> clients = new ConcurrentHashMap<>();

    @Override
    public MongoTestResult test(MongoManageTarget target) {
        try {
            MongoClient client = client(target);
            // 先跑一句不碰业务数据的 {ping: 1}：地址不通、凭据不对都会在这一步暴露
            client.getDatabase(target.effectiveDatabase()).runCommand(new Document("ping", 1));
            String version = "";
            try {
                Document buildInfo = client.getDatabase(target.effectiveDatabase())
                        .runCommand(new Document("buildInfo", 1));
                Object value = buildInfo.get("version");
                version = value == null ? "" : String.valueOf(value);
            } catch (MongoException e) {
                log.debug("[mongodb] buildInfo 不可用，版本号留空: {}", e.getMessage());
            }
            return new MongoTestResult(true, version, "连接成功");
        } catch (IllegalStateException e) {
            return new MongoTestResult(false, null, e.getMessage());
        }
    }

    @Override
    public List<MongoDatabaseInfo> databases(MongoManageTarget target) {
        MongoClient client = client(target);
        ListDatabasesIterable<Document> iterable = client.listDatabases();
        List<MongoDatabaseInfo> result = new ArrayList<>();
        try (MongoCursor<Document> cursor = iterable.cursor()) {
            while (cursor.hasNext()) {
                Document row = cursor.next();
                result.add(new MongoDatabaseInfo(text(row.get("name")),
                        row.get("sizeOnDisk") instanceof Number n ? n.longValue() : null,
                        row.get("empty") instanceof Boolean b ? b : null));
            }
            return result;
        } catch (MongoException e) {
            throw failure(target, "读取逻辑库列表", e);
        }
    }

    @Override
    public List<MongoCollectionInfo> collections(MongoManageTarget target, String database) {
        MongoDatabase db = client(target).getDatabase(database(target, database));
        List<MongoCollectionInfo> result = new ArrayList<>();
        try (MongoCursor<Document> cursor = db.listCollections().cursor()) {
            while (cursor.hasNext()) {
                Document info = cursor.next();
                String name = String.valueOf(info.get("name"));
                Long estimated = null;
                try {
                    estimated = db.getCollection(name).estimatedDocumentCount();
                } catch (MongoException e) {
                    // 估算数拿不到不推翻集合存在这一事实
                    log.debug("[mongodb] 集合 {} 文档数估算失败: {}", name, e.getMessage());
                }
                result.add(new MongoCollectionInfo(name,
                        info.get("type") == null ? "collection" : String.valueOf(info.get("type")),
                        estimated));
            }
            return result;
        } catch (MongoException e) {
            throw failure(target, "读取集合列表", e);
        }
    }

    /**
     * 执行一句只读 shell 查询。
     *
     * <p>命中默认行数上限且未显式 limit 时，补一次 countDocuments 给出截断前总行数；
     * 聚合管道走驱动 {@code aggregate}，统一按 {@value #MAX_ROW_LIMIT} 封顶。</p>
     */
    @Override
    public MongoQueryResult query(MongoManageTarget target, String database, String expression) {
        MongoShell.Query query = MongoShell.parse(expression);
        MongoCollection<Document> collection = client(target)
                .getDatabase(database(target, database))
                .getCollection(query.collection());
        Document filter = Document.parse(MongoShell.normalizeJson(query.filter()));
        try {
            return switch (query.method()) {
                case "find", "findone" -> find(collection, query, filter);
                case "count", "countdocuments" -> count(collection, filter);
                default -> aggregate(collection, query);
            };
        } catch (MongoException e) {
            throw failure(target, "执行查询", e);
        }
    }

    @Override
    public Map<String, Object> document(MongoManageTarget target, String database, String collection, String id) {
        MongoDatabase db = client(target).getDatabase(database(target, database));
        Document filter;
        try {
            filter = new Document("_id", new ObjectId(id));
        } catch (IllegalArgumentException e) {
            filter = new Document("_id", id);
        }
        try {
            Document doc = db.getCollection(collection).find(filter).first();
            return doc == null ? null : toRow(doc);
        } catch (MongoException e) {
            throw failure(target, "读取文档", e);
        }
    }

    // ==================== 查询分支 ====================

    /**
     * find / findOne 分支。
     *
     * @param collection 集合
     * @param query      解析结果
     * @param filter     条件
     * @return 查询结果
     */
    private MongoQueryResult find(MongoCollection<Document> collection, MongoShell.Query query, Document filter) {
        boolean explicit = query.limit() != null;
        int limit = explicit ? Math.min(query.limit(), MAX_ROW_LIMIT) : DEFAULT_ROW_LIMIT;
        if ("findone".equals(query.method())) {
            limit = 1;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        try (MongoCursor<Document> cursor = collection
                .find(filter)
                .projection(Document.parse(MongoShell.normalizeJson(query.projection())))
                .sort(query.sort().isEmpty() ? new Document() : Document.parse(MongoShell.normalizeJson(query.sort())))
                .skip(query.skip())
                .limit(limit)
                .cursor()) {
            while (cursor.hasNext()) {
                rows.add(toRow(cursor.next()));
            }
        }
        if ("findone".equals(query.method())) {
            return new MongoQueryResult(rows, false, rows.size());
        }
        if (explicit) {
            return new MongoQueryResult(rows, query.limit() > MAX_ROW_LIMIT, rows.size());
        }
        // 未显式 limit：命中默认上限说明可能还有更多，补计数给出总行数
        if (rows.size() >= DEFAULT_ROW_LIMIT) {
            return new MongoQueryResult(rows, true, (int) Math.min(countQuietly(collection, filter),
                    Integer.MAX_VALUE));
        }
        return new MongoQueryResult(rows, false, rows.size());
    }

    /**
     * count / countDocuments 分支。
     *
     * @param collection 集合
     * @param filter     条件
     * @return 查询结果（单行 count）
     */
    private MongoQueryResult count(MongoCollection<Document> collection, Document filter) {
        long count = collection.countDocuments(filter);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("count", count);
        return new MongoQueryResult(List.of(row), false, 1);
    }

    /**
     * aggregate 分支。
     *
     * @param collection 集合
     * @param query      解析结果
     * @return 查询结果
     */
    private MongoQueryResult aggregate(MongoCollection<Document> collection, MongoShell.Query query) {
        List<Document> pipeline = parsePipeline(query.pipeline());
        List<Map<String, Object>> rows = new ArrayList<>();
        try (MongoCursor<Document> cursor = collection.aggregate(pipeline).allowDiskUse(true).cursor()) {
            while (cursor.hasNext()) {
                rows.add(toRow(cursor.next()));
            }
        }
        // 聚合无法预知总数，截断时总行数口径即为下发行数
        return new MongoQueryResult(rows, rows.size() >= MAX_ROW_LIMIT, rows.size());
    }

    /**
     * 解析聚合管道数组。
     *
     * <p>{@code Document.parse} 只认对象，顶层数组需包一层壳再取回。
     * {@code $out}/{@code $merge} 会把聚合结果写进其它集合，与「只读管理面」的承诺冲突，
     * 在此直接拒绝；末尾统一追加 {@code $limit} 封顶（AggregateIterable 没有 limit 方法）。</p>
     *
     * @param pipeline 管道 JSON 文本
     * @return 阶段文档列表
     */
    private List<Document> parsePipeline(String pipeline) {
        Document wrapper = Document.parse("{\"pipeline\":" + MongoShell.normalizeJson(pipeline) + "}");
        Object raw = wrapper.get("pipeline");
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("aggregate 参数需为阶段数组，如 [{$match:{...}}]");
        }
        List<Document> stages = new ArrayList<>();
        for (Object stage : list) {
            if (!(stage instanceof Document doc)) {
                throw new IllegalArgumentException("aggregate 阶段需为对象: " + stage);
            }
            for (String key : doc.keySet()) {
                if ("$out".equals(key) || "$merge".equals(key)) {
                    throw new IllegalArgumentException("aggregate 不支持写阶段 " + key + "：管理面只读");
                }
            }
            stages.add(doc);
        }
        stages.add(new Document("$limit", MAX_ROW_LIMIT));
        return stages;
    }

    /**
     * 计数兜底：拿不到时回落下发行数，不把整次查询判失败。
     *
     * @param collection 集合
     * @param filter     条件
     * @return 总行数
     */
    private long countQuietly(MongoCollection<Document> collection, Document filter) {
        try {
            return collection.countDocuments(filter);
        } catch (MongoException e) {
            log.debug("[mongodb] 截断计数失败，回落下发行数: {}", e.getMessage());
            return DEFAULT_ROW_LIMIT;
        }
    }

    // ==================== 连接与会话 ====================

    /**
     * 取（必要时新建）驱动客户端。
     *
     * @param target 连接目标
     * @return 客户端
     * @throws IllegalStateException 地址不可解析
     */
    private MongoClient client(MongoManageTarget target) {
        String connectionString = target.connectionString();
        return clients.computeIfAbsent(connectionString, this::createClient);
    }

    /**
     * 目标里的逻辑库名（空按默认库）。
     *
     * @param target   连接目标
     * @param database 请求指定的逻辑库
     * @return 逻辑库名
     */
    private static String database(MongoManageTarget target, String database) {
        return database == null || database.isBlank() ? target.effectiveDatabase() : database.trim();
    }

    /**
     * 创建带超时的驱动客户端。
     *
     * @param connectionString 连接串
     * @return 客户端（懒连接，不在此刻真正建连）
     */
    private MongoClient createClient(String connectionString) {
        return MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(connectionString))
                .applyToClusterSettings(builder -> builder
                        .serverSelectionTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .applyToSocketSettings(builder -> builder
                        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .build());
    }

    /**
     * 驱动异常 → 带上下文的业务异常。
     *
     * @param target 连接目标
     * @param action 操作描述
     * @param e      驱动异常
     * @return 业务异常
     */
    private static IllegalStateException failure(MongoManageTarget target, String action, MongoException e) {
        String reason = readableMessage(e);
        log.warn("[mongodb] 数据源 {}({}) {}失败: {}", target.name(), target.host(), action, reason);
        return new IllegalStateException("MongoDB " + target.name() + "(" + target.host() + ") "
                + action + "失败: " + reason, e);
    }

    /**
     * 驱动异常 → 可读中文原因。
     *
     * @param e 驱动异常
     * @return 原因文本
     */
    private static String readableMessage(Throwable e) {
        if (e instanceof MongoTimeoutException) {
            return "连接超时：地址不可达或凭据错误（5 秒内未选到主节点）";
        }
        if (e instanceof MongoCommandException command) {
            int code = command.getErrorCode();
            if (code == 18) {
                return "认证失败：用户名或密码错误";
            }
            if (code == 13) {
                return "权限不足：当前用户无权执行该操作";
            }
            return firstLine(command.getErrorMessage());
        }
        if (e instanceof MongoSecurityException) {
            return "认证失败: " + firstLine(e.getMessage());
        }
        if (e instanceof MongoSocketException) {
            return "连接失败: " + firstLine(e.getMessage());
        }
        return firstLine(e.getMessage());
    }

    /**
     * 取消息首行并截断。
     *
     * @param message 原始消息
     * @return 首行截断结果
     */
    private static String firstLine(String message) {
        if (StringUtils.isEmpty(message)) {
            return "未知错误";
        }
        String line = message.split("\n")[0].trim();
        return line.length() > MESSAGE_LIMIT ? line.substring(0, MESSAGE_LIMIT) + "…" : line;
    }

    /**
     * 一行文档归一化为「键 → JSON 友好值」。
     *
     * @param doc 原始文档
     * @return 归一化行
     */
    private Map<String, Object> toRow(Document doc) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : doc.entrySet()) {
            row.put(entry.getKey(), toJsonValue(entry.getValue()));
        }
        return row;
    }

    /**
     * BSON 值归一化：ObjectId 走十六进制、日期走 ISO 串、二进制走 Base64、
     * 嵌套文档/数组递归，超长字符串截断，其余原样透传。
     *
     * @param value 原始值
     * @return 归一化值
     */
    private Object toJsonValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof ObjectId oid) {
            return oid.toHexString();
        }
        if (value instanceof Date date) {
            return date.toInstant().toString();
        }
        if (value instanceof Decimal128 decimal) {
            return decimal.toString();
        }
        if (value instanceof Binary binary) {
            return Base64.getEncoder().encodeToString(binary.getData());
        }
        if (value instanceof Document doc) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : doc.entrySet()) {
                nested.put(entry.getKey(), toJsonValue(entry.getValue()));
            }
            return nested;
        }
        if (value instanceof List<?> list) {
            List<Object> nested = new ArrayList<>();
            for (Object item : list) {
                nested.add(toJsonValue(item));
            }
            return nested;
        }
        if (value instanceof String text && text.length() > VALUE_TEXT_LIMIT) {
            return text.substring(0, VALUE_TEXT_LIMIT) + "…(已截断)";
        }
        return value;
    }

    /**
     * 值转非空文本。
     *
     * @param value 原始值
     * @return 文本，null 为空串
     */
    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
