import com.chua.influxdb.support.engine.InfluxDbEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * 引擎验收运行器 —— 阶段十三：InfluxDbEngine（SPI {@code influxdb}，纯内存实现）。
 *
 * <p>它虽挂着 InfluxDB 的名字，实现却是**内存型**（{@code store} + 内存 WHERE 求值），
 * 不需要任何 InfluxDB 服务即可完整验证；而在本轮之前它只有阶段一的契约面。
 * 作为 24 个 SPI 注册 Engine 之一，这是可闭合的覆盖缺口。</p>
 *
 * <p>注意与阶段九区分：{@code InfluxdbSeriesEngine}（时序 SPI）在阶段九验证，
 * 本类验证的是 {@code Engine} 接口上的 {@code InfluxDbEngine}。</p>
 */
public final class EngineInfluxDbEngine {

    private EngineInfluxDbEngine() {
    }

    public static void main(String[] args) {
        EngineFunctional.emit("=".repeat(78));
        EngineFunctional.emit("  引擎验收 阶段十三：InfluxDbEngine（纯内存 Engine，SPI influxdb）");
        EngineFunctional.emit("=".repeat(78));

        runQuietly("capability", EngineInfluxDbEngine::capabilitySuite);
        runQuietly("store", EngineInfluxDbEngine::storeSuite);
        runQuietly("query", EngineInfluxDbEngine::querySuite);
        runQuietly("lambda", EngineInfluxDbEngine::lambdaSuite);
        runQuietly("paging", EngineInfluxDbEngine::pagingSuite);
        runQuietly("write-path", EngineInfluxDbEngine::writePathSuite);

        EngineFunctional.finishAndExit("EngineInfluxDbEngine-report.txt");
    }

    // ==================== 能力声明 ====================

    static void capabilitySuite() {
        EngineFunctional.emit("--- influxdb-engine:capability (能力声明与行为自洽) ---");
        String p = "cap.";

        EngineFunctional.check(p + "无 SQL 执行器时 supportsSql=false 且 querySql 抛 UOE", () -> {
            InfluxDbEngine e = new InfluxDbEngine();
            if (e.supportsSql()) {
                throw new EngineFunctional.HarnessFailure("supportsSql 应为 false");
            }
            if (e.getExecutor() != null) {
                throw new EngineFunctional.HarnessFailure("getExecutor 应为 null");
            }
            expectType(UnsupportedOperationException.class, () -> e.querySql("select * from t_acc_user"));
            return "sql=false executor=null querySql=UOE";
        });

        EngineFunctional.check(p + "未注册数据源时 dataSourceNames 为空、hasDataSource=false", () -> {
            InfluxDbEngine e = new InfluxDbEngine();
            if (!e.dataSourceNames().isEmpty()) {
                throw new EngineFunctional.HarnessFailure("dataSourceNames: " + e.dataSourceNames());
            }
            if (e.hasDataSource("default") || e.hasDataSource("t_acc_user")) {
                throw new EngineFunctional.HarnessFailure("hasDataSource 不应为 true");
            }
            return "empty / false";
        });
    }

    // ==================== store 语义 ====================

    static void storeSuite() {
        EngineFunctional.emit("--- influxdb-engine:store (表名解析与回退) ---");
        String p = "store.";

        EngineFunctional.check(p + "按实体 @TableName 存储后可查询，顺序保持", () -> {
            InfluxDbEngine e = new InfluxDbEngine();
            e.store("t_acc_user", sample(5));
            List<EngineFunctional.AccUser> all = e.query(EngineFunctional.AccUser.class).list();
            if (all.size() != 5) {
                throw new EngineFunctional.HarnessFailure("行数: " + all.size());
            }
            for (int i = 0; i < 5; i++) {
                if (all.get(i).getId() == null || all.get(i).getId() != (i + 1L)) {
                    throw new EngineFunctional.HarnessFailure("顺序错乱: " + all.get(i));
                }
            }
            return "5 行保序";
        });

        EngineFunctional.check(p + "同名重复 store 为覆盖（不是追加）", () -> {
            InfluxDbEngine e = new InfluxDbEngine();
            e.store("t_acc_user", sample(5));
            e.store("t_acc_user", sample(2));
            List<EngineFunctional.AccUser> all = e.query(EngineFunctional.AccUser.class).list();
            if (all.size() != 2) {
                throw new EngineFunctional.HarnessFailure("应为覆盖后的 2 行: " + all.size());
            }
            return "5 -> 2";
        });

        EngineFunctional.check(p + "无表名数据回退到 default；非 default 的无关表不串读", () -> {
            InfluxDbEngine fallback = new InfluxDbEngine();
            fallback.store("default", sample(3));
            if (fallback.query(EngineFunctional.AccUser.class).list().size() != 3) {
                throw new EngineFunctional.HarnessFailure("default 回退失败");
            }

            InfluxDbEngine isolated = new InfluxDbEngine();
            isolated.store("some_other_table", sample(3));
            if (!isolated.query(EngineFunctional.AccUser.class).list().isEmpty()) {
                throw new EngineFunctional.HarnessFailure("不得跨表读到无关数据");
            }
            return "default->3, other->0";
        });

        EngineFunctional.check(p + "空引擎查询为空、计数为 0（不抛异常）", () -> {
            InfluxDbEngine e = new InfluxDbEngine();
            if (!e.query(EngineFunctional.AccUser.class).list().isEmpty()) {
                throw new EngineFunctional.HarnessFailure("应为空");
            }
            if (e.query(EngineFunctional.AccUser.class).count() != 0) {
                throw new EngineFunctional.HarnessFailure("count 应为 0");
            }
            return "empty / 0";
        });
    }

