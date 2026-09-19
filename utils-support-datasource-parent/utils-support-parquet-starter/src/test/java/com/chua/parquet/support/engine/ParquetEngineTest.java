package com.chua.parquet.support.engine;

import com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql;
import com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ParquetEngine 真实落盘回归测试（仅依赖本地临时目录，无需外部服务）。
 *
 * @author CH
 */
class ParquetEngineTest {

    @TempDir
    File dir;

    private ParquetEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ParquetEngine();
        engine.addDataSource("default", dir.getAbsolutePath());
    }

    /**
     * store 后必须能从磁盘读回，且列类型、null 值、枚举、时间均按原样还原。
     */
    @Test
    void storeThenQueryFromFile() {
        List<Profile> rows = new ArrayList<>();
        rows.add(row(1L, "张三", 20, 1.5D, true, new Date(1700000000000L), Level.SENIOR, null));
        rows.add(row(2L, "李四", 30, 2.5D, false, new Date(1700000001000L), Level.JUNIOR, "备注"));
        engine.store("profile", rows);

        assertTrue(new File(dir, "profile.parquet").exists(), "数据必须真实落盘");

        List<Profile> read = engine.query(Profile.class).list();
        assertEquals(2, read.size(), "读回行数必须与写入一致");
        Profile first = read.get(0);
        assertEquals(1L, first.getId());
        assertEquals("张三", first.getName());
        assertEquals(20, first.getAge());
        assertEquals(1.5D, first.getScore());
        assertTrue(first.isEnabled());
        assertEquals(new Date(1700000000000L), first.getBorn());
        assertEquals(Level.SENIOR, first.getLevel());
        assertNull(first.getRemark(), "null 列必须原样读回为 null");
        assertEquals("备注", read.get(1).getRemark());
    }

    /**
     * WHERE 谓词与 limit/offset 必须真实生效，不能被静默忽略。
     */
    @Test
    void whereAndPagingTakeEffect() {
        List<Profile> rows = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            rows.add(row((long) i, "u" + i, i * 10, i, i % 2 == 0, null, Level.JUNIOR, null));
        }
        engine.store("profile", rows);

        List<Profile> filtered = engine.query(Profile.class).gt(Profile::getAge, 20).list();
        assertEquals(3, filtered.size());

        List<Profile> paged = engine.query(Profile.class).orderByAsc(Profile::getAge)
                .limit(2).offset(1).list();
        assertEquals(2, paged.size());
        assertEquals(20, paged.get(0).getAge(), "offset 必须跳过前 1 行");
        assertEquals(30, paged.get(1).getAge());

        List<Profile> direct = engine.executeNewQuery("age > ?", new Object[]{20}, Profile.class, 2, 0);
        assertEquals(2, direct.size(), "引擎侧 limit 参数不能被忽略");
        List<Profile> offsetOnly = engine.executeNewQuery("", new Object[0], Profile.class, 0, 4);
        assertEquals(1, offsetOnly.size(), "引擎侧 offset 参数不能被忽略");
        assertTrue(engine.executeNewQuery("", new Object[0], Profile.class, 0, 9).isEmpty());
    }

    /**
     * update 必须返回真实影响行数并把改动写回文件。
     */
    @Test
    void updateWritesBackAndReturnsAffectedRows() {
        engine.store("profile", Arrays.asList(
                row(1L, "张三", 20, 1D, true, null, Level.JUNIOR, null),
                row(2L, "李四", 30, 1D, true, null, Level.JUNIOR, null)));

        int affected = engine.update(Profile.class)
                .set(Profile::getName, "王五")
                .set(Profile::getAge, 44)
                .eq(Profile::getId, 2L)
                .update();
        assertEquals(1, affected);

        List<Profile> read = engine.query(Profile.class).list();
        assertEquals(2, read.size());
        Profile updated = read.stream().filter(p -> p.getId() == 2L).findFirst().orElseThrow();
        assertEquals("王五", updated.getName());
        assertEquals(44, updated.getAge());
        assertEquals("张三", read.stream().filter(p -> p.getId() == 1L).findFirst().orElseThrow().getName(),
                "未命中行不能被连带修改");
    }

    /**
     * 驼峰列名写法也必须命中同一字段，SET 列不存在时必须抛错而不是静默忽略。
     */
    @Test
    void updateRejectsUnknownColumn() {
        engine.store("profile", List.of(row(1L, "张三", 20, 1D, true, null, Level.JUNIOR, null)));

        int affected = engine.executeUpdate(new UpdateSql<>(Profile.class,
                "name = ?", "id = ?", List.of("赵六", 1L)));
        assertEquals(1, affected);
        assertEquals("赵六", engine.query(Profile.class).list().get(0).getName());

        assertThrows(IllegalArgumentException.class, () -> engine.executeUpdate(new UpdateSql<>(
                Profile.class, "not_exists = ?", "id = ?", List.of("x", 1L))),
                "未知 SET 列必须显式抛错");
    }

    /**
     * delete 必须返回真实删除行数并把剩余数据写回文件。
     */
    @Test
    void deleteRemovesRowsAndPersists() {
        engine.store("profile", Arrays.asList(
                row(1L, "a", 10, 1D, true, null, Level.JUNIOR, null),
                row(2L, "b", 20, 1D, true, null, Level.JUNIOR, null),
                row(3L, "c", 30, 1D, true, null, Level.JUNIOR, null)));

        int removed = engine.delete(Profile.class).eq(Profile::getId, 2L).remove();
        assertEquals(1, removed);

        List<Profile> read = engine.query(Profile.class).list();
        assertEquals(2, read.size());
        assertEquals(Arrays.asList(1L, 3L), read.stream().map(Profile::getId).toList());

        int miss = engine.executeDelete(new DeleteSql<>(Profile.class, "id = ?", List.of(99L)));
        assertEquals(0, miss);
        assertEquals(2, engine.query(Profile.class).list().size(), "未命中时不得改动文件内容");
    }

    /**
     * 表名来自实体类名，必须做白名单校验，禁止借 @TableName 等入口做目录穿越。
     */
    @Test
    void rejectsPathTraversalTableName() {
        assertThrows(IllegalArgumentException.class,
                () -> engine.executeNewQuery("", new Object[0], Traversing.class, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> engine.store("x", List.of(new Traversing())), "越界表名必须拒绝落盘");
    }

    /**
     * 未注册目录、close 之后调用都必须显式抛错；重新注册可再次使用。
     */
    @Test
    void lifecycleGuards() {
        ParquetEngine fresh = new ParquetEngine();
        assertThrows(IllegalStateException.class, () -> fresh.query(Profile.class).list());
        assertThrows(IllegalStateException.class,
                () -> fresh.store("profile", List.of(row(1L, "a", 1, 1D, true, null, Level.JUNIOR, null))));

        engine.close();
        assertThrows(IllegalStateException.class, () -> engine.query(Profile.class).list(),
                "close 之后不得继续读盘");
        assertThrows(IllegalStateException.class,
                () -> engine.store("profile", List.of(row(1L, "a", 1, 1D, true, null, Level.JUNIOR, null))),
                "close 之后不得继续写盘");

        engine.addDataSource("default", dir.getAbsolutePath());
        engine.store("profile", List.of(row(1L, "a", 1, 1D, true, null, Level.JUNIOR, null)));
        assertEquals(1, engine.query(Profile.class).list().size(), "重新注册数据源即重新开启引擎");
    }

    /**
     * 空列表与 null 列表无法推断实体类型，必须抛错而不是静默成功。
     */
    @Test
    void rejectsUnusableWriteInput() {
        assertThrows(IllegalArgumentException.class, () -> engine.store("profile", null));
        assertThrows(IllegalArgumentException.class, () -> engine.store("profile", new ArrayList<>()));
        List<Profile> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> engine.store("profile", withNull));
    }

    /**
     * 覆盖写必须留下单一数据文件，不残留写入中的临时文件。
     */
    @Test
    void overwriteLeavesSingleFile() {
        engine.store("profile", Arrays.asList(
                row(1L, "a", 1, 1D, true, null, Level.JUNIOR, null),
                row(2L, "b", 2, 1D, true, null, Level.JUNIOR, null)));
        engine.store("profile", List.of(row(3L, "c", 3, 1D, true, null, Level.JUNIOR, null)));

        File[] left = dir.listFiles((d, n) -> n.endsWith(".parquet"));
        assertNotNull(left);
        assertEquals(1, left.length, "覆盖写后只应保留一个数据文件");
        assertTrue(left[0].getName().endsWith("profile.parquet"));
        assertEquals(1, engine.query(Profile.class).list().size());
    }

    private static Profile row(long id, String name, int age, double score, boolean enabled,
                               Date born, Level level, String remark) {
        Profile p = new Profile();
        p.setId(id);
        p.setName(name);
        p.setAge(age);
        p.setScore(score);
        p.setEnabled(enabled);
        p.setBorn(born);
        p.setLevel(level);
        p.setRemark(remark);
        return p;
    }

    /**
     * 测试用枚举列
     */
    enum Level {
        /**
         * 初级
         */
        JUNIOR,
        /**
         * 高级
         */
        SENIOR
    }

    /**
     * 覆盖各类列类型的测试实体
     */
    public static class Profile {
        private long id;
        private String name;
        private int age;
        private double score;
        private boolean enabled;
        private Date born;
        private Level level;
        private String remark;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }

        public double getScore() {
            return score;
        }

        public void setScore(double score) {
            this.score = score;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Date getBorn() {
            return born;
        }

        public void setBorn(Date born) {
            this.born = born;
        }

        public Level getLevel() {
            return level;
        }

        public void setLevel(Level level) {
            this.level = level;
        }

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }
    }

    /**
     * 表名带路径穿越的实体，用于验证标识符校验
     */
    @com.chua.datasource.support.annotation.TableName("../../escape")
    public static class Traversing {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
