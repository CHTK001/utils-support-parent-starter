package com.chua.common.support.network.rpc;

import com.chua.common.support.base.serialize.Serialization;
import com.chua.common.support.spi.ServiceProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * RPC 请求/响应编解码适配器。
 *
 * <p>序列化实现通过 SPI 加载（{@link Serialization}），默认优先 Apache Fury
 * （SPI 名 {@code fury}/{@code fory}），classpath 无 Fury 时回退到 {@code jackson}，
 * 两者均不可用时降级为 JDK 原生序列化（保留 {@link ObjectInputFilter} 反序列化安全防护）。</p>
 *
 * <p>客户端与服务端必须选用同一套序列化实现，否则互相无法解码。可通过
 * {@link RpcConsumerConfig#getSerialization()} / {@link RpcProtocolConfig#serialization()}
 * 显式指定 SPI 名（如 {@code fury}、{@code jackson}），未指定时按上述优先级自动选择。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RpcSerialization {

    /**
     * 序列化器选择优先级（Fury 最高）
     */
    private static final String[] FALLBACK_NAMES = {"fury", "fory", "jackson"};

    /**
     * 缓存的序列化实现
     */
    private final Serialization serialization;

    /**
     * 序列化实现名称
     */
    private final String name;

    /**
     * 构造 RPC 编解码器，按优先级自动选择序列化实现。
     */
    public RpcSerialization() {
        this((String) null);
    }

    /**
     * 构造 RPC 编解码器。
     *
     * @param configuredName 显式指定的序列化 SPI 名，为空时按默认优先级选择
     */
    public RpcSerialization(String configuredName) {
        Serialization picked = null;
        if (configuredName != null && !configuredName.isBlank()) {
            picked = load(configuredName);
        }
        if (picked == null) {
            for (String candidate : FALLBACK_NAMES) {
                picked = load(candidate);
                if (picked != null) {
                    break;
                }
            }
        }
        if (picked == null) {
            throw new IllegalStateException("No serialization implementation available "
                    + "(expected SPI: fury/fory/jackson)");
        }
        this.serialization = picked;
        this.name = picked.name();
    }

    /**
     * 从 SPI 加载指定名称的序列化实现。
     *
     * @param spiName SPI 名称
     * @return 序列化实现，未找到时返回 {@code null}
     */
    private static Serialization load(String spiName) {
        try {
            return ServiceProvider.of(Serialization.class).getNewExtension(spiName);
        } catch (Exception e) {
            System.err.println("[RpcSerialization] load failed for '" + spiName + "': " + e);
            e.printStackTrace(System.err);
            return null;
        }
    }

    /**
     * 序列化 RPC 请求。
     *
     * @param request 请求对象
     * @return 序列化后的字节数组
     * @throws IOException 序列化异常
     */
    public byte[] serialize(RpcRequest request) throws IOException {
        return doSerialize(request);
    }

    /**
     * 序列化 RPC 响应。
     *
     * @param response 响应对象
     * @return 序列化后的字节数组
     * @throws IOException 序列化异常
     */
    public byte[] serialize(RpcResponse response) throws IOException {
        return doSerialize(response);
    }

    /**
     * 反序列化 RPC 请求。
     *
     * @param data 请求字节数组
     * @return 请求对象
     * @throws IOException            反序列化 IO 异常
     * @throws ClassNotFoundException 类型不存在异常
     */
    public RpcRequest deserializeRequest(byte[] data) throws IOException, ClassNotFoundException {
        return doDeserialize(data, RpcRequest.class);
    }

    /**
     * 反序列化 RPC 响应。
     *
     * @param data 响应字节数组
     * @return 响应对象
     * @throws IOException            反序列化 IO 异常
     * @throws ClassNotFoundException 类型不存在异常
     */
    public RpcResponse deserializeResponse(byte[] data) throws IOException, ClassNotFoundException {
        return doDeserialize(data, RpcResponse.class);
    }

    /**
     * 当前使用的序列化实现名称。
     *
     * @return 序列化 SPI 名称
     */
    public String name() {
        return name;
    }

    /**
     * 执行对象序列化。
     *
     * @param obj 待序列化对象
     * @return 字节数组
     * @throws IOException 序列化异常
     */
    private byte[] doSerialize(Object obj) throws IOException {
        try {
            return serialization.serialize(obj);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Serialize failed via SPI [" + name + "]", e);
        }
    }

    /**
     * 执行对象反序列化。
     *
     * @param data 字节数组
     * @param type 目标类型
     * @param <T>  目标类型泛型
     * @return 反序列化后的对象
     * @throws IOException            反序列化 IO 异常
     * @throws ClassNotFoundException 类型不存在异常
     */
    @SuppressWarnings("unchecked")
    private <T> T doDeserialize(byte[] data, Class<T> type) throws IOException, ClassNotFoundException {
        if (data == null || data.length == 0) {
            return null;
        }
        // Fury / Jackson 等外部实现序列化失败时统一包装，避免上层按 IO 异常误判
        try {
            return serialization.deserialize(data, type);
        } catch (IOException | ClassNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Deserialize failed via SPI [" + name + "]", e);
        }
    }

    /**
     * 创建 JDK 原生序列化回退实现（带反序列化安全过滤）。
     *
     * <p>SPI 中无 {@code fury}/{@code jackson} 实现时使用，语义与项目旧版
     * {@code ObjectOutputStream} 方案完全一致。</p>
     *
     * @return JDK 原生序列化实现
     */
    static Serialization jdkFallback() {
        return new JdkSerialization();
    }

    /**
     * JDK 原生序列化实现（带安全过滤）。
     *
     * @author CH
     * @since 4.0.0.42
     */
    private static final class JdkSerialization implements Serialization {

        @Override
        public String name() {
            return "java";
        }

        @Override
        public byte[] serialize(Object obj) throws Exception {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(512);
            try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
                oos.writeObject(obj);
            }
            return bos.toByteArray();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T deserialize(byte[] data, Class<T> type) throws Exception {
            try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
                ois.setObjectInputFilter(RpcSerialization.objectInputFilter());
                return (T) ois.readObject();
            }
        }
    }

    /**
     * 创建反序列化安全过滤器。
     *
     * <p>策略：默认放行普通业务类，但拒绝已知反序列化攻击 gadget 链上的高危类，
     * 同时限制对象图深度与数组长度，防止恶意报文 OOM。</p>
     *
     * @return 对象输入过滤器
     */
    static ObjectInputFilter objectInputFilter() {
        return info -> {
            Class<?> serialClass = info.serialClass();
            if (serialClass == null) {
                return ObjectInputFilter.Status.UNDECIDED;
            }
            if (info.depth() > 64) {
                return ObjectInputFilter.Status.REJECTED;
            }
            if (info.arrayLength() >= 0 && info.arrayLength() > 100_000) {
                return ObjectInputFilter.Status.REJECTED;
            }
            String className = serialClass.getName();
            for (String denied : DENIED_CLASS_PREFIXES) {
                if (className.startsWith(denied)) {
                    return ObjectInputFilter.Status.REJECTED;
                }
            }
            return ObjectInputFilter.Status.UNDECIDED;
        };
    }

    /**
     * 高危反序列化 gadget 类前缀黑名单
     */
    private static final String[] DENIED_CLASS_PREFIXES = {
            "com.sun.", "java.rmi.", "javax.naming.", "javax.management.",
            "org.apache.commons.collections.", "org.apache.commons.beanutils.",
            "org.apache.commons.fileupload.", "org.apache.xbean.",
            "org.springframework.beans.factory.", "org.springframework.context.",
            "org.codehaus.groovy.runtime.", "com.mchange.v2.c3p0.",
            "com.alibaba.fastjson.", "net.sf.json.", "org.jboss.",
            "org.python.core.", "org.mozilla.javascript.", "jdk.internal.",
            "sun.rmi.", "org.apache.dubbo.", "com.caucho."
    };
}