    // ==================== 查询语义 ====================

    static void querySuite() {
        EngineFunctional.emit("--- influxdb-engine:query (WHERE 求值与类型化映射) ---");
        String p = "query.";

        EngineFunctional.check(p + "eq 条件命中唯一行，字段映射完整", () -> {
            InfluxDbEngine e = new InfluxDbEngine();
            e.store("t_acc_user", sample(5));
            List<EngineFunctional.AccUser> hit =
                    e.query(EngineFunctional.AccUser.class).eq(EngineFunctional.AccUser::getName, "alice").list();
            if (hit.size() != 1) {
                throw new EngineFunctional.HarnessFailure("行数: " + hit.size());
            }
            EngineFunctional.AccUser u = hit.get(0);
            if (u.getId() == null || u.getId() != 1L
                    || !"alice".equals(u.getName())
                    || !Integer.valueOf(30).equals(u.getAge())) {
                throw new EngineFunctional.HarnessFailure("字段映射: " + u);
            }
            return "alice/1/30";
        });

        EngineFunctional.check(p + "gt + orderByDesc 同时生效", () -> {
            InfluxDbEngine e = new InfluxDbEngine();
            e.store("t_acc_user", sample(5));
            List<EngineFunctional.AccUser> list = e.query(EngineFunctional.AccUser.class)
                    .gt(EngineFunctional.AccUser::getAge, 26)
                    .orderByDesc(EngineFunctional.AccUser::getId)
                    .list();
            // 样例年龄 30/25/35/28/22 → >26 命中 alice(30)/carol(35)/dave(28) 共 3 行
            if (list.size() != 3) {
                throw new EngineFunctional.HarnessFailure("行数: " + list.size());
            }
            if (list.get(0).getId() == null || list.get(0).getId() != 4L) {
                throw new EngineFunctional.HarnessFailure("首行应为 id=4: " + list.get(0));
            }
            return "3 行，首行 id=4";
        });

        EngineFunctional.check(p + "无匹配条件返回空（不是全表）", () -> {
            InfluxDbEngine e = new InfluxDbEngine();
            e.store("t_acc_user", sample(5));
            if (!e.query(EngineFunctional.AccUser.class)
                    .eq(EngineFunctional.AccUser::getName, "nobody").list().isEmpty()) {
                throw new EngineFunctional.HarnessFailure("应为空");
            }
            return "empty";
        });
    }

    // ==================== Lambda 终端 ====================

    static void lambdaSuite() {
        EngineFunctional.emit("--- influxdb-engine:lambda (count / 包装器终端) ---");
        String p = "lambda.";

        EngineFunctional.check(p + "count(包装器) 与过滤结果一致", () -> {
            InfluxDbEngine e = new InfluxDbEngine();
            e.store("t_acc_user", sample(5));
            long n = e.count(e.query(EngineFunctional.AccUser.class).gt(EngineFunctional.AccUser::getAge, 26));
            if (n != 3) {
                throw new EngineFunctional.HarnessFailure("count: " + n);
            }
            return "3";
        });

        EngineFunctional.check(p + "orderByAsc 生效", () -> {
            InfluxDbEngine e = new InfluxDbEngine();
            e.store("t_acc_user", sample(5));
            List<EngineFunctional.AccUser> list = e.query(EngineFunctional.AccUser.class)
                    .orderByAsc(EngineFunctional.AccUser::getId).list();
            if (list.get(0).getId() != 1L || list.get(4).getId() != 5L) {
                throw new EngineFunctional.HarnessFailure("排序: " + list.get(0) + " .. " + list.get(4));
            }
            return "1..5";
        });
    }

    // ==================== 分页 ====================

