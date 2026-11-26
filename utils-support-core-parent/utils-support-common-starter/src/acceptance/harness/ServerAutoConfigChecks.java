package com.chua.common.support.network.server;

import com.chua.common.support.network.server.ServerSetting;

/** Checks configuration precedence and repeatability without opening any network listener. */
public final class ServerAutoConfigChecks {
    private static int checks;
    private static int failures;

    private static void check(String name, boolean result) {
        checks++;
        if (!result) { failures++; }
        System.out.println((result ? "PASS " : "FAIL ") + name);
    }

    public static void main(String[] args) {
        ServerSetting setters = new ServerSetting();
        setters.setWorkerThreads(3);
        setters.setBacklog(37);
        setters.setMaxFrameSize(12345);
        setters.setMaxKeepAliveRequests(19);
        setters.setReactor(Runtime.getRuntime().availableProcessors() < 8);
        setters.setMaxConnections(321);
        setters.setMaxConcurrency(0);
        setters.setBufferSize(3073);
        setters.setUseDirectBuffer(true);
        setters.autoConfig().autoConfig();
        check("setter worker threads retained", setters.getWorkerThreads() == 3);
        check("setter backlog retained", setters.getBacklog() == 37);
        check("setter frame limit retained", setters.getMaxFrameSize() == 12345);
        check("setter keep-alive retained", setters.getMaxKeepAliveRequests() == 19);
        check("setter reactor retained", setters.isReactor() == (Runtime.getRuntime().availableProcessors() < 8));
        check("setter connection limit retained", setters.getMaxConnections() == 321);
        check("setter unlimited concurrency retained", setters.getMaxConcurrency() == 0);
        check("setter buffer retained", setters.getBufferSize() == 3073);
        check("setter direct memory retained", setters.isUseDirectBuffer());

        ServerSetting built = ServerSetting.builder().workerThreads(3).backlog(37).maxFrameSize(12345)
                .maxKeepAliveRequests(19).reactor(Runtime.getRuntime().availableProcessors() < 8)
                .maxConnections(321).maxConcurrency(0).bufferSize(3073).useDirectBuffer(true).build();
        built.autoConfig().autoConfig();
        check("builder worker threads retained", built.getWorkerThreads() == 3);
        check("builder backlog retained", built.getBacklog() == 37);
        check("builder frame limit retained", built.getMaxFrameSize() == 12345);
        check("builder keep-alive retained", built.getMaxKeepAliveRequests() == 19);
        check("builder reactor retained", built.isReactor() == (Runtime.getRuntime().availableProcessors() < 8));
        check("builder connection limit retained", built.getMaxConnections() == 321);
        check("builder unlimited concurrency retained", built.getMaxConcurrency() == 0);
        check("builder buffer retained", built.getBufferSize() == 3073);
        check("builder direct memory retained", built.isUseDirectBuffer());
        ServerSetting auto = ServerSetting.auto();
        int connections = auto.getMaxConnections();
        int buffer = auto.getBufferSize();
        auto.autoConfig();
        check("repeated auto configuration is stable", connections == auto.getMaxConnections() && buffer == auto.getBufferSize());
        long budgetKb = Runtime.getRuntime().maxMemory() * 3L / 5L / 1024L;
        check("automatic connection estimate respects heap budget", auto.getMaxConnections() > 0
                && auto.getMaxConnections() * auto.effectivePerConnectionKb() <= budgetKb);
        ServerSetting event = new ServerSetting();
        event.autoConfig();
        event.setIoThreads(1);
        event.autoConfig();
        check("explicit IO threads replace previously automatic loops", event.getIoThreads() == 1 && event.getEventLoops() == 1);
        check("fractional KB buffer rounds upward", setters.effectivePerConnectionKb() == 16);
        check("fd parser reads soft column", ServerSetting.parseFdLimit("Max open files            1024                 1048576              files") == 1024);
        check("fd parser accepts whitespace", ServerSetting.parseFdLimit("  Max open files\t4096\t65536\tfiles ") == 4096);
        check("fd parser preserves unlimited", ServerSetting.parseFdLimit("Max open files            unlimited            unlimited            files") == Long.MAX_VALUE);
        check("fd parser preserves zero", ServerSetting.parseFdLimit("Max open files 0 65536 files") == 0);
        check("fd parser rejects unrelated resource", ServerSetting.parseFdLimit("Max processes 1024 2048 processes") == -1);
        check("fd parser rejects malformed number", ServerSetting.parseFdLimit("Max open files invalid 65536 files") == -1);
        check("fd parser rejects overflow", ServerSetting.parseFdLimit("Max open files 999999999999999999999999 65536 files") == -1);
        check("fd parser accepts absent input", ServerSetting.parseFdLimit(null) == -1);
        ServerSetting.ServerSettingBuilder reuse = ServerSetting.builder().workerThreads(5).bufferSize(4097);
        ServerSetting a = reuse.build().autoConfig();
        ServerSetting b = reuse.build().autoConfig();
        check("reused builder preserves explicit values", a != b && a.getWorkerThreads() == 5
                && b.getWorkerThreads() == 5 && a.getBufferSize() == 4097 && b.getBufferSize() == 4097);
        check("last IO alias setter wins in builder", ServerSetting.builder().eventLoops(3).ioThreads(1)
                .build().autoConfig().getEventLoops() == 1);
        check("last event alias setter wins in builder", ServerSetting.builder().ioThreads(3).eventLoops(1)
                .build().autoConfig().getIoThreads() == 1);
        event.setEventLoops(0);
        event.autoConfig();
        check("zero restores automatic IO selection", event.getEventLoops() == event.resolveEventLoops(
                Runtime.getRuntime().availableProcessors(), System.getProperty("os.name").startsWith("Windows")));
        System.out.println("RESULT autoChecks=" + checks + " fail=" + failures);
        if (failures != 0) { throw new AssertionError("configuration failures=" + failures); }
    }
}
