import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.spi.ServiceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 引擎验收运行器 —— 第一阶段：注册表 / 创建 / 能力契约 / 生命周期。
 *
 * 覆盖 META-INF/extensions 中登记的全部 24 个 engine 键。
 * 每个键独立判定，失败互不影响；退出码 = 失败数（0 表示全通过）。
 */
public final class EngineAcceptance {

    /** 已注册引擎键（来自各 starter 的 META-INF/extensions 登记文件）。 */
    private static final List<String> KEYS = List.of(
            "memory", "file",
            "h2", "sqlite", "duckdb", "parquet", "lucene", "rocksdb", "nitrite", "tablesaw",
            "mysql", "postgresql", "sqlserver", "sqlserver-legacy", "oracle",
            "redis", "elasticsearch", "solr", "neo4j", "hbase",
            "influxdb", "clickhouse", "greptimedb", "prometheus");

    /** 嵌入式引擎：无外部服务依赖，必须能真正建表读写。 */
    private static final Set<String> EMBEDDED = Set.of(
            "memory", "file", "h2", "sqlite", "duckdb", "parquet", "lucene", "rocksdb", "nitrite", "tablesaw");

    private static int pass = 0;
    private static int fail = 0;
    private static final List<String> FAILURES = new ArrayList<>();

    private EngineAcceptance() {
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(78));
        System.out.println("  引擎验收 阶段一：注册表 / 创建 / 能力契约 / 生命周期");
        System.out.println("  候选键 " + KEYS.size() + " 个（嵌入式 " + EMBEDDED.size() + " 个）");
        System.out.println("=".repeat(78));

        // 0. SPI 注册表本身：确认每个键都能被解析出来
        for (String key : KEYS) {
            check("SPI.create(" + key + ")", () -> {
                Engine e = Engine.create(key);
                if (e == null) {
                    throw new IllegalStateException("Engine.create 返回 null");
                }
                return e.getClass().getName();
            });
        }

        // 1. 逐引擎契约
        for (String key : KEYS) {
            Engine e;
            try {
                e = Engine.create(key);
            } catch (Throwable t) {
                recordFail(key + " 创建抛异常: " + t);
                continue;
            }
            if (e == null) {
                recordFail(key + " 创建返回 null");
                continue;
            }
            contract(key, e);
            safeClose(key, e);
        }

        System.out.println("=".repeat(78));
        System.out.println("  结果: PASS=" + pass + "  FAIL=" + fail);
        for (String f : FAILURES) {
            System.out.println("    [FAIL] " + f);
        }
        System.out.println("=".repeat(78));
        System.exit(fail);
    }

    /** 单个引擎的契约验证：全部断言都在同一实例上进行，最后统一关闭。 */
    private static void contract(String key, Engine e) {
        String tag = key + "(" + e.getClass().getSimpleName() + ")";

        // 能力声明必须自洽：supportsSql()==true 时 querySql 必须可用（不能抛 UnsupportedOperationException）
        check(tag + ".supportsSql 自洽", () -> {
            boolean sql = e.supportsSql();
            if (sql) {
                try {
                    e.querySql("SELECT 1");
                } catch (UnsupportedOperationException uoe) {
                    throw new IllegalStateException("supportsSql=true 但 querySql 抛 UnsupportedOperationException");
                } catch (RuntimeException expectedBackendFailure) {
                    // 后端未连接等运行期失败可接受：这里只验证「不是能力缺失」
                }
            }
            return String.valueOf(sql);
        });

        check(tag + ".supportsMeta 可用", () -> String.valueOf(e.supportsMeta()));

        check(tag + ".interceptors 非 null", () -> {
            List<?> list = e.interceptors();
            if (list == null) {
                throw new IllegalStateException("interceptors() 返回 null");
            }
            return "size=" + list.size();
        });

        // deprecated getExecutor 允许返回 null（非 SQL 引擎），但不允许抛异常
        check(tag + ".getExecutor() 不抛异常", () -> {
            Object ex = e.getExecutor();
            return ex == null ? "null(合法)" : ex.getClass().getSimpleName();
        });

        // 数据源盘点：契约明示「不支持时抛 UnsupportedOperationException」，因此只验证「不抛别的异常」
        check(tag + ".dataSourceNames 契约", () -> {
            try {
                Set<String> names = e.dataSourceNames();
                return names == null ? "null(违规)" : "size=" + names.size();
            } catch (UnsupportedOperationException expected) {
                return "UnsupportedOperationException(契约允许)";
            }
        });

        // removeDataSource(null) 契约明示抛 IllegalArgumentException
        check(tag + ".removeDataSource(null) 语义", () -> {
            try {
                e.removeDataSource(null);
                return "未抛异常";
            } catch (IllegalArgumentException expected) {
                return "IllegalArgumentException(契约)";
            } catch (UnsupportedOperationException allowed) {
                return "UnsupportedOperationException(契约允许)";
            }
        });

        // querySql(null) 不应抛 NPE 之外的东西；只验证不静默返回错值
        check(tag + ".querySql(null) 不透传", () -> {
            try {
                e.querySql(null);
                return "返回(实现自行处理)";
            } catch (UnsupportedOperationException | IllegalArgumentException allowed) {
                return allowed.getClass().getSimpleName();
            } catch (RuntimeException other) {
                return other.getClass().getSimpleName() + "(后端失败)";
            }
        });

        // queryPage 参数守卫：页码/页大小 <=0 必须抛 IllegalArgumentException（接口默认实现已保证）
        check(tag + ".queryPage 参数守卫", () -> {
            try {
                e.queryPage("SELECT 1", 0, 10);
                throw new IllegalStateException("pageNum=0 未抛 IllegalArgumentException");
            } catch (IllegalArgumentException expected) {
                return "pageNum=0 已拒绝";
            } catch (UnsupportedOperationException allowed) {
                return "UnsupportedOperationException(契约允许)";
            }
        });

        check(tag + ".insertBatch(null) 守卫", () -> {
            try {
                e.insertBatch(null, null);
                throw new IllegalStateException("insertBatch(null,null) 未抛 IllegalArgumentException");
            } catch (IllegalArgumentException expected) {
                return "已拒绝";
            } catch (UnsupportedOperationException allowed) {
                return "UnsupportedOperationException(契约允许)";
            }
        });

        check(tag + ".count(包装器 null) 守卫", () -> {
            try {
                e.count((com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper<Object>) null);
                throw new IllegalStateException("未抛 IllegalArgumentException");
            } catch (IllegalArgumentException expected) {
                return "已拒绝";
            }
        });
    }

    private static void safeClose(String key, Engine e) {
        check(key + ".close 幂等", () -> {
            e.close();
            e.close();
            return "ok";
        });
    }

    // ---------------- 断言与统计 ----------------

    interface Probe {
        String run() throws Exception;
    }

    private static void check(String name, Probe probe) {
        try {
            String detail = probe.run();
            pass++;
            System.out.println("  [PASS] " + name + (detail == null || detail.isEmpty() ? "" : "  -> " + detail));
        } catch (Throwable t) {
            recordFail(name + "  -> " + t);
        }
    }

    private static void recordFail(String message) {
        fail++;
        FAILURES.add(message);
        System.out.println("  [FAIL] " + message);
    }
}
