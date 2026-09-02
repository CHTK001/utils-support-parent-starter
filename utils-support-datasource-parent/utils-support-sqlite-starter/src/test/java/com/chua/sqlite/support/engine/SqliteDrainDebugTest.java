package com.chua.sqlite.support.engine;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

public class SqliteDrainDebugTest {
    @Test
    public void test_drain_thread_basic() throws Exception {
        System.out.println("[1] Starting");
        Path db = Files.createTempFile("sqlite-drain", ".db");
        db.toFile().deleteOnExit();
        
        System.out.println("[2] Opening connection...");
        SqliteHookConnection conn = SqliteHookConnection.open(db.toString());
        assertNotNull(conn, "Connection must not be null");
        System.out.println("[3] Connection opened, isOpen=" + conn.isOpen());
        
        // Give drain thread a moment to start
        Thread.sleep(100);
        
        System.out.println("[4] Exec CREATE TABLE...");
        int rc = conn.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT)");
        System.out.println("[5] CREATE TABLE returned rc=" + rc);
        
        final boolean[] received = {false};
        conn.changes().subscribe(
            e -> { System.out.println("[6] Event: " + e.getType() + " on " + e.getTable()); received[0] = true; },
            err -> System.err.println("[6b] Error: " + err),
            () -> System.out.println("[6c] Complete")
        );
        
        System.out.println("[7] Exec INSERT...");
        rc = conn.exec("INSERT INTO t(name) VALUES('test')");
        System.out.println("[8] INSERT returned rc=" + rc);
        
        // Wait with timeout
        for (int i = 0; i < 10 && !received[0]; i++) {
            Thread.sleep(200);
            System.out.println("[9] Waiting... received=" + received[0] + " isOpen=" + conn.isOpen());
        }
        
        assertTrue(received[0], "Should have received INSERT event");
        System.out.println("[10] Test PASSED!");
        
        conn.close();
        System.out.println("[11] Closed");
    }
}
