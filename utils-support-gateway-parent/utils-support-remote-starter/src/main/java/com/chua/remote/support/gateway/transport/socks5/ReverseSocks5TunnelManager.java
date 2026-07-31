package com.chua.remote.support.gateway.transport.socks5;

import com.chua.remote.support.gateway.agent.AgentInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Gateway SOCKS5 反向隧道管理器。

 * @author CH
 */@Slf4j
public class ReverseSocks5TunnelManager {

    private static final int CHUNK_SIZE = 48 * 1024;
    private static final long CONNECT_TIMEOUT_MILLIS = 15_000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, StreamState> streams = new ConcurrentHashMap<>();
    private final Map<String, String> streamByClient = new ConcurrentHashMap<>();

    public CompletableFuture<ConnectResult> openTunnel(AgentInfo agent, Channel clientChannel,
                                                       String host, int port, String addrType) {
        CompletableFuture<ConnectResult> future = new CompletableFuture<>();
        Channel agentChannel = agent != null ? agent.getChannel() : null;
        if (agentChannel == null || !agentChannel.isActive()) {
            future.complete(new ConnectResult(null, Socks5CommandStatus.HOST_UNREACHABLE, "agent offline"));
            return future;
        }

        String streamId = UUID.randomUUID().toString().replace("-", "");
        String clientAddress = clientChannel.remoteAddress() != null ? String.valueOf(clientChannel.remoteAddress()) : "";
        String agentAddress = agentChannel.remoteAddress() != null ? String.valueOf(agentChannel.remoteAddress()) : "";
        StreamState state = new StreamState(streamId, clientChannel, agentChannel, future,
                agent.getAgentId(), agent.getVerifyCode(), agent.getIpAddress(), agentAddress,
                clientAddress, host, port);
        streams.put(streamId, state);
        streamByClient.put(clientChannel.id().asLongText(), streamId);

        clientChannel.closeFuture().addListener(ignored -> closeFromClient(streamId, "client_closed"));
        clientChannel.eventLoop().schedule(() -> {
            StreamState current = streams.get(streamId);
            if (current != null && !current.connected && current.connectFuture.complete(
                    new ConnectResult(streamId, Socks5CommandStatus.FAILURE, "connect timeout"))) {
                remove(streamId);
                sendClose(agentChannel, streamId, "connect_timeout");
            }
        }, CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

        ChannelFuture sendFuture = sendJson(agentChannel, Map.of("type", "socks_connect",
                "streamId", streamId,
                "host", host,
                "port", port,
                "addrType", addrType));
        sendFuture.addListener(done -> {
            if (!done.isSuccess()) {
                remove(streamId);
                future.complete(new ConnectResult(streamId, Socks5CommandStatus.FAILURE,
                        done.cause() != null ? done.cause().getMessage() : "send failed"));
            }
        });
        return future;
    }

    public void forwardClientData(String streamId, ByteBuf buf) {
        StreamState state = streams.get(streamId);
        if (state == null || !state.connected || !state.agentChannel.isActive()) {
            return;
        }
        while (buf.isReadable()) {
            int len = Math.min(buf.readableBytes(), CHUNK_SIZE);
            byte[] chunk = new byte[len];
            buf.readBytes(chunk);
            sendJson(state.agentChannel, Map.of(
                    "type", "socks_data",
                    "streamId", streamId,
                    "data", Base64.getEncoder().encodeToString(chunk)
            ));
        }
    }

    public boolean handleAgentMessage(Channel agentChannel, Map<String, Object> msg) {
        String type = (String) msg.get("type");
        String streamId = (String) msg.get("streamId");
        if (type == null || streamId == null) {
            return false;
        }
        StreamState state = streams.get(streamId);
        if (state == null) {
            return type.startsWith("socks_");
        }

        switch (type) {
            case "socks_connected" -> {
                state.connected = true;
                state.connectFuture.complete(new ConnectResult(streamId, Socks5CommandStatus.SUCCESS, "connected"));
                return true;
            }
            case "socks_error" -> {
                Socks5CommandStatus status = statusFromReplyCode(msg.get("replyCode"));
                String message = String.valueOf(msg.getOrDefault("msg", "connect failed"));
                boolean wasConnected = state.connected;
                state.connectFuture.complete(new ConnectResult(streamId, status, message));
                remove(streamId);
                if (wasConnected) {
                    closeClient(state);
                }
                return true;
            }
            case "socks_data" -> {
                writeAgentData(state, msg.get("data"));
                return true;
            }
            case "socks_closed" -> {
                closeClient(state);
                remove(streamId);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    public void closeFromClient(String streamId, String reason) {
        StreamState state = remove(streamId);
        if (state != null && state.agentChannel.isActive()) {
            sendClose(state.agentChannel, streamId, reason);
        }
    }

    public List<ClientInfo> listClients() {
        List<ClientInfo> clients = new ArrayList<>();
        streams.values().forEach(state -> clients.add(state.toClientInfo()));
        return clients;
    }

    public boolean disconnectClient(String streamId, String reason) {
        StreamState state = remove(streamId);
        if (state == null) {
            return false;
        }
        if (state.agentChannel.isActive()) {
            sendClose(state.agentChannel, streamId, reason == null || reason.isBlank() ? "manual_disconnect" : reason);
        }
        closeClient(state);
        return true;
    }

    public void closeClientStreams(Channel clientChannel) {
        String streamId = streamByClient.get(clientChannel.id().asLongText());
        if (streamId != null) {
            closeFromClient(streamId, "client_closed");
        }
    }

    public void closeAgentStreams(Channel agentChannel) {
        streams.values().stream()
                .filter(state -> state.agentChannel.equals(agentChannel))
                .map(state -> state.streamId)
                .toList()
                .forEach(streamId -> {
                    StreamState state = remove(streamId);
                    if (state != null) {
                        state.connectFuture.complete(new ConnectResult(streamId, Socks5CommandStatus.HOST_UNREACHABLE, "agent disconnected"));
                        closeClient(state);
                    }
                });
    }

    private void writeAgentData(StreamState state, Object dataObj) {
        if (!(dataObj instanceof String data) || !state.clientChannel.isActive()) {
            return;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(data);
            state.clientChannel.writeAndFlush(Unpooled.wrappedBuffer(raw));
        }
 catch (IllegalArgumentException e) {
            log.warn("SOCKS5 agent 数据解码失败: streamId={} {}", state.streamId, e.getMessage());
            closeFromClient(state.streamId, "decode_failed");
            closeClient(state);
        }
    }

    private void closeClient(StreamState state) {
        if (state.clientChannel.isActive()) {
            state.clientChannel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(f -> state.clientChannel.close());
        }
    }

    private StreamState remove(String streamId) {
        StreamState state = streams.remove(streamId);
        if (state != null) {
            streamByClient.remove(state.clientChannel.id().asLongText());
        }
        return state;
    }

    private void sendClose(Channel agentChannel, String streamId, String reason) {
        sendJson(agentChannel, Map.of("type", "socks_close",
                "streamId", streamId,
                "reason", reason));
    }

    private ChannelFuture sendJson(Channel channel, Map<String, Object> msg) {
        if (channel == null || !channel.isActive()) {
            return channel != null ? channel.newFailedFuture(new IllegalStateException("channel inactive")) : null;
        }
        try {
            String json = mapper.writeValueAsString(msg) + "\n";
            return channel.writeAndFlush(Unpooled.copiedBuffer(json, CharsetUtil.UTF_8));
        }
 catch (Exception e) {
            return channel.newFailedFuture(e);
        }
    }

    private Socks5CommandStatus statusFromReplyCode(Object codeObj) {
        int code = codeObj instanceof Number ? ((Number) codeObj).intValue() : 1;
        return switch (code) {
            case 2 -> Socks5CommandStatus.FORBIDDEN;
            case 3 -> Socks5CommandStatus.NETWORK_UNREACHABLE;
            case 4 -> Socks5CommandStatus.HOST_UNREACHABLE;
            case 5 -> Socks5CommandStatus.CONNECTION_REFUSED;
            case 6 -> Socks5CommandStatus.TTL_EXPIRED;
            case 7 -> Socks5CommandStatus.COMMAND_UNSUPPORTED;
            case 8 -> Socks5CommandStatus.ADDRESS_UNSUPPORTED;
            default -> Socks5CommandStatus.FAILURE;
        };
    }

    public record ConnectResult(String streamId, Socks5CommandStatus status, String message) {
        public boolean success() {
            return Socks5CommandStatus.SUCCESS.equals(status);
        }
    }

    public record ClientInfo(String streamId, String targetId, String agentId, String verifyCode,
                             String agentIpAddress, String agentRemoteAddress, String clientAddress,
                             String targetHost, int targetPort, Instant createdAt, boolean connected,
                             boolean tunnelEnabled) {
    }

    private static final class StreamState {
        private final String streamId;
        private final Channel clientChannel;
        private final Channel agentChannel;
        private final CompletableFuture<ConnectResult> connectFuture;
        private final String agentId;
        /**
         * 验证码
         */
        private final String verifyCode;
        private final String agentIpAddress;
        private final String agentRemoteAddress;
        private final String clientAddress;
        private final String targetHost;
        private final int targetPort;
        private final Instant createdAt = Instant.now();
        private volatile boolean connected;
        private volatile boolean tunnelEnabled;

        private StreamState(String streamId, Channel clientChannel, Channel agentChannel,
                            CompletableFuture<ConnectResult> connectFuture, String agentId,
                            String verifyCode, String agentIpAddress, String agentRemoteAddress,
                            String clientAddress, String targetHost, int targetPort) {
            this.streamId = streamId;
            this.clientChannel = clientChannel;
            this.agentChannel = agentChannel;
            this.connectFuture = connectFuture;
            this.agentId = agentId;
            this.verifyCode = verifyCode;
            this.agentIpAddress = agentIpAddress;
            this.agentRemoteAddress = agentRemoteAddress;
            this.clientAddress = clientAddress;
            this.targetHost = targetHost;
            this.targetPort = targetPort;
        }

        private ClientInfo toClientInfo() {
            return new ClientInfo(streamId, agentId, agentId, verifyCode, agentIpAddress, agentRemoteAddress,
                    clientAddress, targetHost, targetPort, createdAt, connected, tunnelEnabled);
        }
    }
}
