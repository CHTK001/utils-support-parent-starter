package com.chua.common.support.network.rpc;

import com.chua.common.support.base.serialize.Serialization;
import com.chua.common.support.reflection.ReflectUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Constructor;
import lombok.extern.slf4j.Slf4j;

/**
 * RPC 请求/响应编解码适配器。
 *
 * <p>序列化实现按优先级自动选择：</p>
 * <ol>
 *   <li><strong>Apache Fury</strong>：优先加载 {@code com.chua.fory.support.serialize.ForySerialization}，
 *       classpath 含 fory-starter 时启用（多语言二进制、JIT 零反射、性能最高）</li>
 *   <li><strong>Jackson</strong>：内置 jackson-databind 的 JSON 实现，无 fory-starter 时启用</li>
 *   <li><strong>JDK 原生</strong>：最后兜底，语义与旧版 {@code ObjectOutputStream} 方案一致</li>
 * </ol>
 *
 * <p>实现类按全限定类名反射加载，不依赖 SPI 配置注册，故 common-starter 无需
 * 反向依赖 fory-starter。客户端与服务端必须选用同一套序列化实现，否则互相无法解码。
 * 可通过 {@link RpcConsumerConfig#getSerialization()} / {@link RpcProtocolConfig#serialization()}
 * 显式指定名称（{@code fury} / {@code fory} / {@code jackson} / {@code java}），
 * 未指定时按上述优先级自动选择。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class RpcSerialization {

    /**
     * 日志
     */

    /**
     * 支持的序列化名称（映射到实现类全限定名）
     */
    private static final String[][] SUPPORTED = {
            {"fury", "com.chua.fory.support.serialize.ForySerialization"},
            {"fory", "com.chua.fory.support.serialize.ForySerialization"},
            {"jackson", "com.chua.common.support.concurrent.dispatcher.provider.JacksonSerialization"}
    };

    /**
     * 缓存解析出的序列化实现
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
        this(null);
    }

    /**
     * 构造 RPC 编解码器。
     *
     * @param configuredName 显式指定的序列化名称，为空时按默认优先级选择
     */
    public RpcSerialization(String configuredName) {
        Serialization picked = null;
        if (configuredName != null && !configuredName.isBlank()) {
            picked = loadByName(configuredName);
        }
        if (picked == null) {
            for (String[] supported : SUPPORTED) {
                picked = loadByName(supported[0]);
                if (picked != null) {
                    break;
                }
            }
        }
        if (picked == null) {
            picked = new JdkSerialization();
        }
        this.serialization = picked;
        this.name = picked.name();
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
     * @return 序列化名称
     */
    public String name() {
        return name;
    }

    /**
     * 按名称加载序列化实现。
     *
     * @param name 序列化名称
     * @return 序列化实现，加载失败时返回 {@code null}
     */
    private static Serialization loadByName(String name) {
        for (String[] supported : SUPPORTED) {
            if (!supported[0].equalsIgnoreCase(name)) {
                continue;
            }
            try {
                Class<?> implClass = ReflectUtils.forName(supported[1]);
                Constructor<?> constructor = implClass.getDeclaredConstructor();
                constructor.setAccessible(true);
                return (Serialization) constructor.newInstance();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
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
            log.error("Serialize failed via [{}]", name, e);
            throw new IOException("Serialize failed via [" + name + "]", e);
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
    private <T> T doDeserialize(byte[] data, Class<T> type) throws IOException, ClassNotFoundException {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            return serialization.deserialize(data, type);
        } catch (IOException | ClassNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Deserialize failed via [" + name + "]", e);
        }
    }

    /**
     * JDK 原生序列化实现（带反序列化安全过滤）。
     *
     * @since 4.0.0.42
     */
    private static final class JdkSerialization implements Serialization {

        @Override
        /** Name */
        public String name() {
            return "java";
        }

        @Override
        /** 序列化 */
        public byte[] serialize(Object obj) throws Exception {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(512);
            try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
                oos.writeObject(obj);
            }
            return bos.toByteArray();
        }

        @SuppressWarnings("unchecked")
        @Override
        /** 反序列化 */
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
