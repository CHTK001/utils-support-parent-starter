package com.chua.filesystem.log.support.polling;

import com.chua.common.support.lang.directory.EventObserver;
import com.chua.common.support.lang.directory.PolledListener;
import com.chua.common.support.lang.directory.WatcherEvent;
import com.chua.filesystem.log.support.SystemLogService;
import com.chua.filesystem.log.support.model.LogEntry;
import com.chua.filesystem.log.support.model.LogLevel;
import com.chua.filesystem.log.support.model.LogQuery;
import com.chua.filesystem.log.support.spi.SystemLogProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单元测试：通过注入 {@link SystemLogService} 与可控制的 {@link SystemLogProvider}
 * 验证 {@link SyslogPolledDirectory} 的游标推进、增量分发、过滤、多监听器等行为。
 *
 * @author CH
 * @since 4.0.0
 */
class SyslogPolledDirectoryTest {

    /**
     * 可在测试中替换日志快照的最小 mock provider。
     */
    private static final class FakeProvider implements SystemLogProvider {
        private final List<LogEntry> snapshot;
        private final List<String> sources;

        FakeProvider(List<String> sources, List<LogEntry> snapshot) {
            this.sources = sources;
            this.snapshot = new ArrayList<>(snapshot);
        }

        void setSnapshot(List<LogEntry> entries) {
            this.snapshot.clear();
            this.snapshot.addAll(entries);
        }

        @Override
        public boolean isPlatformSupported() {
            return true;
        }

        @Override
        public List<String> getSources() {
            return sources;
        }

        @Override
        public List<LogEntry> search(LogQuery query) {
            List<LogEntry> out = new ArrayList<>();
            for (LogEntry e : snapshot) {
                if (query.source() != null && !query.source().equals(e.source())) {
                    continue;
                }
                if (query.minLevel() != null && !e.level().meetsMinimum(query.minLevel())) {
                    continue;
                }
                out.add(e);
                if (out.size() >= query.maxResults()) {
                    break;
                }
            }
            return out;
        }
    }

