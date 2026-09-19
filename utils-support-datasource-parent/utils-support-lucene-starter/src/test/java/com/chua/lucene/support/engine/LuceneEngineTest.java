package com.chua.lucene.support.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LuceneEngine 查询语义单元测试：等值/范围/LIKE/IN/NULL/NOT、limit-offset 截断、实体回读。
 *
 * @author CH
 * @since 4.0.0.42
 */
class LuceneEngineTest {

    /**
     * 测试实体：覆盖 String/Integer/Double/可空字段。
     */
    public static class TestDoc {
        private String id;
        private String name;
        private Integer age;
        private Double score;
        private String note;

        public TestDoc() {
        }

        public TestDoc(String id, String name, Integer age, Double score, String note) {
            this.id = id;
            this.name = name;
            this.age = age;
            this.score = score;
            this.note = note;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }

        public Double getScore() {
            return score;
        }

        public void setScore(Double score) {
            this.score = score;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }

    private LuceneEngine engine;

    @BeforeEach
    void setUp() {
        engine = new LuceneEngine();
        List<TestDoc> docs = new ArrayList<>();
        docs.add(new TestDoc("1", "alice", 30, 1.5, "hi"));
        docs.add(new TestDoc("2", "bob", 40, 2.5, null));
        docs.add(new TestDoc("3", "alice wang", 50, 3.5, "yo"));
        engine.index(TestDoc.class, docs);
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    private List<TestDoc> query(String where, Object... params) {
        return engine.executeNewQuery(where, params, TestDoc.class, 0, 0);
    }

    @Test
    void eqStringRestoresNumericFields() {
        List<TestDoc> r = query("id = ?", "2");
        assertEquals(1, r.size());
        assertEquals("bob", r.get(0).getName());
        assertEquals(40, r.get(0).getAge());
        assertEquals(2.5, r.get(0).getScore());
    }

    @Test
    void eqIntegerAndRange() {
        assertEquals(1, query("age = ?", 30).size());
        assertEquals(2, query("age >= ?", 40).size());
        assertEquals(1, query("age > ? AND age < ?", 30, 50).size());
    }

    @Test
    void likeMatchesSubstring() {
        List<TestDoc> r = query("name LIKE ?", "%alice%");
        assertEquals(2, r.size());
        assertEquals(1, query("name LIKE ?", "bob%").size());
    }

    @Test
    void notLikeAnchoredMatchesAllExcept() {
        assertEquals(1, query("name NOT LIKE ?", "%alice%").size());
    }

    @Test
    void inListSupportsQuotedAndDecimals() {
        assertEquals(2, query("id IN ('1','3')").size());
        assertEquals(2, query("score IN (1.5, 2.5)").size());
    }

    @Test
    void notInMatchesComplement() {
        assertEquals(1, query("id NOT IN ('1','3')").size());
    }

    @Test
    void isNullAndIsNotNull() {
        assertEquals(1, query("note IS NULL").size());
        assertEquals(2, query("note IS NOT NULL").size());
    }

    @Test
    void notEqualsExcludesMatch() {
        assertEquals(2, query("age != ?", 30).size());
    }

    @Test
    void limitOffsetSliceIndexResults() {
        List<TestDoc> page = engine.executeNewQuery("age >= ?", new Object[]{30}, TestDoc.class, 1, 1);
        assertEquals(1, page.size());
        List<TestDoc> empty = engine.executeNewQuery("age >= ?", new Object[]{30}, TestDoc.class, 10, 99);
        assertTrue(empty.isEmpty());
    }

    @Test
    void memoryStoreSliceWithLimit() {
        LuceneEngine memEngine = new LuceneEngine();
        try {
            List<TestDoc> docs = new ArrayList<>();
            docs.add(new TestDoc("1", "a", 10, 1.0, null));
            docs.add(new TestDoc("2", "b", 20, 2.0, null));
            docs.add(new TestDoc("3", "c", 30, 3.0, null));
            memEngine.store("test_doc", docs);
            List<TestDoc> page = memEngine.executeNewQuery("age >= ?", new Object[]{10}, TestDoc.class, 2, 1);
            assertEquals(2, page.size());
            assertEquals("b", page.get(0).getName());
        } finally {
            memEngine.close();
        }
    }

    @Test
    void unsupportedOperatorThrows() {
        // 解析器对未知运算符直接抛 IllegalArgumentException（拒绝静默放行）
        assertThrows(IllegalArgumentException.class, () -> query("age REGEXP ?", "3"));
    }

    @Test
    void sqlPatternToWildcardEscapesLiterals() {
        assertEquals("*foo*", LuceneEngine.sqlPatternToWildcard("%foo%"));
        assertEquals("a?b", LuceneEngine.sqlPatternToWildcard("a_b"));
        assertEquals("a\\*b", LuceneEngine.sqlPatternToWildcard("a*b"));
        assertEquals("a\\\\b", LuceneEngine.sqlPatternToWildcard("a\\b"));
    }

    @Test
    void updateThenQuerySeesNewValue() {
        int updated = engine.update(TestDoc.class)
                .set("name", "carol")
                .eq("id", "2")
                .update();
        assertEquals(1, updated);
        List<TestDoc> r = query("id = ?", "2");
        assertEquals(1, r.size());
        assertEquals("carol", r.get(0).getName());
    }

    @Test
    void deleteRemovesDocs() {
        int removed = engine.delete(TestDoc.class).eq("id", "3").remove();
        assertEquals(1, removed);
        assertEquals(0, query("id = ?", "3").size());
        assertEquals(2, query("id IS NOT NULL").size());
    }
}
