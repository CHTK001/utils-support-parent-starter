import com.chua.clickhouse.support.engine.ClickHouseEngine;
import com.chua.datasource.support.engine.JdbcEngine;
import com.chua.oracle.support.engine.OracleEngine;
import com.chua.postgresql.support.engine.PostgresqlEngine;
import com.chua.sqlserver.support.engine.SqlServerEngine;
import com.chua.sqlserver.support.engine.SqlServerLegacyEngine;
import com.zaxxer.hikari.HikariDataSource;

/**
 * 引擎验收运行器 —— 阶段十四：外部服务 JDBC 引擎的「本机可验面」。
 *
 * <p>postgresql / sqlserver / sqlserver-legacy / oracle / clickhouse 这 5 个引擎
 * 没有可用服务端，查询语义无法真实验收。但它们有四项行为**完全不需要服务端**
 * 就能判定对错，且每一项出错都是生产事故：</p>
 *
 * <ol>
 *   <li><b>连接串拼装</b>：host/port/database 拼错一位就连不上目标库；</li>
 *   <li><b>入参守卫</b>：host 与 database 是裸拼进 URL 的，不校验就能用
 *       {@code ?autoDeserialize=true} 之类的串改写驱动行为（见 {@code JdbcUrl} 的注释），
 *       这是注入面；</li>
 *   <li><b>失败语义</b>：不可达时必须是「抛异常」，绝不能静默返回空集合——
 *       后者会让上层把「连不上」当成「没有数据」；</li>
 *   <li><b>关闭语义</b>：{@code close()} 必须真的关掉连接池，否则线程泄漏。</li>
 * </ol>
 *
 * <p>本阶段不声称验证了这 5 个引擎的查询语义；那部分仍只有阶段一的契约面，
 * 报告 §7 已如实声明。此处证明的是「配置与生命周期」这一层没有缺陷。</p>
 */
public final class EngineExternalConfig {

    /** 引擎构造器。 */
    @FunctionalInterface
    interface Factory {
        JdbcEngine create();
    }

    /** 数据源注册器：各引擎的 addDataSource 重载是子类私有的，只能逐个绑定。 */
    @FunctionalInterface
    interface Register {
        void register(JdbcEngine engine, String name, String host, int port, String database);
    }

    /** 期望的 JDBC URL。 */
    @FunctionalInterface
    interface UrlExpect {
        String url(String host, int port, String database);
    }

    private static final String USER = "acc_user";
    private static final String PASSWORD = "acc_Passw0rd";

    private EngineExternalConfig() {
    }

    public static void main(String[] args) {
        EngineFunctional.emit("=".repeat(78));
        EngineFunctional.emit("  引擎验收 阶段十四：外部服务 JDBC 引擎的本机可验面");
        EngineFunctional.emit("=".repeat(78));

        caseSuite("postgresql",
                PostgresqlEngine::new,
                (e, n, h, p, d) -> e.addDataSource(n, h, p, d, USER, PASSWORD),
                (h, p, d) -> "jdbc:postgresql://" + h + ":" + p + "/" + d);

        caseSuite("sqlserver",
                SqlServerEngine::new,
                (e, n, h, p, d) -> e.addDataSource(n, h, p, d, USER, PASSWORD),
                (h, p, d) -> "jdbc:sqlserver://" + h + ":" + p
                        + ";databaseName=" + d + ";encrypt=false;trustServerCertificate=true");

        caseSuite("sqlserver-legacy",
                SqlServerLegacyEngine::new,
                (e, n, h, p, d) -> e.addDataSource(n, h, p, d, USER, PASSWORD),
                (h, p, d) -> "jdbc:jtds:sqlserver://" + h + ":" + p + "/" + d
                        + ";selectMethod=cursor");

        caseSuite("oracle",
                OracleEngine::new,
                (e, n, h, p, d) -> e.addDataSource(n, h, p, d, USER, PASSWORD),
                (h, p, d) -> "jdbc:oracle:thin:@//" + h + ":" + p + "/" + d);

        caseSuite("clickhouse",
                ClickHouseEngine::new,
                (e, n, h, p, d) -> e.addDataSource(n, h, p, d, USER, PASSWORD),
                (h, p, d) -> "jdbc:clickhouse://" + h + ":" + p + "/" + d);

        EngineFunctional.finishAndExit("EngineExternalConfig-report.txt");
    }

    // ==================================================================

