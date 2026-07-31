package com.chua.remote.support.gateway.transport.socks5;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 分发 Agent 返回的 SOCKS5 隧道消息。

 * @author CH
 */@Slf4j
public class Socks5AgentFrameHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private final ReverseSocks5TunnelManager tunnelManager;
    private final ObjectMapper mapper = new ObjectMapper();

    public Socks5AgentFrameHandler(ReverseSocks5TunnelManager tunnelManager) {
        this.tunnelManager = tunnelManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) {
        String text = buf.toString(StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> msg = mapper.readValue(text, Map.class);
            String type = (String) msg.get("type");
            if (type != null && type.startsWith("socks_") && tunnelManager.handleAgentMessage(ctx.channel(), msg)) {
                return;
            }
        }
 catch (Exception e) {
            log.debug("SOCKS5 Agent 消息解析跳过: {}", e.getMessage());
        }
        ctx.fireChannelRead(buf.retain());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        tunnelManager.closeAgentStreams(ctx.channel());
        super.channelInactive(ctx);
    }
}
