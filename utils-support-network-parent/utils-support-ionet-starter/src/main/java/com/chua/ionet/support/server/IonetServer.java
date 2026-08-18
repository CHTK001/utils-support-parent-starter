package com.chua.ionet.support.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.filter.DefaultServerFilterChain;
import com.chua.common.support.network.server.request.AbstractServerRequest;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.AbstractServerResponse;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.annotations.Spi;
import com.iohao.net.app.RunOne;
import com.iohao.net.external.core.ExternalServer;
import com.iohao.net.external.core.config.ExternalGlobalConfig;
import com.iohao.net.external.core.config.ExternalJoinEnum;
import com.iohao.net.external.core.netty.ExternalMapper;
import com.iohao.net.framework.core.BarSkeletonBuilder;
import com.iohao.net.framework.core.flow.ActionMethodInOut;
import com.iohao.net.framework.core.flow.FlowContext;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * ionet 服务器封装 — 接入 common Server 体系（AbstractServer），支持统一生命周期与 ServerFilter。
 * <p>
 * 一站式启动对外服 + 逻辑服 + 中心服，使用 Builder 模式构建：
 * <pre>
 * IonetServer server = IonetServer.builder()
 *     .port(10100)
 *     .scanActionPackage(MyAction.class)
 *     .build();
 * server.start();
 * </pre>
 *
 * @author CH
 */
@Slf4j
@Spi("ionet")
public class IonetServer extends AbstractServer {

    /** 连接方式：TCP / WEBSOCKET / UDP */
    private final ExternalJoinEnum joinType;
    /** 逻辑服名称 */
    private final String logicServerName;
    /** Action 类所在包的扫描根类 */
    private final Class<?> scanActionClass;
    /** 是否启用中心服 */
    private final boolean enableCenterServer;
    /** 是否开启调试插件 */
    private final boolean debugMode;
    /** 额外的 BarSkeletonBuilder 配置 */
    private final Consumer<BarSkeletonBuilder> skeletonConfigurer;
    /** 额外的 RunOne 配置 */
    private final Consumer<RunOne> runOneConfigurer;
    /** RunOne 实例 */
    private RunOne runOne;

    private IonetServer(Builder builder) {
        // 由 joinType 映射底层协议，构造期即可用（AbstractServer 构造会调用 getProtocolType()）
        super(ServerSetting.builder()
                .host("0.0.0.0")
                .port(builder.port)
                .protocol(protocolName(builder.joinType))
                .build());
        this.joinType = builder.joinType;
        this.logicServerName = builder.logicServerName;
        this.scanActionClass = builder.scanActionClass;
        this.enableCenterServer = builder.enableCenterServer;
        this.debugMode = builder.debugMode;
        this.skeletonConfigurer = builder.skeletonConfigurer;
        this.runOneConfigurer = builder.runOneConfigurer;
    }

    /**
     * SPI 构造（供 {@code ServerBuilder.type("ionet")} 反射创建）：
     * joinType 由 setting.protocol 反向映射，Action 扫描类需通过 Builder 场景提供。
     *
     * @param setting 服务器配置
     */
    public IonetServer(ServerSetting setting) {
        super(setting != null ? setting : ServerSetting.defaults());
        this.joinType = parseJoinType(getProtocolType());
        this.logicServerName = "IonetLogicServer";
        this.scanActionClass = null;
        this.enableCenterServer = true;
        this.debugMode = true;
        this.skeletonConfigurer = null;
        this.runOneConfigurer = null;
    }

    /**
     * 由协议类型反向推导连接方式。
     *
     * @param protocol 协议类型
     * @return 连接方式
     */
    private static ExternalJoinEnum parseJoinType(ProtocolType protocol) {
        return switch (protocol) {
            case UDP -> ExternalJoinEnum.UDP;
            case WS -> ExternalJoinEnum.WEBSOCKET;
            default -> ExternalJoinEnum.TCP;
        };
    }

    /**
     * 协议字符串：tcp / websocket / udp（EXT_SOCKET 回落 tcp）。
     *
     * @param joinType 连接方式
     * @return 协议字符串
     */
    private static String protocolName(ExternalJoinEnum joinType) {
        return switch (joinType) {
            case UDP -> "udp";
            case WEBSOCKET -> "ws";
            default -> "tcp";
        };
    }

    @Override
    protected void doStart() {
        Locale.setDefault(Locale.CHINA);

        // 创建对外服
        ExternalServer externalServer = ExternalMapper.builder(setting.getPort()).build();

        // 创建逻辑服：注入 ServerFilter 链 InOut，消息入口走统一过滤器链（参照 KcpServer）
        Consumer<BarSkeletonBuilder> filterAwareConfigurer = builder -> {
            builder.addInOut(new IonetFilterInOut());
            if (skeletonConfigurer != null) {
                skeletonConfigurer.accept(builder);
            }
        };
        var logicServer = new IonetLogicServer(logicServerName, scanActionClass, debugMode, filterAwareConfigurer);

        // 获取 Aeron 实例
        var aeron = IonetAeron.getAeronInstance();

        // 构建 RunOne
        runOne = new RunOne()
                .setAeron(aeron)
                .setExternalServer(externalServer)
                .setLogicServerList(List.of(logicServer));

        if (enableCenterServer) {
            runOne.enableCenterServer();
        }

        if (runOneConfigurer != null) {
            runOneConfigurer.accept(runOne);
        }

        // 启动
        runOne.startup();

        log.info("[IonetServer] Started on port={} joinType={} logicServer={}", setting.getPort(), joinType, logicServerName);
    }