    static void pagingSuite() {
        EngineFunctional.emit("--- influxdb-engine:paging (skip 先于 limit) ---");
        String p = "page.";

        EngineFunctional.check(p + "page(1,2)/page(2,2) 切片正确且不重叠", () -> {
            InfluxDbEngine e = new InfluxDbEngine();
            e.store("t_acc_user", sample(5));
            var p1 = e.query(EngineFunctional.AccUser.class).orderByAsc(EngineFunctional.AccUser::getId).page(1, 2);
            var p2 = e.query(EngineFunctional.AccUser.class).orderByAsc(EngineFunctional.AccUser::getId).page(2, 2);
            if (p1.getRecords().size() != 2 || p1.getRecords().get(0).getId() != 1L) {
                throw new EngineFunctional.HarnessFailure("第 1 页: " + p1.getRecords());
            }
            if (p2.getRecords().size() != 2 || p2.getRecords().get(0).getId() != 3L) {
                throw new EngineFunctional.HarnessFailure("第 2 页: " + p2.getRecords());
            }
            return "1,2 / 3,4";
        });

        EngineFunctional.check(p + "大表末页：1000 行取第 500 页（每页 2）恰为 999/1000", () -> {
            InfluxDbEngine e = new InfluxDbEngine();
            List<EngineFunctional.AccUser> big = new ArrayList<>();
            for (long i = 1; i <= 1000; i++) {
                big.add(new EngineFunctional.AccUser(i, "u" + i, (int) (i % 90)));
            }
            e.store("t_acc_user", big);
            var last = e.query(EngineFunctional.AccUser.class)
                    .orderByAsc(EngineFunctional.AccUser::getId).page(500, 2);
            if (last.getRecords().size() != 2
                    || last.getRecords().get(0).getId() != 999L
                    || last.getRecords().get(1).getId() != 1000L) {
                throw new EngineFunctional.HarnessFailure("末页: " + last.getRecords());
            }
            return "999,1000";
        });

        EngineFunctional.check(p + "越界页为空（不是最后一页/整表）", () -> {
            InfluxDbEngine e = new InfluxDbEngine();
            e.store("t_acc_user", sample(5));
            var over = e.query(EngineFunctional.AccUser.class).page(99, 10);
            if (!over.getRecords().isEmpty()) {
                throw new EngineFunctional.HarnessFailure("越界页: " + over.getRecords());
            }
            return "empty";
        });
    }

    // ==================== 写路径 ====================

    static void writePathSuite() {
        EngineFunctional.emit("--- influxdb-engine:write-path (全表守卫 + 内存写真实生效) ---");
        String p = "write.";

        EngineFunctional.check(p + "空条件 update/delete 被全表守卫拒绝，数据不变", () -> {
            InfluxDbEngine e = new InfluxDbEngine();
            e.store("t_acc_user", sample(5));
            expectType(IllegalStateException.class, () -> e.update(EngineFunctional.AccUser.class)
                    .set(EngineFunctional.AccUser::getAge, 99)
                    .update());
            expectType(IllegalStateException.class, () -> e.delete(EngineFunctional.AccUser.class).remove());
            if (e.query(EngineFunctional.AccUser.class).eq(EngineFunctional.AccUser::getAge, 99).list().size() != 0) {
                throw new EngineFunctional.HarnessFailure("被拒绝的更新却改了数据");
            }
            if (e.query(EngineFunctional.AccUser.class).list().size() != 5) {
                throw new EngineFunctional.HarnessFailure("被拒绝的删除却删了数据");
            }
            return "ISE 且 5 行未变";
        });

        EngineFunctional.check(p + "带条件 update/delete 在内存引擎真实生效且只命中目标行", () -> {
            InfluxDbEngine e = new InfluxDbEngine();
            e.store("t_acc_user", sample(5));

            int updated = e.update(EngineFunctional.AccUser.class)
                    .set(EngineFunctional.AccUser::getAge, 99)
                    .eq(EngineFunctional.AccUser::getName, "alice")
                    .update();
            if (updated != 1) {
                throw new EngineFunctional.HarnessFailure("update 影响行数: " + updated);
            }
            if (!Integer.valueOf(99).equals(
                    e.query(EngineFunctional.AccUser.class).eq(EngineFunctional.AccUser::getName, "alice")
                            .list().get(0).getAge())) {
                throw new EngineFunctional.HarnessFailure("更新未落库");
            }

            int removed = e.delete(EngineFunctional.AccUser.class)
                    .eq(EngineFunctional.AccUser::getName, "bob")
                    .remove();
            if (removed != 1) {
                throw new EngineFunctional.HarnessFailure("delete 影响行数: " + removed);
            }
            if (e.query(EngineFunctional.AccUser.class).list().size() != 4) {
                throw new EngineFunctional.HarnessFailure("删除后行数: "
                        + e.query(EngineFunctional.AccUser.class).list().size());
            }
            return "update=1, delete=1, 剩 4 行";
        });
    }

    // ==================== 辅助 ====================

    private static List<EngineFunctional.AccUser> sample(int n) {
        List<EngineFunctional.AccUser> list = new ArrayList<>();
        String[] names = {"alice", "bob", "carol", "dave", "erin", "frank", "grace"};
        int[] ages = {30, 25, 35, 28, 22, 40, 31};
        for (int i = 0; i < n; i++) {
            list.add(new EngineFunctional.AccUser((long) (i + 1), names[i % names.length], ages[i % ages.length]));
        }
        return list;
    }

    private static void expectType(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return;
            }
            throw new EngineFunctional.HarnessFailure(
                    "期望 " + type.getSimpleName() + "，实际 " + EngineFunctional.chain(t));
        }
        throw new EngineFunctional.HarnessFailure("未抛 " + type.getSimpleName());
    }

    private static void runQuietly(String name, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            EngineFunctional.recordFail(name + " 套件异常退出: " + EngineFunctional.chain(t));
        }
    }
}
