package com.chua.neo4j.support.engine;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Neo4jEngine 真实容器集成测试（bolt://172.16.0.40）。
 * 覆盖：连接、store、Lambda 查询/更新/删除、约束 DDL、用户管理。
 */
class Neo4jEngineIT {

    private static final String HOST = "172.16.0.40";
    private static final String USER = "neo4j";
    private static final String PASS = "test12345";
    private static String boltUri;

    @BeforeAll
    static void resolveBolt() {
        for (int port : new int[]{7688, 7687}) {
            if (reachable(HOST, port)) {
                boltUri = "bolt://" + HOST + ":" + port;
                break;
            }
        }
        Assumptions.assumeTrue(boltUri != null, "Neo4j 容器不可达，跳过");
    }

    private static boolean reachable(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Driver adminDriver() {
        return GraphDatabase.driver(boltUri, AuthTokens.basic(USER, PASS));
    }

    @Test
    void connect_store_queryUpdateDelete() throws Exception {
        /* 清理历史数据，保证幂等 */
        try (Driver d = adminDriver(); Session s = d.session()) {
            s.run("MATCH (n:Person) DETACH DELETE n");
        }

        Neo4jEngine engine = (Neo4jEngine) new Neo4jEngine().connect(boltUri, USER, PASS);
        try {
            Person a = new Person();
            a.setId(1);
            a.setName("Alice");
            Person b = new Person();
            b.setId(2);
            b.setName("Bob");
            engine.store("neo4j", List.of(a, b));

            /* 容器可见性轮询：store 后最多等 10s 出现 2 个节点 */
            boolean stored = false;
            String dump = "";
            try (Driver d = adminDriver(); Session s = d.session()) {
                for (int i = 0; i < 20 && !stored; i++) {
                    Thread.sleep(500);
                    var recs = s.run("MATCH (n:Person) RETURN n.name AS name").list();
                    stored = recs.size() == 2;
                    dump = recs.toString();
                }
            }
            assertTrue(stored, "[IT] store 后未查询到节点，实际: " + dump);

            List<Person> found = engine.query(Person.class).eq(Person::getName, "Alice").list();
            assertEquals(1, found.size(), "按 name 查询应命中 Alice");

            int updated = engine.update(Person.class)
                    .set(Person::getName, "Alicia")
                    .eq(Person::getName, "Alice")
                    .update();
            assertEquals(1, updated);

            List<Person> afterUpdate = engine.query(Person.class).eq(Person::getName, "Alicia").list();
            assertEquals(1, afterUpdate.size());

            int removed = engine.delete(Person.class).eq(Person::getId, 2).remove();
            assertTrue(removed >= 1);
        } finally {
            engine.close();
        }
    }

    @Test
    void constraintDdl_andUserAdmin() throws Exception {
        try (Driver driver = adminDriver(); Session session = driver.session()) {

            /* DDL 等价物：唯一性约束（固定名，前置清理保证幂等） */
            String constraint = "c_it_person_name";
            session.run("SHOW CONSTRAINTS").list(r -> r.get("name").asString()).stream()
                    .filter(n -> n.startsWith("c_it_"))
                    .forEach(n -> session.run("DROP CONSTRAINT " + n + " IF EXISTS"));
            session.run("DROP CONSTRAINT " + constraint + " IF EXISTS");
            session.run("CREATE CONSTRAINT " + constraint + " FOR (p:Person) REQUIRE p.name IS UNIQUE");

            boolean exists = false;
            String namesDump = "";
            for (int i = 0; i < 20 && !exists; i++) {
                Thread.sleep(300);
                var names = session.run("SHOW CONSTRAINTS")
                        .list(r -> r.get("name").asString());
                exists = names.contains(constraint);
                namesDump = names.toString();
            }
            assertTrue(exists, "[IT] SHOW CONSTRAINTS 未包含 " + constraint + "，实际: " + namesDump);

            /* 约束生效验证：重复 name 插入必须失败 */
            session.run("MATCH (p:Person) WHERE p.uid STARTS WITH 'uc' DETACH DELETE p");
            String dupName = "Dup" + System.nanoTime();
            session.run("MERGE (p:Person {uid:'uc1'}) SET p.name=$v", Map.of("v", dupName));
            /* 第二个同 name 节点必须被唯一约束拒绝 */
            assertThrows(Exception.class, () ->
                    session.run("MERGE (p:Person {uid:'uc2'}) SET p.name=$v", Map.of("v", dupName)).consume());
            session.run("MATCH (p:Person) WHERE p.uid STARTS WITH 'uc' DETACH DELETE p");
            session.run("DROP CONSTRAINT " + constraint + " IF EXISTS");

            /* 用户管理（Community 版能力探测） */
            String userName = "it_user_probe";
            try {
                session.run("DROP USER " + userName + " IF EXISTS");
                session.run("CREATE USER " + userName + " SET PLAINTEXT PASSWORD 'Passw0rd!'");
                List<Record> users = session.run("SHOW USERS").list();
                assertTrue(users.stream().anyMatch(r -> r.get("user").asString().equals(userName)));
                session.run("DROP USER " + userName + " IF EXISTS");
            } catch (Exception communityLimited) {
                System.out.println("[IT] neo4j user-admin limited: "
                        + communityLimited.getMessage());
            }
        }
    }
}
