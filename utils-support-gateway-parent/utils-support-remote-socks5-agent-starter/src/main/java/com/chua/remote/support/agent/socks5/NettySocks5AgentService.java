package com.chua.remote.support.agent.socks5;

import com.chua.remote.support.agent.BaseRemoteAgent;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Java/Netty 版 SOCKS5 反向 Agent 服务。

 * @author CH
 */@Slf4j
public class NettySocks5AgentService {

    // c h u n k_ s i z e
    private static final int CHUNK_SIZE = 48 * 1024;
    // c l o s e_ m a r k e r
    private static final byte[] CLOSE_MARKER = new byte[0];

    private final BaseRemoteAgent agent;
    private final Map<String, StreamWorker> streams = new ConcurrentHashMap<>();

    public NettySocks5AgentService(BaseRemoteAgent agent) {
        this.agent = agent;
    }

    /**
     * handleGatewayMessage
     * @param type 参数
     * @param Map<String 参数
     * @param payload 参数
     * @return handleGatewayMessage结果
     */
    public boolean handleGatewayMessage(String type, Map<String, Object> payload) {
        return switch (type) {
            case "socks_connect" -> {
                handleConnect(payload);
                yield true;
            }
            case "socks_data" -> {
                handleData(payload);
                yield true;
            }
            case "socks_close" -> {
                handleClose(payload);
                yield true;
            }
            default -> false;
        };
    }

    private void handleConnect(Map<String, Object> payload) {
        String streamId = stringValue(payload.get("streamId"));
        String host = stringValue(payload.get("host"));
        int port = intValue(payload.get("port"));
        if (streamId.isBlank() || host.isBlank() || port <= 0 || port > 65535) {
            sendError(streamId, 8, "invalid socks connect target");
            return;
        }

        StreamWorker worker = new StreamWorker(streamId, host, port);
        StreamWorker previous = streams.putIfAbsent(streamId, worker);
        if (previous != null) {
            sendError(streamId, 1, "duplicate stream");
            return;
        }
        Thread.ofVirtual().name("socks5-stream-" + streamId).start(worker);
    }

    private void handleData(Map<String, Object> payload) {
        String streamId = stringValue(payload.get("streamId"));
        String data = stringValue(payload.get("data"));
        StreamWorker worker = streams.get(streamId);
        if (worker == null || data.isBlank()) {
            return;
        }
        try {
            worker.write(Base64.getDecoder().decode(data));
        }
 catch (IllegalArgumentException e) {
            sendError(streamId, 1, "invalid data");
            closeStream(streamId, false);
        }
    }

    private void handleClose(Map<String, Object> payload) {
        closeStream(stringValue(payload.get("streamId")), false);
    }

    /**
     * closeAll
     */
    public void closeAll() {
        streams.keySet().forEach(streamId -> closeStream(streamId, true));
    }

    private void closeStream(String streamId, boolean notifyGateway) {
        if (streamId == null || streamId.isBlank()) {
            return;
        }
        StreamWorker worker = streams.remove(streamId);
        if (worker != null) {
            worker.close(notifyGateway ? "agent_closed" : null);
        }
    }

    private void sendConnected(String streamId) {
        send(Map.of("type", "socks_connected",
                "streamId", streamId,
                "bindHost", "0.0.0.0",
                "bindPort", 0));
    }

    private void sendData(String streamId, byte[] data, int len) {
        int offset = 0;
        while (offset < len) {
            int chunkLen = Math.min(len - offset, CHUNK_SIZE);
            byte[] chunk = new byte[chunkLen];
            System.arraycopy(data, offset, chunk, 0, chunkLen);
            send(Map.of(
                    "type", "socks_data",
                    "streamId", streamId,
                    "data", Base64.getEncoder().encodeToString(chunk)
            ));
            offset += chunkLen;
        }
    }

    private void sendError(String streamId, int replyCode, String message) {
        send(Map.of("type", "socks_error",
                "streamId", streamId == null ? "" : streamId,
                "replyCode", replyCode,
                "msg", message == null ? "connect failed" : message));
    }

    private void sendClosed(String streamId, String reason) {
        send(Map.of("type", "socks_closed",
                "streamId", streamId,
                "reason", reason));
    }

    private void send(Map<String, Object> payload) {
        try {
            agent.sendToGateway(agent.mapper.writeValueAsString(payload));
        }
 catch (Exception e) {
            log.warn("SOCKS5 消息发送失败: {}", e.getMessage());
        }
    }

    private int replyCode(Throwable cause) {
        if (cause instanceof ConnectException) { return 5; }
        if (cause instanceof NoRouteToHostException) { return 3; }
        if (cause instanceof UnknownHostException) { return 4; }
        if (cause instanceof SocketTimeoutException) { return 6; }
        return 1;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int intValue(Object value) {
        if (value instanceof Number n) { return n.intValue(); }
        try {
            return Integer.parseInt(String.valueOf(value));
        }
 catch (Exception ignored) {
            return 0;
        }
    }

    private final class StreamWorker implements Runnable {
        private final String streamId;
        private final String host;
        private final int port;
        private final BlockingQueue<byte[]> outbound = new LinkedBlockingQueue<>();
        private volatile Socket socket;
        private volatile boolean closed;

        private StreamWorker(String streamId, String host, int port) {
            this.streamId = streamId;
            this.host = host;
            this.port = port;
        }

        /**
         * 运行
         */
        @Override
        public void run() {
            try (Socket target = new Socket()) {
                socket = target;
                target.setTcpNoDelay(true);
                target.setKeepAlive(true);
                target.connect(new InetSocketAddress(host, port), 15_000);
                sendConnected(streamId);
                log.info("SOCKS5 目标连接成功: streamId={} {}:{}", streamId, host, port);

                Thread writer = Thread.ofVirtual().name("socks5-stream-writer-" + streamId).start(() -> writeLoop(target));
                readLoop(target);
                writer.interrupt();
            }
 catch (Exception e) {
                if (!closed) {
                    sendError(streamId, replyCode(e), e.getMessage());
                    log.warn("SOCKS5 目标连接失败: streamId={} {}:{} {}", streamId, host, port, e.getMessage());
                }
            }
 finally {
                if (streams.remove(streamId, this) && !closed) {
                    sendClosed(streamId, "target_closed");
                }
                closed = true;
            }
        }

        private void readLoop(Socket target) throws Exception {
            InputStream input = target.getInputStream();
            byte[] buffer = new byte[CHUNK_SIZE];
            int len;
            while (!closed && (len = input.read(buffer)) >= 0) {
                if (len > 0) {
                    sendData(streamId, buffer, len);
                }
            }
        }

        private void writeLoop(Socket target) {
            try {
                OutputStream output = target.getOutputStream();
                while (!closed) {
                    byte[] data = outbound.poll(30, TimeUnit.SECONDS);
                    if (data == null) {
                        continue;
                    }
                    if (data == CLOSE_MARKER) {
                        return;
                    }
                    output.write(data);
                    output.flush();
                }
            }
 catch (Exception e) {
                if (!closed) {
                    sendError(streamId, replyCode(e), e.getMessage());
                    close(null);
                }
            }
        }

        private void write(byte[] data) {
            if (!closed) {
                outbound.offer(data);
            }
        }

        private void close(String notifyReason) {
            closed = true;
            outbound.offer(CLOSE_MARKER);
            Socket current = socket;
            if (current != null) {
                try {
                    current.close();
                }
 catch (Exception ignored) {
                }
            }
            if (notifyReason != null) {
                sendClosed(streamId, notifyReason);
            }
        }
    }
}