    private static void caseSuite(String name, Factory factory, Register register, UrlExpect expect) {
        EngineFunctional.emit("--- external-config:" + name + " ---");
        String p = name + ".";

        EngineFunctional.check(p + "连接串精确拼装 + 用户名/密码透传 + dialect 非空", () -> {
            JdbcEngine e = factory.create();
            try {
                register.register(e, "default", "127.0.0.1", 5432, "appdb");
                String actual = e.getDataSource("default").url();
                String expected = expect.url("127.0.0.1", 5432, "appdb");
                if (!expected.equals(actual)) {
                    throw new EngineFunctional.HarnessFailure(
                            "URL 期望 [" + expected + "]，实际 [" + actual + "]");
                }
                if (!USER.equals(e.getDataSource("default").username())) {
                    throw new EngineFunctional.HarnessFailure(
                            "username 未透传: " + e.getDataSource("default").username());
                }
                if (!PASSWORD.equals(e.getDataSource("default").password())) {
                    throw new EngineFunctional.HarnessFailure("password 未透传");
                }
                if (e.getDataSource("default").getDialect() == null) {
                    throw new EngineFunctional.HarnessFailure("dialect 为 null");
                }
                return actual;
            } finally {
                e.close();
            }
        });

        EngineFunctional.check(p + "密码不进入连接串（避免日志/异常泄漏）", () -> {
            JdbcEngine e = factory.create();
            try {
                register.register(e, "default", "127.0.0.1", 5432, "appdb");
                String url = e.getDataSource("default").url();
                if (url.contains(PASSWORD)) {
                    throw new EngineFunctional.HarnessFailure("URL 含明文密码: " + url);
                }
                return "clean";
            } finally {
                e.close();
            }
        });

        EngineFunctional.check(p + "拒绝空主机 / 含连接串分隔符的主机", () -> {
            for (String bad : new String[]{null, "", "   ", "evil?allowLoadLocalInfile=true",
                    "a/b", "host;param=1", "u@host"}) {
                expectIae(() -> {
                    JdbcEngine e = factory.create();
                    try {
                        register.register(e, "default", bad, 5432, "appdb");
                    } finally {
                        e.close();
                    }
                });
            }
            return "7 种非法 host 全部拒绝";
        });

        EngineFunctional.check(p + "拒绝非法端口", () -> {
            for (int bad : new int[]{0, -1, 65536, 70000, Integer.MAX_VALUE}) {
                expectIae(() -> {
                    JdbcEngine e = factory.create();
                    try {
                        register.register(e, "default", "127.0.0.1", bad, "appdb");
                    } finally {
                        e.close();
                    }
                });
            }
            return "5 种非法 port 全部拒绝";
        });

        EngineFunctional.check(p + "拒绝 null / 含分隔符的库名", () -> {
            for (String bad : new String[]{null, "db?autoDeserialize=true", "db;x=1", "a/b", "db name"}) {
                expectIae(() -> {
                    JdbcEngine e = factory.create();
                    try {
                        register.register(e, "default", "127.0.0.1", 5432, bad);
                    } finally {
                        e.close();
                    }
                });
            }
            return "5 种非法 database 全部拒绝";
        });

        EngineFunctional.check(p + "被拒绝的注册不留半成品数据源（校验先于注册）", () -> {
            JdbcEngine e = factory.create();
            try {
                register.register(e, "default", "127.0.0.1", 5432, "appdb");
                expectIae(() -> register.register(e, "poison", "127.0.0.1", -1, "appdb"));
                if (e.dataSourceNames().contains("poison")) {
                    throw new EngineFunctional.HarnessFailure(
                            "非法注册后仍留下数据源: " + e.dataSourceNames());
                }
                return "names=" + e.dataSourceNames();
            } finally {
                e.close();
            }
        });

        EngineFunctional.check(p + "不可达主机：抛异常而非静默空结果，且 10s 内失败", () -> {
            JdbcEngine e = factory.create();
            try {
                register.register(e, "default", "127.0.0.1", 1, "appdb");
                HikariDataSource pool = (HikariDataSource) e.getDataSource("default").getSource();
                // 连接池默认 30s，验收里收紧到 1.5s；这是配置而非伪造行为
                pool.setConnectionTimeout(1500);
                long t0 = System.nanoTime();
                try {
                    var rows = e.query(EngineFunctional.AccUser.class).list();
                    throw new EngineFunctional.HarnessFailure(
                            "不可达主机却返回了 " + rows.size() + " 行（失败被静默吞掉）");
                } catch (EngineFunctional.HarnessFailure f) {
                    throw f;
                } catch (Throwable expected) {
                    long ms = (System.nanoTime() - t0) / 1_000_000L;
                    if (ms > 10_000L) {
                        throw new EngineFunctional.HarnessFailure("失败耗时过长: " + ms + "ms");
                    }
                    return ms + "ms 内抛 " + expected.getClass().getSimpleName();
                }
            } finally {
                e.close();
            }
        });

        EngineFunctional.check(p + "close() 幂等且真正关闭连接池", () -> {
            JdbcEngine e = factory.create();
            register.register(e, "default", "127.0.0.1", 5432, "appdb");
            HikariDataSource pool = (HikariDataSource) e.getDataSource("default").getSource();
            e.close();
            if (!pool.isClosed()) {
                throw new EngineFunctional.HarnessFailure("close() 后连接池仍存活（线程泄漏）");
            }
            e.close();
            if (!e.dataSourceNames().isEmpty()) {
                throw new EngineFunctional.HarnessFailure("close() 后仍残留数据源: " + e.dataSourceNames());
            }
            return "pool closed, idempotent";
        });
    }

    // ==================================================================

    private static void expectIae(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        } catch (Throwable t) {
            throw new EngineFunctional.HarnessFailure(
                    "期望 IllegalArgumentException，实际 " + EngineFunctional.chain(t));
        }
        throw new EngineFunctional.HarnessFailure("期望 IllegalArgumentException，未抛异常");
    }
}
