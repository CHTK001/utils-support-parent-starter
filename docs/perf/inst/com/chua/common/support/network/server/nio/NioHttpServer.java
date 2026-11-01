package com.chua.common.support.network.server.nio;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.network.server.websocket.WebSocketProtocol;
import com.chua.common.support.ssl.SslUtils;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Instrumented copy of NioHttpServer (perf analysis only) -- prints per-request buckets.
 * 2026-11-01.
 */
@Slf4j
public class NioHttpServer extends AbstractServer {

    /** (=0..1us 1..10 10..100 100..1000 1000..10000 10000+) */
    private static final int NB=6;
    private static final LongAdder[] buckets = new LongAdder[NB];
    private static final LongAdder[] handles = new LongAdder[NB];
    private static final LongAdder writeSlow = new LongAdder();
    private static final LongAdder totalRequests = new LongAdder();
    private static final AtomicLong lastPrint = new AtomicLong();

    static {
        for (int i=0;i<NB;i++) { buckets[i]=new LongAdder(); handles[i]=new LongAdder(); }
    }

    private static void bucket(long us) {
        int idx;
        if (us<1) idx=0;
        else if (us<10) idx=1;
        else if (us<100) idx=2;
        else if (us<1000) idx=3;
        else if (us<10000) idx=4;
        else idx=5;
        buckets[idx].increment();
        totalRequests.increment();
        if ((long)System.nanoTime() - lastPrint.get() > 300_000_000_000L) { // every 5s
            if (lastPrint.compareAndSet(lastPrint.get(), (long)System.nanoTime())) {
                for (int i=0;i<NB;i++) System.out.println("[perf] idx="+i+" count="+buckets[i].sum());
            }
        }
    }

    private static void handleBucket(long us) {
        int idx;
        if (us<1) idx=0; else if (us<10) idx=1; else if (us<100) idx=2; else if (us<1000) idx=3;
        else if (us<10000) idx=4; else idx=5;
        handles[idx].increment();
    }

    public NioHttpServer(ServerSetting setting) {
        super(setting);
    }

    /** protocol */
    @Override
    public ProtocolType protocolType() {
        return ProtocolType.HTTP;
    }

