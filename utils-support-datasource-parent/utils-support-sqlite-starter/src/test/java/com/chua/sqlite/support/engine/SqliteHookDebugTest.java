package com.chua.sqlite.support.engine;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;

class SqliteHookDebugTest {

    @Test
    void debug_hook_works() throws Exception {
        Path db = Files.createTempFile("sqlite-debug", ".db");
        db.toFile().deleteOnExit();

        SqliteHookConnection conn = SqliteHookConnection.open(db.toString());
        System.out.println("[DEBUG] hook open: " + (conn != null));

        if (conn == null) {
            System.out.println("[DEBUG] FAILED: hook connection is null");
            return;
        }

        Method drain = SqliteHookConnection.class.getDeclaredMethod("drainBufferSync");
        drain.setAccessible(true);

        int rc = conn.exec("CREATE TABLE t(x INTEGER PRIMARY KEY)");
        System.out.println("[DEBUG] CREATE rc=" + rc);
        drain.invoke(conn);

        rc = conn.exec("INSERT INTO t(x) VALUES(1)");
        System.out.println("[DEBUG] INSERT rc=" + rc);
        Object result = drain.invoke(conn);
        System.out.println("[DEBUG] drain after INSERT returned: " + result);

        rc = conn.exec("UPDATE t SET x=2 WHERE x=1");
        System.out.println("[DEBUG] UPDATE rc=" + rc);
        drain.invoke(conn);

        rc = conn.exec("DELETE FROM t WHERE x=2");
        System.out.println("[DEBUG] DELETE rc=" + rc);
        drain.invoke(conn);

        conn.close();
        System.out.println("[DEBUG] done");
    }

    @Test
    void debug_parse_event() throws Exception {
        String json = "{\"type\":\"INSERT\",\"database\":\"main\",\"table\":\"t\",\"rowId\":1}";
        SqliteChangeEvent event = SqliteHookConnection.parseEvent(json);
        System.out.println("[DEBUG] parseEvent: " + event);
        if (event != null) {
            System.out.println("[DEBUG]   type=" + event.getType() + " table=" + event.getTable() + " rowId=" + event.getRowId());
        }
    }
}
