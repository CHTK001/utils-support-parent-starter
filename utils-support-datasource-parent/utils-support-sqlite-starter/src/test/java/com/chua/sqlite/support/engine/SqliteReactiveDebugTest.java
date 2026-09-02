package com.chua.sqlite.support.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SqliteReactiveDebugTest {
    @Test
    public void simple_debug() {
        System.out.println("DEBUG: Test started");
        SqliteReactorEngine engine = new SqliteReactorEngine();
        engine.addDataSource("default", ":memory:");
        System.out.println("DEBUG: Engine created");
        engine.close();
        System.out.println("DEBUG: Engine closed");
        assertTrue(true);
    }
}
