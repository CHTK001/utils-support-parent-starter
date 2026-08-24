package com.chua.tablesaw.support.engine;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * TablesawEngine 数据框引擎真实测试（CSV 加载 + Lambda 查询）。
 */
class TablesawEngineIT {

    private static Path csvFile;

    @BeforeAll
    static void setup() throws Exception {
        csvFile = Files.createTempFile("tablesaw_it", ".csv");
        Files.writeString(csvFile, """
                id,name,age
                1,Alice,20
                2,Bob,30
                3,Cathy,25
                """);
    }

    @Test
    void loadCsv_andQuery() {
        TablesawEngine engine = new TablesawEngine();
        engine.load("tuser", csvFile.toString());
        try {
            List<TUser> all = engine.query(TUser.class).list();
            assertNotNull(all);
            assertEquals(3, all.size());

            List<TUser> filtered = engine.query(TUser.class)
                    .gt(TUser::getAge, 22)
                    .list();
            assertEquals(2, filtered.size());
        } finally {
            engine.close();
        }
    }

    public static class TUser {
        private int id;
        private String name;
        private int age;
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String n) { this.name = n; }
        public int getAge() { return age; }
        public void setAge(int a) { this.age = a; }
    }
}