    @Override
    protected void doStart() {
        try {
            int eventLoops = setting.getEventLoops();
            String osName = System.getProperty("os.name", "").toLowerCase();
            boolean windows = osName.contains("win");
            if (eventLoops <= 0) {
                int cpus = Runtime.getRuntime().availableProcessors();
                eventLoops = windows ? 4 : Math.max(cpus, 2);
            }
            eventLoops = Math.max(1, eventLoops);
            eventLoops = Math.min(eventLoops, 8);
            this.eventLoops = eventLoops;
            this.registerPorts();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void registerPorts() {
        try {
            InetSocketAddress bind = new InetSocketAddress("127.0.0.1", 0);
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true);
            int backlog = Math.max(setting.getBacklog(), 1024);
            serverChannel.bind(bind, backlog);
            InetSocketAddress local = (InetSocketAddress) serverChannel.getLocalAddress();
            setting.setPort(local.getPort());
            executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            eventLoops = Math.max(1, eventLoops);
            for (int i=0;i<eventLoops;i++) {
                Selector sel = Selector.open();
                queues[i] = new ConcurrentLinkedQueue<>();
                rearmQueues[i] = new ConcurrentLinkedQueue<>();
                acceptQueues[i] = new ConcurrentLinkedQueue<>();
            }
            acceptorPool = ThreadUtils.newDaemonFixedThreadPool(eventLoops, "nio-event-loop");
            for (int i=0;i<eventLoops;i++) {
                final int idx = i;
                acceptorPool.execute(() -> {
                    try { eventLoop(idx); } catch (Throwable t) { log.error("event-loop[{}] err: {}", idx, t.getMessage(), t); }
                });
            }
            log.info("instrumented NIO server starts port=" + local.getPort() + " eventLoops=" + eventLoops);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void eventLoop(int idx) {
        Selector sel = selectors[idx];
        java.util.Queue<SelectionKey> writeQueue = pendingWriteQueues[idx];
        log.info("event-loop[{}] started", idx);
        if (idx == 0 && serverChannel != null && serverChannel.isOpen()) {
            try { serverChannel.register(sel, SelectionKey.OP_ACCEPT); } catch (Exception e) { log.warn(e.getMessage(), e); }
        }
        while (running) {
            try {
                sel.select(200L);
                SelectionKey rk;
                while ((rk = rearmReadQueues[idx].poll()) != null) {
                    try {
                        if (rk.isValid()) {
                            ConnectionState rst = (ConnectionState) rk.attachment();
                            if (rst != null && rst.keepAlive && running) {
                                rk.interestOps(SelectionKey.OP_READ);
                                rst.inWorker = false;
                            } else if (rst != null) {
                                closeConn(rk, rst);
                            }
                        }
                    } catch (Exception e) { closeQuietly(rk.channel() instanceof SocketChannel sc ? sc : null); }
                }
                SelectionKey wk;
                while ((wk = writeQueue.poll()) != null) {
                    try { if (wk.isValid()) wk.interestOps(SelectionKey.OP_WRITE); }
                    catch (Exception e) { closeQuietly(wk.channel() instanceof SocketChannel sc ? sc : null); }
                }
                SocketChannel ac;
                while ((ac = pendingAcceptQueues[idx].poll()) != null) {
                    try {
                        if (!ac.isOpen()) continue;
                        ConnectionState st = new ConnectionState(ac, setting.getMaxRequestSize(), setting.getCharset());
                        st.shard = idx;
                        ac.register(sel, SelectionKey.OP_READ, st);
                    } catch (Exception e) { closeQuietly(ac); }
                }
                Iterator<SelectionKey> it = sel.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    try {
                        if (!key.isValid()) continue;
                        if (key.isAcceptable()) handleAccept(key);
                        else if (key.isReadable()) handleRead(key);
                        else if (key.isWritable()) handleWrite(key);
                    } catch (Throwable t) { log.warn("key error: {}", t.getMessage(), t); }
                }
            } catch (Throwable t) {
                if (running) log.warn("event loop[{}] error: {}", idx, t.getMessage(), t);
            }
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        SocketChannel accepted = serverChannel.accept();
        if (accepted == null) return;
        accepted.configureBlocking(false);
        accepted.setOption(java.net.StandardSocketOptions.TCP_NODELAY, setting.isTcpNoDelay());
        int shard = (accepted.hashCode() & Integer.MAX_VALUE) % selectors.length;
        pendingAcceptQueues[shard].add(accepted);
        selectors[shard].wakeup();
    }

    private void handleRead(SelectionKey key) throws IOException {
        ConnectionState st = (ConnectionState) key.attachment();
        // exec log: time from event loop wake to read
        long t0 = System.nanoTime();
        if (st.inWorker) {
            key.interestOps(0);
            return;
        }
        ByteBuffer buf = st.readBuf;
        if (buf == null) {
            buf = ByteBuffer.allocate(32768);
            st.readBuf = buf;
        }
        int n = st.channel.read(buf);
        if (n < 0) { closeConn(key, st); return; }
        if (n == 0) return;
        buf.flip();
        int r = st.request.feed(buf);
        buf.compact();
        if (r == 1) {
            key.interestOps(0);
            st.inWorker = true;
            if (setting.isInlineDispatch() && sslContext == null && !WebSocketProtocol.isUpgradeRequest(st.request)) {
                long p0 = System.nanoTime();
                processRequestInline(st, key);
                long us = (System.nanoTime() - p0)/1000;
                handleBucket(us);
            } else {
                executor.submit(() -> processRequestDirectWrite(st, key));
            }
        } else if (r < 0) {
            closeConn(key, st);
        }
    }

    private void processRequestInline(ConnectionState st, SelectionKey key) {
        try {
            NioServerResponse response = new NioServerResponse(st.channel);
            long t0 = System.nanoTime();
            response.setAsyncWriter((header, body) -> {
                synchronized (st.writeQueue) {
                    st.writeQueue.add(header);
                    if (body != null && body.hasRemaining()) st.writeQueue.add(body);
                }
                flushInlineWrite(st, key);
            });
            try {
                handleRequest(st.request, response);
            } catch (Exception e) {
                log.debug("Inline handler failed: {}", e.getMessage());
                if (!response.isCommitted()) response.sendError(500, "Internal Server Error");
            } finally {
                response.complete();
            }
            st.keepAlive = shouldKeepAlive(st.request, response);
            st.request.resetForNextRequest();
            boolean hasPending;
            synchronized (st.writeQueue) {
                hasPending = !st.writeQueue.isEmpty();
            }
            if (hasPending) {
                pendingWriteQueues[st.shard].add(key);
                selectors[st.shard].wakeup();
            } else {
                finishAfterWrite(st, key);
            }
            long us = (System.nanoTime() - t0)/1000;
            if (us > 1000) System.out.println("[perf] SLOW-inline " + st.shard + " req=" + us + " us");
        } catch (Exception e) {
            log.warn("Inline worker failed: {}", e.getMessage(), e);
            closeConn(key, st);
        }
    }

    private void finishAfterWrite(ConnectionState st, SelectionKey key) {
        if (st.keepAlive && running) {
            key.interestOps(SelectionKey.OP_READ);
            st.inWorker = false;
        } else {
            closeConn(key, st);
        }
    }

    private void flushInlineWrite(ConnectionState st, SelectionKey key) {
        synchronized (st.writeQueue) {
            long t0 = System.nanoTime();
            while (true) {
                ByteBuffer bb = st.writeQueue.peek();
                if (bb == null) break;
                try {
                    int w = st.channel.write(bb);
                    if (w < 0) { closeConn(key, st); return; }
                    if (bb.hasRemaining()) return;
                } catch (IOException e) { closeConn(key, st); return; }
                st.writeQueue.poll();
            }
            long us = (System.nanoTime() - t0)/1000;
            if (us > 1000) System.out.println("[perf] SLOW-write " + st.shard + " write=" + us + " us");
        }
    }

    private void processRequest(ConnectionState st, SelectionKey key) {
        try {
            if (WebSocketProtocol.isUpgradeRequest(st.request)) {
                key.cancel();
                st.channel.configureBlocking(true);
                handleWebSocketUpgrade(st.channel, st.request);
                closeConn(key, st);
                return;
            }
            NioServerResponse response = new NioServerResponse(st.channel);
            response.setAsyncWriter((header, body) -> {
                synchronized (st.writeQueue) {
                    st.writeQueue.add(header);
                    if (body != null && body.hasRemaining()) st.writeQueue.add(body);
                }
                pendingWriteQueues[st.shard].add(key);
                selectors[st.shard].wakeup();
            });
            try {
                handleRequest(st.request, response);
            } catch (Exception e) {
                log.warn("Request handling failed: {}", e.getMessage(), e);
                if (!response.isCommitted()) response.sendError(500, "Internal Server Error");
            } finally {
                response.complete();
            }
            st.keepAlive = shouldKeepAlive(st.request, response);
            st.request.resetForNextRequest();
            boolean hasPending;
            synchronized (st.writeQueue) { hasPending = !st.writeQueue.isEmpty(); }
            if (hasPending) {
                pendingWriteQueues[st.shard].add(key);
                selectors[st.shard].wakeup();
            }
        } catch (Exception e) {
            log.warn("Worker handling failed: {} -> {}", e.getClass().getSimpleName(), e.getMessage(), e);
            closeConn(key, st);
        }
    }

    private void processRequestDirectWrite(ConnectionState st, SelectionKey key) {
        try {
            if (WebSocketProtocol.isUpgradeRequest(st.request)) {
                key.cancel();
                st.channel.configureBlocking(true);
                handleWebSocketUpgrade(st.channel, st.request);
                closeConn(key, st);
                return;
            }
            NioServerResponse response = new NioServerResponse(st.channel);
            response.setAsyncWriter((header, body) -> {
                synchronized (st.writeQueue) {
                    st.writeQueue.add(header);
                    if (body != null && body.hasRemaining()) st.writeQueue.add(body);
                }
            });
            try {
                handleRequest(st.request, response);
            } catch (Exception e) {
                log.warn("Request handling failed: {}", e.getMessage(), e);
                if (!response.isCommitted()) response.sendError(500, "Internal Server Error");
            } finally {
                response.complete();
            }
            st.keepAlive = shouldKeepAlive(st.request, response);
            st.request.resetForNextRequest();
            boolean allWritten;
            synchronized (st.writeQueue) {
                allWritten = drainWriteQueue(st, key);
            }
            if (allWritten) {
                rearmReadQueues[st.shard].add(key);
                selectors[st.shard].wakeup();
            } else {
                pendingWriteQueues[st.shard].add(key);
                selectors[st.shard].wakeup();
            }
        } catch (Exception e) {
            log.warn("Worker handling failed: {} -> {}", e.getClass().getSimpleName(), e.getMessage(), e);
            closeConn(key, st);
        }
    }

    private boolean drainWriteQueue(ConnectionState st, SelectionKey key) {
        while (true) {
            ByteBuffer bb = st.writeQueue.peek();
            if (bb == null) return true;
            try {
                int w = st.channel.write(bb);
                if (w < 0) { closeConn(key, st); return true; }
                if (bb.hasRemaining()) return false;
            } catch (IOException e) { closeConn(key, st); return true; }
            st.writeQueue.poll();
        }
    }

    private void handleWrite(SelectionKey key) throws IOException {
        ConnectionState st = (ConnectionState) key.attachment();
        long t0 = System.nanoTime();
        synchronized (st.writeQueue) {
            while (true) {
                ByteBuffer bb = st.writeQueue.peek();
                if (bb == null) break;
                int w = st.channel.write(bb);
                if (w < 0) { closeConn(key, st); return; }
                if (bb.hasRemaining()) return;
                st.writeQueue.poll();
            }
        }
        long us = (System.nanoTime() - t0)/1000;
        if (us > 1000) { writeSlow.increment(); System.out.println("[perf] SLOW-write queue " + st.shard + " write=" + us + " us"); }
        if (st.keepAlive && running) {
            key.interestOps(SelectionKey.OP_READ);
            st.inWorker = false;
        } else {
            closeConn(key, st);
        }
    }

    private void closeConn(SelectionKey key, ConnectionState st) {
        try { key.cancel(); } catch (Exception e) { log.debug(e.getMessage(), e); }
        closeQuietly(st.channel);
    }
}
