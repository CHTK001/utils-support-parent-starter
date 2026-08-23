package com.chua.datasource.support.engine;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class InMemoryEngineTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void testStoreAndList() {
        InMemoryEngine engine = new InMemoryEngine();
        List<Map<String, Object>> users = new ArrayList<>();
        users.add(map("id", 1, "name", "zhangsan", "age", 20));
        users.add(map("id", 2, "name", "lisi", "age", 30));
        engine.store("user", users);
        assertEquals(2, engine.query(Map.class).list().size());
    }

    @Test
    void testStoreDefaultName() {
        InMemoryEngine engine = new InMemoryEngine();
        List<Map<String, Object>> data = new ArrayList<>();
        data.add(map("id", 1));
        engine.store("default", data);
        assertEquals(1, engine.query(Map.class).list().size());
    }

    @Test
    void testClose() {
        InMemoryEngine engine = new InMemoryEngine();
        List<Map<String, Object>> data = new ArrayList<>();
        data.add(map("id", 1));
        engine.store("user", data);
        engine.close();
        assertTrue(engine.query(Map.class).list().isEmpty());
    }
}