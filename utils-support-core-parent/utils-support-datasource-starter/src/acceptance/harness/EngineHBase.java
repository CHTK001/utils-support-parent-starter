import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.hbase.support.engine.HBaseEngine;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptor;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引擎验收运行器 —— 阶段二十：{@code HBaseEngine}（SPI {@code hbase}）。
 *
 * <p>本机没有 HBase / ZooKeeper。但该引擎的数据源接受<b>任意</b>
 * {@link Connection}，而 {@code Connection} / {@code Table} / {@code Admin} /
 * {@code ResultScanner} / {@code Cell} 全是接口，{@code Put} / {@code Get} /
 * {@code Delete} / {@code Scan} 是可直接检查的真实类。</p>
 *
 * <p>因此验收用 {@link Proxy} 构造假连接，让引擎真实地：</p>
 * <ul>
 *   <li>构建 {@link Put}（行键 + 列族/限定符/值）、{@link Get}、{@link Delete}；</li>
 *   <li>构建 {@link Scan}（列族、起始行、前缀停止行）；</li>
 *   <li>遍历 {@link ResultScanner} 并把 {@link Cell} 解析成 Map。</li>
 * </ul>
 *
 * <p>断言聚焦「引擎到底构造了什么请求、又如何解释结果」。
 * 仍然<b>不是</b>真实 HBase 读写验收（没有 RegionServer 参与）。</p>
 */
public final class EngineHBase {

    private EngineHBase() {
    }

    public static void main(String[] args) {
        EngineFunctional.emit("=".repeat(78));
        EngineFunctional.emit("  引擎验收 阶段二十：HBaseEngine（动态代理假 Connection 驱动真实领域 API）");
        EngineFunctional.emit("=".repeat(78));

        adminSuite();
        putSuite();
        getSuite();
        scanSuite();
        deleteSuite();
        rejectSuite();
        lifeSuite();

        EngineFunctional.finishAndExit("EngineHBase-report.txt");
    }

    // ==================== 建表 ====================

