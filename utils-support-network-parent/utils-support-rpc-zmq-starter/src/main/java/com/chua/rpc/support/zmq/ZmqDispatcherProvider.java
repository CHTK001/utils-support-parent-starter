package com.chua.rpc.support.zmq;

import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.DispatcherDefinition;
import com.chua.common.support.concurrent.dispatcher.DispatcherProvider;
import com.chua.common.support.concurrent.dispatcher.provider.AbstractDispatcherProvider;
import com.chua.common.support.concurrent.dispatcher.provider.JacksonSerialization;
import com.chua.common.support.base.serialize.Serialization;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;

/**
* zeromq 消息分发器提供者（jeromq，纯 Java 无需原生依赖）。
*
* <p><b>模型</b>：基于 <strong>PUB/SUB</strong> 发布订阅模型，单实例同时承担
* 发布者与订阅者两种角色：</p>
* <ul>
*   <li><b>发布</b>：内部维护一个 PUB Socket，绑定到配置地址，消息以
*       {@code [topic][separator][serializedBody]} 多帧报文发送</li>
*   <li><b>订阅</b>：内部维护一个 SUB Socket，连接到同一地址，并按主题
*       {@code sub.subscribe(topic)} 过滤；后台线程循环收包并回调
*       {@link DispatcherDefinition#dispatch(Object)}</li>
* </ul>
*
* <p><b>报文格式</b>（PUB/SUB）：
* <pre>
*   第一帧: 主题名（UTF-8 字节）
*   第二帧: 序列化后的消息体（JSON/SPI 序列化）
* </pre>
* SUB Socket按主题过滤由 zeromq 内核完成，同一主题只投递匹配的报文。</p>
*
* <p><b>序列化</b>：优先使用 {@link DispatcherConfig#getSerializer()} 指定的
* SPI 序列化器（如 {@code fury}/{@code fory}/{@code jackson}），未配置时回退
* {@link JacksonSerialization}。</p>
*
* <p><b>线程模型</b>：PUB Socket线程安全（内部队列），可在任意线程调用
* {@link #publish(String, Object)}；SUB Socket由单一后台线程独占收发。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi("zmq")
public class ZmqDispatcherProvider extends AbstractDispatcherProvider {

    /**
    * 未配置连接地址时的默认端点
    */
    private static final String DEFAULT_ADDRESS = "tcp://127.0.0.1:5556";

    /**
    * 单次 {@code recv} 阻塞等待的最长时间（毫秒），用于终止循环检测
    */
    private static final int RECV_TIMEOUT = 100;

    /**
    * ZMQ 上下文（线程安全，复用）
    */
    private final ZContext zContext;

    /**
    * PUB 发布Socket
    */
    private final ZMQ.Socket pubSocket;

    /**
    * SUB 订阅Socket
    */
    private final ZMQ.Socket subSocket;

    /**
    * 订阅端点地址
    */
    private final String address;

    /**
    * 消息体序列化器
    */
    private final Serialization serializer;

    /**
    * 主题 → 订阅定义列表映射
    */
    private final Map<String, List<DispatcherDefinition>> definitionMap = new ConcurrentHashMap<>();

    /**
    * 收包线程
    */
    private Thread recvThread;

    /**
    * 关闭标志（收包线程退出条件）
    */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
    * 创建 zmqdispatcher提供者 实例。
    *
    * @param config 分发器配置（url 指定 PUB/SUB 端点地址，序列化器 指定序列化器）
    */
    public ZmqDispatcherProvider(DispatcherConfig config) {
        super(config);
        this.address = config != null && config.getUrl() != null && !config.getUrl().isBlank()
                ? config.getUrl() : DEFAULT_ADDRESS;
        this.serializer = resolveSerializer(config != null ? config.getSerializer() : null);
        this.zContext = new ZContext();
        // 先绑 PUB 再连 SUB，避免慢连接（slow joiner）导致早期消息丢失
        this.pubSocket = zContext.createSocket(SocketType.PUB);
        pubSocket.setLinger(0);
        pubSocket.bind(address);
        this.subSocket = zContext.createSocket(SocketType.SUB);
        subSocket.setLinger(0);
        subSocket.setReceiveTimeOut(RECV_TIMEOUT);
        subSocket.connect(address);
        log.info("ZmqDispatcherProvider initialized: {} (serialization={})", address, serializer.name());
    }

    /**
    * 按 SPI 名称解析消息体序列化器。
    *
    * @param name 序列化器 SPI 名称（如 {@code fury}/{@code fory}/{@code jackson}），为空时使用 Jackson
    * @return 序列化器实例，加载失败时回退 Jackson
    */
    private static Serialization resolveSerializer(String name) {
        if (name == null || name.isBlank()) {
            return JacksonSerialization.INSTANCE;
        }
        try {
            Serialization serialization = ServiceProvider.of(Serialization.class).getNewExtension(name);
            if (serialization != null) {
                log.info("ZmqDispatcherProvider 序列化器已加载: {} -> {}", name, serialization.getClass().getName());
                return serialization;
            }
        } catch (Exception e) {
            log.warn("ZmqDispatcherProvider 序列化器 SPI 加载失败: {}，回退 Jackson", name, e);
        }
        return JacksonSerialization.INSTANCE;
    }

    @Override
    /** 启动 */
    public void start() {
        if (closed.get()) {
            return;
        }
        recvThread = Thread.ofPlatform().name("zmq-dispatcher-recv").daemon(true).start(this::recvLoop);
        log.info("ZmqDispatcherProvider started, recv thread active");
    }

    /**
    * 收包主循环：阻塞接收 SUB 报文，先读主题帧，再读消息帧，按主题分派给订阅者。
    */
    private void recvLoop() {
        while (!closed.get()) {
            try {
                byte[] topic = subSocket.recv(0);
                if (topic == null) {
                    continue;
                }
                // 报文体为第二帧；多余帧直接丢弃，保持兼容
                byte[] body = subSocket.hasReceiveMore() ? subSocket.recv(0) : null;
                while (subSocket.hasReceiveMore()) {
                    subSocket.recv(0);
                }
                if (body == null) {
                    continue;
                }
                dispatch(topic, body);
            } catch (Exception e) {
                if (!closed.get()) {
                    log.warn("ZMQ dispatcher recv error: {}", e.toString());
                }
            }
        }
    }

    /**
    * 将主题 + 消息体分派给所有匹配的订阅定义。
    *
    * @param topicBytes 主题字节
    * @param bodyBytes  序列化后的消息体字节
    */
    private void dispatch(byte[] topicBytes, byte[] bodyBytes) {
        String topic = new String(topicBytes, StandardCharsets.UTF_8);
        List<DispatcherDefinition> definitions = definitionMap.get(topic);
        if (definitions == null || definitions.isEmpty()) {
            return;
        }
        Object body = readBody(bodyBytes);
        if (body == null) {
            return;
        }
        for (DispatcherDefinition definition : definitions) {
            try {
                definition.dispatch(body);
            } catch (Exception e) {
                log.warn("ZMQ dispatcher dispatch error, topic={}", topic, e);
            }
        }
    }

    @Override
    /** 发布 */
    public void publish(String topic, Object body) {
        if (closed.get() || topic == null) {
            return;
        }
        byte[] bodyBytes = writeBody(body);
        if (bodyBytes == null) {
            return;
        }
        pubSocket.sendMore(topic.getBytes(StandardCharsets.UTF_8));
        pubSocket.send(bodyBytes, 0);
    }

    @Override
    /** 订阅 */
    public void subscribe(DispatcherDefinition definition) {
        for (String topic : definition.getTopics()) {
            if (topic == null) {
                continue;
            }
            definitionMap.computeIfAbsent(topic, t -> {
 // zeromq SUB 按主题前缀过滤：直接订阅精确主题
                subSocket.subscribe(t.getBytes(StandardCharsets.UTF_8));
                return new CopyOnWriteArrayList<>();
            }).add(definition);
        }
    }

    @Override
    /** 注销订阅 */
    public void unsubscribe(DispatcherDefinition definition) {
        for (String topic : definition.getTopics()) {
            if (topic == null) {
                continue;
            }
            List<DispatcherDefinition> definitions = definitionMap.get(topic);
            if (definitions != null) {
                definitions.remove(definition);
                if (definitions.isEmpty()) {
                    definitionMap.remove(topic);
                    subSocket.unsubscribe(topic.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    }

    /**
    * 序列化消息体。
    *
    * @param body 消息体
    * @return 字节数组，序列化失败时返回 {@code null}
    */
    private byte[] writeBody(Object body) {
        try {
            if (serializer == null) {
                return JacksonSerialization.INSTANCE.serialize(body);
            }
            synchronized (serializer) {
                return serializer.serialize(body);
            }
        } catch (Exception e) {
            log.warn("ZMQ dispatcher 序列化失败", e);
            return null;
        }
    }

    /**
    * 反序列化消息体。
    *
    * @param data 字节数组
    * @return 消息对象，反序列化失败时返回 {@code null}
    */
    private Object readBody(byte[] data) {
        try {
            if (serializer == null) {
                return JacksonSerialization.INSTANCE.deserialize(data, Object.class);
            }
            synchronized (serializer) {
                return serializer.deserialize(data, Object.class);
            }
        } catch (Exception e) {
            log.warn("ZMQ dispatcher 反序列化失败，回退 Jackson", e);
            try {
                return JacksonSerialization.INSTANCE.deserialize(data, Object.class);
            } catch (Exception ex) {
                log.warn("ZMQ dispatcher Jackson 反序列化失败", ex);
                return null;
            }
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (recvThread != null) {
            recvThread.interrupt();
        }
        try {
            pubSocket.close();
        } catch (Exception ignored) {
            // 关闭时忽略Socket异常
        }
        try {
            subSocket.close();
        } catch (Exception ignored) {
            // 关闭时忽略Socket异常
        }
        zContext.close();
        definitionMap.clear();
        log.info("ZmqDispatcherProvider closed");
    }
}
