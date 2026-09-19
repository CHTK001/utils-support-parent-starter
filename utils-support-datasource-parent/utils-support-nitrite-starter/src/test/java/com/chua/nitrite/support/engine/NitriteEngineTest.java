package com.chua.nitrite.support.engine;

import org.dizitart.no2.collection.Document;
import org.dizitart.no2.collection.NitriteCollection;
import org.dizitart.no2.collection.NitriteId;
import org.dizitart.no2.filters.FluentFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nitrite 引擎单元测试（文件型嵌入式数据库，无需外部服务）。
 * <p>Nitrite 4.4.x 的对象仓库需注册 EntityConverter，本测试覆盖引擎自研的
 * 集合级实体编解码路径与 DocumentStore 语义。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class NitriteEngineTest {

    @TempDir
    Path dir;

    private NitriteEngine open(String fileName) {
        NitriteEngine engine = new NitriteEngine();
        engine.addDataSource("db", dir.resolve(fileName).toString());
        return engine;
    }

    @Test
    void storePersistsAndReopens() {
        try (NitriteEngine engine = open("a.db")) {
            List<User> users = new ArrayList<>();
            users.add(new User("1", "alice", 30));
            users.add(new User("2", "bob", 40));
            engine.store("user", users);
        }
        // 重开引擎：内存为空，查询走真实集合读取路径，证明已落盘
        try (NitriteEngine reopened = open("a.db")) {
            List<User> all = reopened.executeNewQuery(null, null, User.class, 0, 0);
            assertEquals(2, all.size());
            List<User> older = reopened.executeNewQuery("age > ?", new Object[]{35}, User.class, 0, 0);
            assertEquals(1, older.size());
            assertEquals("bob", older.get(0).name);
        }
    }

    @Test
    void storeUpsertsByIdWithoutDuplication() {
        try (NitriteEngine engine = open("u.db")) {
            List<User> first = new ArrayList<>();
            first.add(new User("1", "alice", 30));
            engine.store("user", first);
            List<User> second = new ArrayList<>();
            second.add(new User("1", "alice", 31));
            engine.store("user", second);
        }
        try (NitriteEngine reopened = open("u.db")) {
            List<User> all = reopened.executeNewQuery(null, null, User.class, 0, 0);
            assertEquals(1, all.size());
            assertEquals(31, all.get(0).age);
        }
    }

    @Test
    void entityBusinessIdCrud() {
        try (NitriteEngine engine = open("e.db")) {
            List<User> users = new ArrayList<>();
            users.add(new User("1", "alice", 30));
            users.add(new User("2", "bob", 40));
            engine.store("user", users);

            User found = engine.findById("User", "1", User.class);
            assertNotNull(found);
            assertEquals("alice", found.name);
            assertEquals(2, engine.findAll("User", User.class).size());

            assertTrue(engine.delete("User", "1"));
            assertEquals(1, engine.findAll("User", User.class).size());
            assertFalse(engine.delete("User", "404"));
        }
    }

    @Test
    void documentStoreCrud() {
        try (NitriteEngine engine = open("b.db")) {
            engine.insert("notes", Document.createDocument().put("id", "1").put("text", "hello"));
            engine.insert("notes", Document.createDocument().put("id", "2").put("text", "world"));

            Document found = engine.findById("notes", "1", Document.class);
            assertNotNull(found);
            assertEquals("hello", found.get("text"));
            assertEquals(2, engine.findAll("notes", Document.class).size());

            engine.update("notes", "1",
                    Document.createDocument().put("id", "1").put("text", "changed"));
            assertEquals("changed", engine.findById("notes", "1", Document.class).get("text"));

            assertTrue(engine.delete("notes", "2"));
            assertEquals(1, engine.findAll("notes", Document.class).size());
            assertFalse(engine.delete("notes", "999"));
        }
    }

    @Test
    void deleteByNitriteIdRemovesExactlyOne() {
        try (NitriteEngine engine = open("c.db")) {
            NitriteCollection raw = engine.getNitrite("db").getCollection("notes");
            raw.insert(Document.createDocument().put("text", "one"));
            raw.insert(Document.createDocument().put("text", "two"));
            NitriteId target = null;
            for (Document doc : raw.find(FluentFilter.where("text").eq("one"))) {
                Object rawId = doc.get("_id");
                target = rawId instanceof NitriteId n
                        ? n
                        : NitriteId.createId(((Number) rawId).longValue());
            }
            assertNotNull(target);
            assertTrue(engine.delete("notes", target));
            assertEquals(1, raw.size());
        }
    }

    @Test
    void memoryStoreHonorsLimitOffset() {
        try (NitriteEngine engine = new NitriteEngine()) {
            List<User> users = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                users.add(new User(String.valueOf(i), "u" + i, 20 + i));
            }
            engine.store("user", users);
            List<User> page = engine.executeNewQuery(null, null, User.class, 2, 2);
            assertEquals(2, page.size());
            assertEquals("u3", page.get(0).name);
        }
    }
}