    @Override
    protected void doStop() {
        if (runOne != null) {
            // RunOne 未暴露 stop API，通过关闭钩子释放；此处仅置空引用
            runOne = null;
        }
        log.info("[IonetServer] Stopped on port={} joinType={}", setting.getPort(), joinType);
    }

    @Override
    public String getProtocol() {
        return getProtocolType().name().toLowerCase();
    }

    @Override
    public ProtocolType getProtocolType() {
        // 基于构造传入的 setting（AbstractServer 构造期调用本方法时 setting 已赋值）
        return switch (setting.getProtocol()) {
            case "udp" -> ProtocolType.UDP;
            case "ws", "websocket" -> ProtocolType.WS;
            default -> ProtocolType.TCP;
        };
    }

    /**
     * 兼容旧入口：等价于 {@link #start()}。
     */
    public void startup() {
        start();
    }

    /**
     * 获取连接方式。
     *
     * @return 连接方式
     */
    public ExternalJoinEnum getJoinType() {
        return joinType;
    }

    /**
     * ionet 消息入口 InOut：在 Action 执行前构造协议无关 request/response，走统一 ServerFilter 链。
     */
    private final class IonetFilterInOut implements ActionMethodInOut {

        @Override
        public void fuckIn(FlowContext flowContext) {
            long userId = flowContext.getUserId();
            int cmdMerge = flowContext.getCmdMerge();
            String path = "/" + cmdMerge;
            String payload = flowContext.getRequest() != null
                    ? String.valueOf(flowContext.getRequest().getCmdMerge()) : String.valueOf(cmdMerge);

            IonetServerRequest request = new IonetServerRequest(String.valueOf(userId), path, payload);
            IonetServerResponse response = new IonetServerResponse();
            DefaultServerFilterChain chain = new DefaultServerFilterChain(
                    filterManager.getMergedFilters(), (req, res) -> {
                // 链尾空实现：ionet Action 由 iohao 框架继续执行，filter 仅做横切（日志/限流/鉴权）
            });
            try {
                chain.doFilter(request, response);
            } catch (Exception e) {
                log.warn("ionet 过滤器链执行异常: {}", e.getMessage());
            }
        }

        @Override
        public void fuckOut(FlowContext flowContext) {
            // 响应阶段无额外处理
        }
    }

    /**
     * ionet 消息的协议无关请求视图（cmdMerge → path，userId → remote）。
     */
    private static final class IonetServerRequest extends AbstractServerRequest {

        /**
         * 客户端远端地址
         */
        private final String remoteAddress;
        /**
         * 请求路径
         */
        private final String path;
        /**
         * 请求载荷字节数组
         */
        private final byte[] payload;

        IonetServerRequest(String remoteAddress, String path, String payload) {
            this.remoteAddress = remoteAddress;
            this.path = path;
            this.payload = payload.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public HttpHeader getHeaders() {
            return HttpHeader.create();
        }

        @Override
        public String getHeader(String name) {
            return null;
        }

        @Override
        public String getUri() {
            return path;
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public HttpMethod getMethod() {
            return HttpMethod.POST;
        }

        @Override
        public String getRemoteAddress() {
            return remoteAddress;
        }

        @Override
        public int getRemotePort() {
            return 0;
        }

        @Override
        protected byte[] readBody() {
            return payload;
        }
    }

    /**
     * ionet 消息的协议无关响应视图（ionet 无 HTTP 响应体，仅承载 filter 链语义）。
     */
    private static final class IonetServerResponse extends AbstractServerResponse {

        @Override
        public java.io.OutputStream getOutputStream() {
            return new java.io.ByteArrayOutputStream();
        }

        @Override
        public void writeRaw(byte[] bytes) {
            // ionet 响应由 Action 返回值承载，忽略原始写回
        }
    }

    // ========== Builder ==========

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        /**
         * 服务器端口号，默认取外部全局端口
         */
        private int port = ExternalGlobalConfig.externalPort;
        /**
         * 连接方式，默认 TCP
         */
        private ExternalJoinEnum joinType = ExternalJoinEnum.TCP;
        /**
         * 逻辑服名称，默认 IonetLogicServer
         */
        private String logicServerName = "IonetLogicServer";
        /**
         * Action 类所在包的扫描根类
         */
        private Class<?> scanActionClass;
        /**
         * 是否启用中心服，默认 true
         */
        private boolean enableCenterServer = true;
        /**
         * 是否开启调试插件，默认 true
         */
        private boolean debugMode = true;
        /**
         * 额外的 BarSkeletonBuilder 配置器
         */
        private Consumer<BarSkeletonBuilder> skeletonConfigurer;
        /**
         * 额外的 RunOne 配置器
         */
        private Consumer<RunOne> runOneConfigurer;

        public Builder port(int port) { this.port = port; return this; }
        public Builder joinType(ExternalJoinEnum joinType) { this.joinType = joinType; return this; }
        public Builder logicServerName(String name) { this.logicServerName = name; return this; }
        public Builder scanActionPackage(Class<?> scanClass) { this.scanActionClass = scanClass; return this; }
        public Builder enableCenterServer(boolean enable) { this.enableCenterServer = enable; return this; }
        public Builder debugMode(boolean debug) { this.debugMode = debug; return this; }
        public Builder skeletonConfigurer(Consumer<BarSkeletonBuilder> configurer) { this.skeletonConfigurer = configurer; return this; }
        public Builder runOneConfigurer(Consumer<RunOne> configurer) { this.runOneConfigurer = configurer; return this; }

        public IonetServer build() {
            if (scanActionClass == null) {
                throw new IllegalArgumentException("scanActionPackage is required: call .scanActionPackage(YourAction.class)");
            }
            return new IonetServer(this);
        }
    }
}
