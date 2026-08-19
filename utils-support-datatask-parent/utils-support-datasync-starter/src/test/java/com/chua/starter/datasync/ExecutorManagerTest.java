package com.chua.starter.datasync;

import com.chua.datasync.agent.support.executor.ReactorDataSyncExecutor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** @author CH */
class ExecutorManagerTest {

    @Test
    void shouldCreateExecutorOnFirstGet() {
        var mgr = new DefaultExecutorManager("server1");
        ReactorDataSyncExecutor exec = mgr.getExecutor("topic1");
        assertNotNull(exec);
    }

    @Test
    void shouldReuseExecutorForSameTopic() {
        var mgr = new DefaultExecutorManager("server1");
        ReactorDataSyncExecutor e1 = mgr.getExecutor("t");
        ReactorDataSyncExecutor e2 = mgr.getExecutor("t");
        assertSame(e1, e2);
    }

    @Test
    void shouldCreateDifferentExecutorsForDifferentTopics() {
        var mgr = new DefaultExecutorManager("server1");
        assertNotSame(mgr.getExecutor("a"), mgr.getExecutor("b"));
    }

    @Test
    void startStopShouldNotThrow() {
        var mgr = new DefaultExecutorManager("server1");
        assertDoesNotThrow(mgr::start);
        assertDoesNotThrow(mgr::stop);
    }

    @Test
    void shouldCountExecutors() {
        var mgr = new DefaultExecutorManager("server1");
        mgr.getExecutor("a");
        mgr.getExecutor("b");
        assertEquals(2, mgr.getExecutorCount());
    }
}