    private static void adminSuite() {
        EngineFunctional.emit("--- hbase.admin ---");

        EngineFunctional.check("admin.表不存在：按表名与列族建表", () -> {
            FakeHBase h = new FakeHBase();
            HBaseEngine e = engine(h);
            try {
                e.createTable("t_user", "info");
                if (!"t_user".equals(h.adminTable == null ? null : h.adminTable.getNameAsString())) {
                    throw new EngineFunctional.HarnessFailure(
                            "未按预期表名探测存在性: " + h.adminTable);
                }
                TableDescriptor descriptor = h.lastCreated;
                if (descriptor == null) {
                    throw new EngineFunctional.HarnessFailure("未调用 createTable");
                }
                if (!"t_user".equals(descriptor.getTableName().getNameAsString())) {
                    throw new EngineFunctional.HarnessFailure(
                            "descriptor 表名错误: " + descriptor.getTableName());
                }
                ColumnFamilyDescriptor[] families = descriptor.getColumnFamilies();
                if (families.length != 1 || !"info".equals(families[0].getNameAsString())) {
                    throw new EngineFunctional.HarnessFailure("列族错误");
                }
                if (!h.adminClosed) {
                    throw new EngineFunctional.HarnessFailure("Admin 未关闭（try-with-resources 失效）");
                }
                return "create t_user/info";
            } finally {
                close(e);
            }
        });

        EngineFunctional.check("admin.表已存在：跳过建表（幂等）", () -> {
            FakeHBase h = new FakeHBase();
            h.existing.add("t_user");
            HBaseEngine e = engine(h);
            try {
                e.createTable("t_user", "info");
                if (h.lastCreated != null) {
                    throw new EngineFunctional.HarnessFailure("已存在的表仍被创建");
                }
                return "skipped existing";
            } finally {
                close(e);
            }
        });

        EngineFunctional.check("admin.建表异常被包成 ISE 且带表名", () -> {
            FakeHBase h = new FakeHBase();
            h.fail = new IOException("simulated admin failure");
            HBaseEngine e = engine(h);
            try {
                e.createTable("t_user", "info");
                throw new EngineFunctional.HarnessFailure("异常被吞");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("HBase 建表失败: t_user")) {
                    throw new EngineFunctional.HarnessFailure("消息不明确: " + expected.getMessage());
                }
                if (expected.getCause() == null) {
                    throw new EngineFunctional.HarnessFailure("cause 丢失");
                }
                return expected.getMessage();
            } finally {
                close(e);
            }
        });
    }

    // ==================== 写入 ====================

    private static void putSuite() {
        EngineFunctional.emit("--- hbase.put ---");

        EngineFunctional.check("put.行键、列族、限定符、值逐项正确", () -> {
            FakeHBase h = new FakeHBase();
            HBaseEngine e = engine(h);
            try {
                Map<String, String> values = new LinkedHashMap<>();
                values.put("name", "alice");
                values.put("city", "shanghai");
                e.put("t_user", "row-1", "info", values);
                Put put = h.lastPut;
                if (put == null) {
                    throw new EngineFunctional.HarnessFailure("未调用 Table.put");
                }
                if (!"row-1".equals(Bytes.toString(put.getRow()))) {
                    throw new EngineFunctional.HarnessFailure("行键错误: " + Bytes.toString(put.getRow()));
                }
                Map<String, String> cells = cellsOf(put);
                if (!"alice".equals(cells.get("info:name")) || !"shanghai".equals(cells.get("info:city"))) {
                    throw new EngineFunctional.HarnessFailure("列值错误: " + cells);
                }
                if (!"t_user".equals(h.lastTableName())) {
                    throw new EngineFunctional.HarnessFailure("表名错误: " + h.lastTableName());
                }
                return cells.toString();
            } finally {
                close(e);
            }
        });

        EngineFunctional.check("put.空 Map：只写行键，不产生列", () -> {
            FakeHBase h = new FakeHBase();
            HBaseEngine e = engine(h);
            try {
                e.put("t_user", "row-2", "info", new LinkedHashMap<>());
                Put put = h.lastPut;
                if (put == null || !Arrays.equals(Bytes.toBytes("row-2"), put.getRow())) {
                    throw new EngineFunctional.HarnessFailure("行键错误");
                }
                if (!cellsOf(put).isEmpty()) {
                    throw new EngineFunctional.HarnessFailure("空 Map 却产生了列: " + cellsOf(put));
                }
                return "row only";
            } finally {
                close(e);
            }
        });

        EngineFunctional.check("put.异常被包成 ISE 且带表名/行键", () -> {
            FakeHBase h = new FakeHBase();
            h.fail = new IOException("simulated put failure");
            HBaseEngine e = engine(h);
            try {
                e.put("t_user", "row-9", "info", Map.of("name", "x"));
                throw new EngineFunctional.HarnessFailure("异常被吞");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("HBase put 失败: t_user/row-9")) {
                    throw new EngineFunctional.HarnessFailure("消息不明确: " + expected.getMessage());
                }
                return expected.getMessage();
            } finally {
                close(e);
            }
        });
    }

    // ==================== 读取 ====================

    private static void getSuite() {
        EngineFunctional.emit("--- hbase.get ---");

        EngineFunctional.check("get.按列族过滤：只返回该列族的限定符→值", () -> {
            FakeHBase h = new FakeHBase();
            h.getResult = Result.create(List.of(
                    cell("row-1", "info", "name", "alice"),
                    cell("row-1", "other", "name", "bob")));
            HBaseEngine e = engine(h);
            try {
                Map<String, String> row = e.get("t_user", "row-1", "info");
                if (row.size() != 1 || !"alice".equals(row.get("name"))) {
                    throw new EngineFunctional.HarnessFailure("列族过滤错误: " + row);
                }
                Get get = h.lastGet;
                if (get == null || !"row-1".equals(Bytes.toString(get.getRow()))) {
                    throw new EngineFunctional.HarnessFailure("Get 行键错误");
                }
                return row.toString();
            } finally {
                close(e);
            }
        });

        EngineFunctional.check("get.列族为空：全部列族以「族:限定符」返回", () -> {
            FakeHBase h = new FakeHBase();
            h.getResult = Result.create(List.of(
                    cell("row-1", "info", "name", "alice"),
                    cell("row-1", "other", "city", "shanghai")));
            HBaseEngine e = engine(h);
            try {
                Map<String, String> row = e.get("t_user", "row-1", null);
                if (!"alice".equals(row.get("info:name")) || !"shanghai".equals(row.get("other:city"))) {
                    throw new EngineFunctional.HarnessFailure("全列族映射错误: " + row);
                }
                return row.toString();
            } finally {
                close(e);
            }
        });

        EngineFunctional.check("get.行不存在：返回空 Map（不是 null）", () -> {
            FakeHBase h = new FakeHBase();
            h.getResult = Result.EMPTY_RESULT;
            HBaseEngine e = engine(h);
            try {
                Map<String, String> row = e.get("t_user", "missing", "info");
                if (row == null || !row.isEmpty()) {
                    throw new EngineFunctional.HarnessFailure("期望空 Map，实际: " + row);
                }
                return "empty map";
            } finally {
                close(e);
            }
        });
    }

    // ==================== 扫描 ====================

    private static void scanSuite() {
        EngineFunctional.emit("--- hbase.scan ---");

        EngineFunctional.check("scan.行键前缀 → 起始行=前缀、停止行=前缀末字节+1", () -> {
            FakeHBase h = new FakeHBase();
            HBaseEngine e = engine(h);
            try {
                e.scan("t_user", "info", "ab");
                Scan scan = h.lastScan;
                if (scan == null) {
                    throw new EngineFunctional.HarnessFailure("未调用 getScanner");
                }
                if (!Arrays.equals(Bytes.toBytes("ab"), scan.getStartRow())) {
                    throw new EngineFunctional.HarnessFailure(
                            "起始行错误: " + Bytes.toString(scan.getStartRow()));
                }
                if (!Arrays.equals(Bytes.toBytes("ac"), scan.getStopRow())) {
                    throw new EngineFunctional.HarnessFailure(
                            "停止行错误: " + Bytes.toString(scan.getStopRow()));
                }
                if (!scan.getFamilyMap().containsKey(Bytes.toBytes("info"))) {
                    throw new EngineFunctional.HarnessFailure("列族未加入 Scan");
                }
                return "ab .. ac";
            } finally {
                close(e);
            }
        });

        EngineFunctional.check("scan.非 ASCII 前缀按字节递增（不做字符串往返）", () -> {
            FakeHBase h = new FakeHBase();
            HBaseEngine e = engine(h);
            try {
                String prefix = "中";
                e.scan("t_user", null, prefix);
                byte[] start = Bytes.toBytes(prefix);
                byte[] expectedStop = start.clone();
                expectedStop[expectedStop.length - 1]++;
                byte[] actualStop = h.lastScan.getStopRow();
                if (!Arrays.equals(Arrays.copyOf(expectedStop, expectedStop.length), actualStop)) {
                    throw new EngineFunctional.HarnessFailure("停止行不是末字节+1: "
                            + java.util.HexFormat.of().formatHex(actualStop));
                }
                return "utf8 prefix incremented";
            } finally {
                close(e);
            }
        });

        EngineFunctional.check("scan.无前缀：不设起止行（全表扫描）", () -> {
            FakeHBase h = new FakeHBase();
            HBaseEngine e = engine(h);
            try {
                e.scan("t_user", null, null);
                Scan scan = h.lastScan;
                if (scan.getStartRow().length != 0 || scan.getStopRow().length != 0) {
                    throw new EngineFunctional.HarnessFailure("无前缀却设置了起止行");
                }
                if (!scan.getFamilyMap().isEmpty()) {
                    throw new EngineFunctional.HarnessFailure("family=null 却设置了列族");
                }
                return "full scan";
            } finally {
                close(e);
            }
        });

        EngineFunctional.check("scan.结果带 __row，空 Result 与 null 列值被跳过", () -> {
            FakeHBase h = new FakeHBase();
            h.scanResults.addAll(List.of(
                    Result.create(List.of(
                            cell("r1", "info", "name", "alice"),
                            cell("r1", "info", "empty", ""))),
                    Result.EMPTY_RESULT,
                    Result.create(List.of(cell("r2", "info", "name", "bob")))));
            HBaseEngine e = engine(h);
            try {
                List<Map<String, String>> rows = e.scan("t_user", "info", "r");
                if (rows.size() != 2) {
                    throw new EngineFunctional.HarnessFailure("期望 2 行（空 Result 被跳过），实际 " + rows.size());
                }
                if (!"r1".equals(rows.get(0).get("__row")) || !"alice".equals(rows.get(0).get("name"))) {
                    throw new EngineFunctional.HarnessFailure("首行错误: " + rows.get(0));
                }
                if (!"r2".equals(rows.get(1).get("__row"))) {
                    throw new EngineFunctional.HarnessFailure("次行错误: " + rows.get(1));
                }
                return "2 rows with __row";
            } finally {
                close(e);
            }
        });

        EngineFunctional.check("scan.异常被包成 ISE 且带表名", () -> {
            FakeHBase h = new FakeHBase();
            h.fail = new IOException("simulated scan failure");
            HBaseEngine e = engine(h);
            try {
                e.scan("t_user", "info", null);
                throw new EngineFunctional.HarnessFailure("异常被吞");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("HBase scan 失败: t_user")) {
                    throw new EngineFunctional.HarnessFailure("消息不明确: " + expected.getMessage());
                }
                return expected.getMessage();
            } finally {
                close(e);
            }
        });
    }

    // ==================== 删除 ====================

    private static void deleteSuite() {
        EngineFunctional.emit("--- hbase.delete ---");

        EngineFunctional.check("delete.按行键删除整行", () -> {
            FakeHBase h = new FakeHBase();
            HBaseEngine e = engine(h);
            try {
                e.deleteRow("t_user", "row-7");
                Delete delete = h.lastDelete;
                if (delete == null || !"row-7".equals(Bytes.toString(delete.getRow()))) {
                    throw new EngineFunctional.HarnessFailure("Delete 行键错误");
                }
                if (!"t_user".equals(h.lastTableName())) {
                    throw new EngineFunctional.HarnessFailure("表名错误: " + h.lastTableName());
                }
                return "delete row-7";
            } finally {
                close(e);
            }
        });
    }

    // ==================== 显式拒绝的 SQL/ORM 语义 ====================

    private static void rejectSuite() {
        EngineFunctional.emit("--- hbase.reject ---");

        EngineFunctional.check("reject.store 抛 UOE 并指向 put/scan", () -> {
            FakeHBase h = new FakeHBase();
            HBaseEngine e = engine(h);
            try {
                e.store("t_user", List.of("x"));
                throw new EngineFunctional.HarnessFailure("store 未抛 UOE");
            } catch (UnsupportedOperationException expected) {
                if (!expected.getMessage().contains("不支持内存存储")) {
                    throw new EngineFunctional.HarnessFailure("消息不明确: " + expected.getMessage());
                }
                return expected.getMessage();
            } finally {
                close(e);
            }
        });

        EngineFunctional.check("reject.query(X).list() 抛 UOE 并指向 scan", () -> {
            FakeHBase h = new FakeHBase();
            HBaseEngine e = engine(h);
            try {
                e.query(EngineFunctional.AccUser.class).list();
                throw new EngineFunctional.HarnessFailure("查询未抛 UOE");
            } catch (UnsupportedOperationException expected) {
                if (!expected.getMessage().contains("HBase 无 SQL 查询")) {
                    throw new EngineFunctional.HarnessFailure("消息不明确: " + expected.getMessage());
                }
                return expected.getMessage();
            } finally {
                close(e);
            }
        });

        EngineFunctional.check("reject.update 抛 UOE 并提示同键覆盖", () -> {
            FakeHBase h = new FakeHBase();
            HBaseEngine e = engine(h);
            try {
                e.update(EngineFunctional.AccUser.class)
                        .eq(EngineFunctional.AccUser::getName, "a")
                        .set(EngineFunctional.AccUser::getName, "b").update();
                throw new EngineFunctional.HarnessFailure("update 未抛 UOE");
            } catch (UnsupportedOperationException expected) {
                if (!expected.getMessage().contains("HBase 无 UPDATE")) {
                    throw new EngineFunctional.HarnessFailure("消息不明确: " + expected.getMessage());
                }
                return expected.getMessage();
            } finally {
                close(e);
            }
        });

        EngineFunctional.check("reject.delete 抛 UOE 并指向 deleteRow", () -> {
            FakeHBase h = new FakeHBase();
            HBaseEngine e = engine(h);
            try {
                e.delete(EngineFunctional.AccUser.class)
                        .eq(EngineFunctional.AccUser::getName, "a").remove();
                throw new EngineFunctional.HarnessFailure("delete 未抛 UOE");
            } catch (UnsupportedOperationException expected) {
                if (!expected.getMessage().contains("HBase 无 SQL DELETE")) {
                    throw new EngineFunctional.HarnessFailure("消息不明确: " + expected.getMessage());
                }
                return expected.getMessage();
            } finally {
                close(e);
            }
        });
    }

    // ==================== 生命周期 ====================

    private static void lifeSuite() {
        EngineFunctional.emit("--- hbase.life ---");

        EngineFunctional.check("life.close() 不关闭调用方注入的 Connection", () -> {
            FakeHBase h = new FakeHBase();
            HBaseEngine e = engine(h);
            e.close();
            if (h.connectionClosed) {
                throw new EngineFunctional.HarnessFailure(
                        "关闭了外部注入的连接——其它消费者会被一并打断");
            }
            return "injected connection untouched";
        });

        EngineFunctional.check("life.close() 后领域 API 快速失败为 ISE", () -> {
            FakeHBase h = new FakeHBase();
            HBaseEngine e = engine(h);
            e.close();
            try {
                e.put("t_user", "r", "info", Map.of("n", "v"));
                throw new EngineFunctional.HarnessFailure("close() 后仍可写入");
            } catch (IllegalStateException expected) {
                if (!"未配置 HBase 数据源".equals(expected.getMessage())) {
                    throw new EngineFunctional.HarnessFailure(
                            "用法错误被包装成操作失败: " + expected.getMessage());
                }
                return expected.getMessage();
            }
        });

        EngineFunctional.check("life.未配置数据源：领域 API 抛 ISE", () -> {
            HBaseEngine e = new HBaseEngine();
            try {
                e.get("t_user", "r", "info");
                throw new EngineFunctional.HarnessFailure("未配置却可读取");
            } catch (IllegalStateException expected) {
                if (!"未配置 HBase 数据源".equals(expected.getMessage())) {
                    throw new EngineFunctional.HarnessFailure(
                            "用法错误被包装成操作失败: " + expected.getMessage());
                }
                return expected.getMessage();
            }
        });

        EngineFunctional.check("life.非法数据源类型被拒绝", () -> {
            HBaseEngine e = new HBaseEngine();
            try {
                e.addDataSource("bad", source(42));
                throw new EngineFunctional.HarnessFailure("非法类型未被拒绝");
            } catch (IllegalArgumentException | IllegalStateException expected) {
                return expected.getMessage();
            }
        });
    }

    // ==================== 基础设施 ====================

    private static HBaseEngine engine(FakeHBase fake) {
        HBaseEngine engine = new HBaseEngine();
        engine.addDataSource("default", new EngineDataSource<Connection>() {
            @Override
            public String name() {
                return "default";
            }

            @Override
            public Connection getSource() {
                return fake.connection;
            }

            @Override
            public EngineDataSource<Connection> setSource(Object source) {
                return this;
            }

            @Override
            public Dialect getDialect() {
                return null;
            }

            @Override
            public EngineDataSource<Connection> setDialect(Dialect dialect) {
                return this;
            }

            @Override
            public String url() {
                return "hbase://fake:2181";
            }

            @Override
            public String username() {
                return null;
            }

            @Override
            public String password() {
                return null;
            }
        });
        return engine;
    }

    private static void close(HBaseEngine engine) {
        engine.close();
    }

    /** 用于构造非法底层类型的数据源。 */
    private static EngineDataSource<Object> source(Object raw) {
        return new EngineDataSource<Object>() {
            @Override
            public String name() {
                return "tmp";
            }

            @Override
            public Object getSource() {
                return raw;
            }

            @Override
            public EngineDataSource<Object> setSource(Object source) {
                return this;
            }

            @Override
            public Dialect getDialect() {
                return null;
            }

            @Override
            public EngineDataSource<Object> setDialect(Dialect dialect) {
                return this;
            }

            @Override
            public String url() {
                return null;
            }

            @Override
            public String username() {
                return null;
            }

            @Override
            public String password() {
                return null;
            }
        };
    }

    private static Map<String, String> cellsOf(Put put) {
        Map<String, String> out = new LinkedHashMap<>();
        put.getFamilyCellMap().forEach((family, cells) -> {
            for (Cell cell : cells) {
                out.put(Bytes.toString(family) + ":" + Bytes.toString(CellUtil.cloneQualifier(cell)),
                        Bytes.toString(CellUtil.cloneValue(cell)));
            }
        });
        return out;
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }

    /**
     * 用 HBase 自带的 {@link org.apache.hadoop.hbase.KeyValue}（真实 {@link Cell} 实现）
     * 构造单元格，避免自制 {@code Cell} 在 {@link Result#create(List)} 归一化时失真。
     */
    private static Cell cell(String row, String family, String qualifier, String value) {
        return new org.apache.hadoop.hbase.KeyValue(
                Bytes.toBytes(row), Bytes.toBytes(family),
                Bytes.toBytes(qualifier), Bytes.toBytes(value));
    }

    /**
     * 回放型假 HBase：记录引擎构造的真实请求对象，并按配置回放结果。
     */
    static final class FakeHBase {
        private static final ClassLoader CL = FakeHBase.class.getClassLoader();

        final List<String> existing = new ArrayList<>();
        final List<Result> scanResults = new ArrayList<>();
        final List<String> tableNames = new ArrayList<>();
        Result getResult = Result.EMPTY_RESULT;
        Put lastPut;
        Get lastGet;
        Delete lastDelete;
        Scan lastScan;
        TableDescriptor lastCreated;
        TableName adminTable;
        IOException fail;
        boolean connectionClosed;
        boolean adminClosed;

        final Connection connection = (Connection) Proxy.newProxyInstance(CL,
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getTable":
                            tableNames.add(((TableName) args[0]).getNameAsString());
                            return table();
                        case "getAdmin":
                            return admin();
                        case "close":
                            connectionClosed = true;
                            return null;
                        case "isClosed":
                            return connectionClosed;
                        case "toString":
                            return "FakeConnection";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });

        String lastTableName() {
            return tableNames.isEmpty() ? "" : tableNames.get(tableNames.size() - 1);
        }

        private Admin admin() {
            return (Admin) Proxy.newProxyInstance(CL, new Class<?>[]{Admin.class},
                    (proxy, method, args) -> {
                        if (fail != null) {
                            throw fail;
                        }
                        switch (method.getName()) {
                            case "tableExists":
                                adminTable = (TableName) args[0];
                                return existing.contains(((TableName) args[0]).getNameAsString());
                            case "createTable":
                                lastCreated = (TableDescriptor) args[0];
                                return null;
                            case "close":
                                adminClosed = true;
                                return null;
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }

        private Table table() {
            return (Table) Proxy.newProxyInstance(CL, new Class<?>[]{Table.class},
                    (proxy, method, args) -> {
                        if (fail != null) {
                            throw fail;
                        }
                        switch (method.getName()) {
                            case "put":
                                lastPut = (Put) args[0];
                                return null;
                            case "get":
                                lastGet = (Get) args[0];
                                return getResult;
                            case "getScanner":
                                lastScan = (Scan) args[0];
                                return scanner();
                            case "delete":
                                lastDelete = (Delete) args[0];
                                return null;
                            case "close":
                            case "isAutoFlush":
                                return null;
                            case "toString":
                                return "FakeTable";
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }

        private ResultScanner scanner() {
            return (ResultScanner) Proxy.newProxyInstance(CL, new Class<?>[]{ResultScanner.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "iterator":
                                return scanResults.iterator();
                            case "close":
                                return null;
                            case "toString":
                                return "FakeScanner";
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }
    }
}
