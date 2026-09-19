package com.chua.common.support.network.rpc;


/**
 * RPC 协议配置（Java 14+ Record）。
 *
 * <p>字段集与各协议实现实际消费项对齐：Dubbo 使用全部字段，SOFA 使用子集，
 * native/json 仅使用 {@code host}/{@code port}/{@code threads}。</p>
 *
 * @param name            协议名称（dubbo、http、sofa 等）
 * @param host            监听地址，{@code null} 或 {@code "0.0.0.0"} 表示监听所有网卡
 * @param port            监听端口
 * @param payload         最大载荷（字节），默认 8MB
 * @param buffer          网络缓冲区大小（字节）
 * @param threads         业务线程池核心线程数
 * @param accepts         最大接入连接数
 * @param ioThreads       IO 事件处理线程数
 * @param alive           空闲连接保活超时（毫秒）
 * @param queues          业务线程池等待队列大小
 * @param serialization   序列化协议名称（如 hessian2, java, kryo, protobuf）
 * @param codec           编解码协议
 * @param transporter     传输层实现（如 netty, mina）
 * @param dispatcher      消息分发策略（如 all, direct, message, execution, connection）
 * @param threadpool      线程池类型（如 fixed, cached, limited, eager）
 * @param heartbeat       心跳间隔（毫秒）
 * @param ssl             是否启用 SSL/TLS
 * @param register        是否向注册中心注册此协议
 * @param charset         字符集（如 UTF-8）
 * @param keepAlive       连接保活
 * @param coreThreads     核心业务线程数
 * @param maxThreads      最大业务线程数
 * @param idleTimeout     空闲超时（毫秒）
 *
 * @author CH
 * @since 1.0.0
 */
public record RpcProtocolConfig(String name, String host, Integer port, Integer payload,
                                Integer buffer, Integer threads, Integer accepts,
                                Integer ioThreads, Integer alive, Integer queues,
                                String serialization, String codec, String transporter,
                                String dispatcher, String threadpool, Integer heartbeat,
                                Boolean ssl, Boolean register, String charset,
                                Boolean keepAlive, Integer coreThreads, Integer maxThreads,
                                Integer idleTimeout) {

    /**
     * 创建一份按当前系统自动调优的协议配置。
     *
     * <p>基于 CPU 核数与 JVM 可用堆内存自动调整业务线程池、IO 线程、等待队列、
     * 缓冲区与最大连接数等参数，使服务端在各环境中都能获得较优的默认表现。</p>
     *
     * @param name 协议名称
     * @param port 监听端口
     * @return 自动调优的协议配置
     */
    public static RpcProtocolConfig auto(String name, int port) {
        int cpus = Runtime.getRuntime().availableProcessors();
        long heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);

        // 业务线程池：核心 = CPU×2，最大 = CPU×4（堆充足时放大）
        int coreThreads = Math.max(cpus * 2, 4);
        int maxThreads = heapMb >= 4096 ? cpus * 8 : cpus * 4;
        int ioThreads = Math.max(cpus, 2);
        int queues = heapMb >= 4096 ? 512 : 256;
        int buffer = heapMb >= 4096 ? 16384 : 8192;
        int accepts = heapMb >= 4096 ? 100000 : 10000;

        return new RpcProtocolConfig(name, "0.0.0.0", port, 8 * 1024 * 1024, buffer,
                maxThreads, accepts, ioThreads, 30000, queues,
                null, null, null, null, "cached", 30000,
                false, true, "UTF-8", true, coreThreads, maxThreads, 30000);
    }
}