    private static SystemLogService serviceWith(FakeProvider provider) {
        return new SystemLogService() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<LogEntry> search(LogQuery query) {
                return provider.search(query);
            }
        };
    }

    private static LogEntry entry(String ts, LogLevel lvl, String src, String msg) {
        return new LogEntry(ts, lvl, src, msg, "test", null);
    }

    /**
     * 首次轮询只记录游标，不推送任何历史条目。
     */
    @Test
    void firstUpgrade_onlyAdvancesCursor_noEvents() {
        FakeProvider provider = new FakeProvider(
                List.of("System"),
                List.of(
                        entry("2026-08-03T00:00:01.000", LogLevel.INFO, "System", "old-1"),
                        entry("2026-08-03T00:00:02.000", LogLevel.INFO, "System", "old-2"),
                        entry("2026-08-03T00:00:03.000", LogLevel.WARNING, "System", "old-3")
                )
        );
        SyslogPolledDirectory watcher = SyslogPolledDirectory.builder()
                .pollIntervalSeconds(1)
                .service(serviceWith(provider))
                .build();
        List<LogEntry> received = new CopyOnWriteArrayList<>();
        watcher.addListener((event, observer) -> received.add((LogEntry) observer.getSource()));

        watcher.upgrade();

        assertEquals(0, received.size(), "首次轮询不应推送任何条目");
        assertEquals("2026-08-03T00:00:03.000", watcher.getLastTimestamp(), "游标应推进到最新条目时间戳");
    }

    /**
     * 第二轮新条目应通过 onModify 分发，EventObserver 字段全部正确。
     */
    @Test
    void secondUpgrade_dispatchesNewEntries_withCorrectObserverFields() {
        FakeProvider provider = new FakeProvider(
                List.of("System"),
                List.of(
                        entry("2026-08-03T00:00:01.000", LogLevel.INFO, "System", "old-1"),
                        entry("2026-08-03T00:00:02.000", LogLevel.INFO, "System", "old-2")
                )
        );
        SyslogPolledDirectory watcher = SyslogPolledDirectory.builder()
                .source("System")
                .pollIntervalSeconds(1)
                .service(serviceWith(provider))
                .build();
        List<EventObserver> observers = new CopyOnWriteArrayList<>();
        watcher.addListener((event, observer) -> observers.add(observer));

        watcher.upgrade(); // first poll: cursor set, no events
        assertTrue(observers.isEmpty());

        provider.setSnapshot(List.of(
                entry("2026-08-03T00:00:03.000", LogLevel.WARNING, "System", "new-1"),
                entry("2026-08-03T00:00:04.000", LogLevel.ERROR, "System", "new-2")
        ));

        watcher.upgrade(); // second poll

        assertEquals(2, observers.size(), "应分发 2 条新条目");
        EventObserver first = observers.get(0);
        assertEquals(WatcherEvent.MODIFY, first.getEventType());
        assertEquals("System", first.getCurrentPath(), "currentPath 应等于配置的 source");
        assertEquals("System-WARNING", first.getTriggerFile(), "triggerFile 应为 source + '-' + level");
        assertNotNull(first.getSource());
        assertTrue(first.getSource() instanceof LogEntry);

        LogEntry payload0 = (LogEntry) first.getSource();
        assertEquals("2026-08-03T00:00:03.000", payload0.timestamp());
        assertEquals(LogLevel.WARNING, payload0.level());
        assertEquals("System", payload0.source(), "LogEntry.source 应为真实打开的 channel，而非硬编码");
        assertEquals("new-1", payload0.message());

        LogEntry payload1 = (LogEntry) observers.get(1).getSource();
        assertEquals("System-ERROR", observers.get(1).getTriggerFile());
        assertEquals("new-2", payload1.message());
        assertEquals("2026-08-03T00:00:04.000", watcher.getLastTimestamp(), "游标应推进到最后一条");
    }

    /**
     * 同时间戳重复的条目不重复分发（防止边界抖动）。
     */
    @Test
    void sameTimestampEntry_notRedispatched() {
        FakeProvider provider = new FakeProvider(
                List.of("System"),
                List.of(entry("2026-08-03T00:00:01.000", LogLevel.INFO, "System", "x"))
        );
        SyslogPolledDirectory watcher = SyslogPolledDirectory.builder()
                .pollIntervalSeconds(1)
                .service(serviceWith(provider))
                .build();
        AtomicInteger count = new AtomicInteger();
        watcher.addListener((event, observer) -> count.incrementAndGet());

        watcher.upgrade(); // cursor
        watcher.upgrade(); // same snapshot, timestamp equals cursor -> skip
        watcher.upgrade();

        assertEquals(0, count.get(), "时间戳等于游标的条目不应再次分发");
    }

    /**
     * minLevel 过滤：INFO 不应触发，但 WARNING 应触发。
     */
    @Test
    void minLevelFilter_respectsQuery() {
        FakeProvider provider = new FakeProvider(
                List.of("System"),
                new ArrayList<>(List.of(
                        entry("2026-08-03T00:00:01.000", LogLevel.INFO, "System", "i1"),
                        entry("2026-08-03T00:00:02.000", LogLevel.WARNING, "System", "w1")
                ))
        );
        SyslogPolledDirectory watcher = SyslogPolledDirectory.builder()
                .minLevel(LogLevel.WARNING)
                .pollIntervalSeconds(1)
                .service(serviceWith(provider))
                .build();
        List<LogEntry> out = new CopyOnWriteArrayList<>();
        watcher.addListener((event, observer) -> out.add((LogEntry) observer.getSource()));

        watcher.upgrade(); // first
        provider.setSnapshot(List.of(
                entry("2026-08-03T00:00:03.000", LogLevel.INFO, "System", "i2"),
                entry("2026-08-03T00:00:04.000", LogLevel.ERROR, "System", "e1")
        ));
        watcher.upgrade();

        assertEquals(1, out.size(), "仅 ERROR 应被分发");
        assertEquals(LogLevel.ERROR, out.get(0).level());
    }

    /**
     * source / pattern 应透传给 LogQuery。
     */
    @Test
    void sourceAndPatternPassedToQuery() {
        FakeProvider provider = new FakeProvider(
                List.of("Application"),
                Collections.emptyList()
        );
        AtomicReference<LogQuery> captured = new AtomicReference<>();
        SystemLogService service = new SystemLogService() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<LogEntry> search(LogQuery query) {
                captured.set(query);
                return provider.search(query);
            }
        };

        SyslogPolledDirectory watcher = SyslogPolledDirectory.builder()
                .source("Application")
                .pattern("*disk*")
                .service(service)
                .build();
        watcher.upgrade();

        LogQuery q = captured.get();
        assertNotNull(q);
        assertEquals("Application", q.source());
        assertEquals("*disk*", q.pattern());
        assertEquals(LogQuery.ORDER_ASC, q.order());
    }

    /**
     * 同一事件应被所有已注册的监听器收到。
     */
    @Test
    void multipleListeners_allReceiveSameEvent() {
        FakeProvider provider = new FakeProvider(
                List.of("System"),
                List.of(entry("2026-08-03T00:00:01.000", LogLevel.INFO, "System", "old"))
        );
        SyslogPolledDirectory watcher = SyslogPolledDirectory.builder()
                .pollIntervalSeconds(1)
                .service(serviceWith(provider))
                .build();
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        AtomicInteger c = new AtomicInteger();
        watcher.addListener((event, observer) -> a.incrementAndGet());
        watcher.addListener((event, observer) -> b.incrementAndGet());
        watcher.addListener((event, observer) -> c.incrementAndGet());

        watcher.upgrade(); // first
        provider.setSnapshot(List.of(
                entry("2026-08-03T00:00:02.000", LogLevel.INFO, "System", "new")
        ));
        watcher.upgrade();

        assertEquals(1, a.get());
        assertEquals(1, b.get());
        assertEquals(1, c.get());
    }

    /**
     * 监听器抛异常不应影响其他监听器与后续轮询。
     */
    @Test
    void listenerException_doesNotBreakOthersOrNextPoll() {
        FakeProvider provider = new FakeProvider(
                List.of("System"),
                List.of(entry("2026-08-03T00:00:01.000", LogLevel.INFO, "System", "old"))
        );
        SyslogPolledDirectory watcher = SyslogPolledDirectory.builder()
                .pollIntervalSeconds(1)
                .service(serviceWith(provider))
                .build();
        AtomicInteger okCount = new AtomicInteger();
        watcher.addListener((event, observer) -> {
            throw new RuntimeException("boom");
        });
        watcher.addListener((event, observer) -> okCount.incrementAndGet());

        watcher.upgrade(); // first
        provider.setSnapshot(List.of(
                entry("2026-08-03T00:00:02.000", LogLevel.INFO, "System", "new")
        ));
        watcher.upgrade();

        assertEquals(1, okCount.get(), "异常监听器不应阻断其他监听器");
    }

    /**
     * close() 后 running 置 false，后续 upgrade() 应直接返回（不再触发 listener）。
     */
    @Test
    void closeStopsPolling() {
        FakeProvider provider = new FakeProvider(
                List.of("System"),
                List.of(entry("2026-08-03T00:00:01.000", LogLevel.INFO, "System", "old"))
        );
        SyslogPolledDirectory watcher = SyslogPolledDirectory.builder()
                .pollIntervalSeconds(1)
                .service(serviceWith(provider))
                .build();
        AtomicInteger count = new AtomicInteger();
        watcher.addListener((event, observer) -> count.incrementAndGet());

        watcher.upgrade(); // first
        watcher.close();
        provider.setSnapshot(List.of(
                entry("2026-08-03T00:00:02.000", LogLevel.INFO, "System", "new")
        ));
        watcher.upgrade(); // should be no-op

        assertEquals(0, count.get(), "close 后 upgrade 不应再触发监听器");
        assertNull(watcher.getLastTimestamp(), "close 应清空游标");
    }

    /**
     * 服务不可用时 upgrade() 安全返回（不抛异常）。
     */
    @Test
    void unavailableService_upgradeIsNoop() {
        SystemLogService unavailable = new SystemLogService() {
            @Override
            public boolean isAvailable() {
                return false;
            }
        };
        SyslogPolledDirectory watcher = SyslogPolledDirectory.builder()
                .service(unavailable)
                .build();
        assertDoesNotThrow(watcher::upgrade);
        assertNull(watcher.getLastTimestamp());
    }

    /**
     * isDelegatedOperatingSystem 返回 false，表明本实现使用定时轮询而非 OS 事件。
     */
    @Test
    void isDelegatedOperatingSystem_returnsFalse() {
        SyslogPolledDirectory watcher = SyslogPolledDirectory.builder().build();
        assertFalse(watcher.isDelegatedOperatingSystem());
    }
}