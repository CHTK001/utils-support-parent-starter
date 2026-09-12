package com.chua.zookeeper.support.client;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
* ZooKeeper 链式客户端，全功能封装 Curator。
*
* <p>采用 Builder 模式 + 链式 API，支持节点 CRUD、Watch、ACL 等操作。</p>
*
* <h2>使用方式</h2>
* <pre>{@code
* // 创建客户端
* ZookeeperClient client = ZookeeperClient.create("127.0.0.1:2181");
*
* // 节点操作
* client.create().path("/app/config").data("hello").forPath();
* client.getData().path("/app/config").forString();
* client.setData().path("/app/config").data("world").forPath();
* client.delete().path("/app/config").forPath();
*
* // 判断节点存在
* boolean exists = client.checkExists().path("/app/config").forBool();
*
* // 获取子节点
* List<String> children = client.getChildren().path("/app").forList();
*
* // Watch
* client.getData().path("/app/config").watch((type, event) -> {
*     System.out.println("节点变更: " + type);
* }).forPath();
*
* // 自动资源管理
* try (ZookeeperClient c = ZookeeperClient.create()) {
*     c.create().path("/test").data("value").forPath();
* }
* }</pre>// 自动资源管理
* 尝试 (zookeeper客户端 C = zookeeper客户端.创建()) {
* C.创建().路径("/测试").数据("值").for路径();
* }
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Getter
public class ZookeeperClient implements AutoCloseable {

    /**
    * Curator 客户端实例
     */
    private final CuratorFramework curator;
    /**
    * ZooKeeper 连接字符串
     */
    private final String connectString;

    /**
    * 创建 zookeeper客户端 实例
    * @param curator curator
    * @param connectString 字符串
    * @param connectString 连接字符串
     */
    private ZookeeperClient(CuratorFramework curator, String connectString) {
        this.curator = curator;
        this.connectString = connectString;
    }

    // ==================== 工厂方法 ====================

    /**
    * 创建
    *
    * @param connectString 连接字符串
    * @return 创建的结果
     */
    public static ZookeeperClient create(String connectString) {
        return builder().connectString(connectString).build();
    }

    /**
    * 创建
    *
    * @param connectString 连接字符串
    * @param sessionTimeoutMs 会话超时ms
    * @return 创建的结果
     */
    public static ZookeeperClient create(String connectString, int sessionTimeoutMs) {
        return builder().connectString(connectString).sessionTimeoutMs(sessionTimeoutMs).build();
    }

    /**
    * 构建器
    *
    * @return 构建器的结果
     */
    public static Builder builder() {
        return new Builder();
    }

    // ==================== 连接管理 ====================

    /**
    * 启动连接。
    * @return 启动的结果
     */
    public ZookeeperClient start() {
        curator.start();
        log.info("ZooKeeper 客户端启动: {}", connectString);
        return this;
    }

    /**
    * 等待连接就绪。
    * @return blockuntil连接的结果
     */
    public ZookeeperClient blockUntilConnected() throws InterruptedException {
        curator.blockUntilConnected();
        return this;
    }

    /**
    * 获取 Curator 客户端。
    * @return 获取curator的结果
     */
    public CuratorFramework getCurator() {
        return curator;
    }

    // ==================== 节点操作 ====================

    /**
    * 创建节点构建器。
    * @return 创建的结果
     */
    public CreateBuilder create() {
        return new CreateBuilder(curator);
    }

    /**
    * 删除节点构建器。
    * @return 删除的结果
     */
    public DeleteBuilder delete() {
        return new DeleteBuilder(curator);
    }

    /**
    * 获取数据构建器。
    * @return 获取数据的结果
     */
    public GetDataBuilder getData() {
        return new GetDataBuilder(curator);
    }

    /**
    * 设置数据构建器。
    * @return 设置数据的结果
     */
    public SetDataBuilder setData() {
        return new SetDataBuilder(curator);
    }

    /**
    * 判断节点是否存在构建器。
    * @return 检查exists的结果
     */
    public CheckExistsBuilder checkExists() {
        return new CheckExistsBuilder(curator);
    }

    /**
    * 获取子节点构建器。
    * @return 获取children的结果
     */
    public GetChildrenBuilder getChildren() {
        return new GetChildrenBuilder(curator);
    }

    @Override
    /** 关闭 */
    public void close() {
        if (curator != null) {
            curator.close();
        }
    }

    // ==================== Builder ====================
    /**
    * 构建器类。
    *
    * @author CH
    * @since 4.0.0
     */

    public static class Builder {
        /**
        * 连接字符串，默认 127.0.0.1:2181
         */
        private String connectString = "127.0.0.1:2181";
        /**
        * 会话超时时间（毫秒），默认 30000
         */
        private int sessionTimeoutMs = 30000;
        /**
        * 连接超时时间（毫秒），默认 15000
         */
        private int connectionTimeoutMs = 15000;
        /**
        * 重试基础休眠时间（毫秒），默认 1000
         */
        private int retryBaseSleepMs = 1000;
        /**
        * 最大重试次数，默认 3
         */
        private int retryMaxRetries = 3;
        /**
        * 命名空间
         */
        private String namespace;

        /**
        * 连接字符串
        *
        * @param connectString 连接字符串
        * @return 连接字符串的结果
         */
        public Builder connectString(String connectString) { this.connectString = connectString; return this; }
        /**
        * 会话超时ms
        *
        * @param ms ms
        * @return 会话超时ms的结果
         */
        public Builder sessionTimeoutMs(int ms) { this.sessionTimeoutMs = ms; return this; }
        /**
        * connection超时ms
        *
        * @param ms ms
        * @return connection超时ms的结果
         */
        public Builder connectionTimeoutMs(int ms) { this.connectionTimeoutMs = ms; return this; }
        /**
        * 重试basesleepms
        *
        * @param ms ms
        * @return 重试basesleepms的结果
         */
        public Builder retryBaseSleepMs(int ms) { this.retryBaseSleepMs = ms; return this; }
        /**
        * 重试最大值重试
        *
        * @param max 最大
        * @return 重试最大重试的结果
         */
        public Builder retryMaxRetries(int max) { this.retryMaxRetries = max; return this; }
        /**
        * Namespace
        *
        * @param namespace namespace
        * @return namespace的结果
         */
        public Builder namespace(String namespace) { this.namespace = namespace; return this; }

        /**
        * 构建
        *
        * @return 构建的结果
         */
        public ZookeeperClient build() {
            CuratorFrameworkFactory.Builder factoryBuilder = CuratorFrameworkFactory.builder()
                    .connectString(connectString)
                    .sessionTimeoutMs(sessionTimeoutMs)
                    .connectionTimeoutMs(connectionTimeoutMs)
                    .retryPolicy(new ExponentialBackoffRetry(retryBaseSleepMs, retryMaxRetries));

            if (namespace != null && !namespace.isEmpty()) {
                factoryBuilder.namespace(namespace);
            }

            CuratorFramework curator = factoryBuilder.build();
            return new ZookeeperClient(curator, connectString);
        }
    }

    // ==================== 操作构建器 ====================

    /**
    * 创建节点构建器。
    *
    * <p>支持三种节点模式：</p>
    * <ul>
    *   <li><b>持久节点</b> — 默认，客户端断开后节点保留</li>
    *   <li><b>临时节点</b> — 客户端断开后自动删除</li>
    *   <li><b>临时顺序节点</b> — 自动追加递增序号</li>
    * </ul>
    * @author CH
    * @since 4.0.0
     */
    public static class CreateBuilder {
        /**
        * Curator 客户端实例
         */
        private final CuratorFramework curator;
        /**
        * 节点路径
         */
        private String path;
        /**
        * 节点数据
         */
        private byte[] data;
        /**
        * 是否自动创建父节点，默认 true
         */
        private boolean creatingParentsIfNeeded = true;
        /**
        * 节点创建模式，默认持久节点
         */
        private org.apache.zookeeper.CreateMode mode = org.apache.zookeeper.CreateMode.PERSISTENT;
        /**
        * 访问控制列表 权限映射
         */
        private Map<String, byte[]> acl;

        CreateBuilder(CuratorFramework curator) { this.curator = curator; }

        /**
        * 路径
        *
        * @param path 路径
        * @return 路径的结果
         */
        public CreateBuilder path(String path) { this.path = path; return this; }
        /**
        * 数据
        *
        * @param data 数据
        * @return 数据的结果
         */
        public CreateBuilder data(String data) { this.data = data != null ? data.getBytes() : null; return this; }
        /**
        * 数据
        *
        * @param data 数据
        * @return 数据的结果
         */
        public CreateBuilder data(byte[] data) { this.data = data; return this; }
        /**
        * 创建父ifneeded
        *
        * @param flag flag
        * @return 创建父ifneeded的结果
         */
        public CreateBuilder creatingParentsIfNeeded(boolean flag) { this.creatingParentsIfNeeded = flag; return this; }
        /**
        * withacl
        *
        * @param acl 访问控制列表
        * @return withACL的结果
         */
        public CreateBuilder withACL(Map<String, byte[]> acl) { this.acl = acl; return this; }

        /**
        * 设置为持久节点（默认）。
        * @return persistent的结果
         */
        public CreateBuilder persistent() { this.mode = org.apache.zookeeper.CreateMode.PERSISTENT; return this; }

        /**
        * 设置为持久顺序节点。
        * @return persistentSequential的结果
         */
        public CreateBuilder persistentSequential() { this.mode = org.apache.zookeeper.CreateMode.PERSISTENT_SEQUENTIAL; return this; }

        /**
        * 设置为临时节点，客户端断开后自动删除。
        * @return ephemeral的结果
         */
        public CreateBuilder ephemeral() { this.mode = org.apache.zookeeper.CreateMode.EPHEMERAL; return this; }

        /**
        * 设置为临时顺序节点，自动追加递增序号。
        * @return ephemeralSequential的结果
         */
        public CreateBuilder ephemeralSequential() { this.mode = org.apache.zookeeper.CreateMode.EPHEMERAL_SEQUENTIAL; return this; }

        /**
        * 兼容旧 API：设置为临时节点。
        * @param flag flag
        * @return forEphemeral的结果
         */
        public CreateBuilder forEphemeral(boolean flag) {
            this.mode = flag ? org.apache.zookeeper.CreateMode.EPHEMERAL : org.apache.zookeeper.CreateMode.PERSISTENT;
            return this;
        }

        /**
        * 执行创建，返回创建的路径。
        * @return for路径的结果
         */
        public String forPath() throws Exception {
            var op = curator.create();
            if (creatingParentsIfNeeded) {
                op.creatingParentsIfNeeded();
            }
            op.withMode(mode);
            return op.forPath(path, data);
        }
    }

    /**
    * 删除节点构建器。
    * @author CH
    * @since 4.0.0
     */
    public static class DeleteBuilder {
        /**
        * Curator 客户端实例
         */
        private final CuratorFramework curator;
        /**
        * 节点路径
         */
        private String path;
        /**
        * 是否级联删除子节点，默认 false
         */
        private boolean deletingChildrenIfNeeded = false;
        /**
        * 是否静默删除（忽略异常），默认 false
         */
        private boolean quiet = false;

        DeleteBuilder(CuratorFramework curator) { this.curator = curator; }

        /**
        * 路径
        *
        * @param path 路径
        * @return 路径的结果
         */
        public DeleteBuilder path(String path) { this.path = path; return this; }
        /**
        * 删除childrenifneeded
        *
        * @param flag flag
        * @return 删除childrenifneeded的结果
         */
        public DeleteBuilder deletingChildrenIfNeeded(boolean flag) { this.deletingChildrenIfNeeded = flag; return this; }
        /**
        * Quiet
        *
        * @param flag flag
        * @return quiet的结果
         */
        public DeleteBuilder quiet(boolean flag) { this.quiet = flag; return this; }

        /**
        * 执行删除。
         */
        public void forPath() throws Exception {
            var op = curator.delete();
            if (deletingChildrenIfNeeded) {
                op.deletingChildrenIfNeeded();
            }
            if (quiet) {
                op.quietly();
            }
            op.forPath(path);
        }
    }

    /**
    * 获取数据构建器。
    * @author CH
    * @since 4.0.0
     */
    public static class GetDataBuilder {
        /**
        * Curator 客户端实例
         */
        private final CuratorFramework curator;
        /**
        * 节点路径
         */
        private String path;

        GetDataBuilder(CuratorFramework curator) { this.curator = curator; }

        /**
        * 路径
        *
        * @param path 路径
        * @return 路径的结果
         */
        public GetDataBuilder path(String path) { this.path = path; return this; }

        /**
        * 获取数据（字节数组）。
        * @return forBytes的结果
         */
        public byte[] forBytes() throws Exception {
            return curator.getData().forPath(path);
        }

        /**
        * 获取数据（字符串）。
        * @return for字符串的结果
         */
        public String forString() throws Exception {
            byte[] data = forBytes();
            return data != null ? new String(data) : null;
        }

        /**
        * 获取数据长度（-1 表示节点不存在）。
        * @return forStat的结果
         */
        public int forStat() throws Exception {
            var stat = curator.checkExists().forPath(path);
            return stat != null ? stat.getDataLength() : -1;
        }
    }

    /**
    * 设置数据构建器。
    * @author CH
    * @since 4.0.0
     */
    public static class SetDataBuilder {
        /**
        * Curator 客户端实例
         */
        private final CuratorFramework curator;
        /**
        * 节点路径
         */
        private String path;
        /**
        * 节点数据
         */
        private byte[] data;

        SetDataBuilder(CuratorFramework curator) { this.curator = curator; }

        /**
        * 路径
        *
        * @param path 路径
        * @return 路径的结果
         */
        public SetDataBuilder path(String path) { this.path = path; return this; }
        /**
        * 数据
        *
        * @param data 数据
        * @return 数据的结果
         */
        public SetDataBuilder data(String data) { this.data = data != null ? data.getBytes() : null; return this; }
        /**
        * 数据
        *
        * @param data 数据
        * @return 数据的结果
         */
        public SetDataBuilder data(byte[] data) { this.data = data; return this; }

        /**
        * 执行设置。
         */
        public void forPath() throws Exception {
            curator.setData().forPath(path, data);
        }
    }

    /**
    * 判断节点是否存在构建器。
    * @author CH
    * @since 4.0.0
     */
    public static class CheckExistsBuilder {
        /**
        * Curator 客户端实例
         */
        private final CuratorFramework curator;
        /**
        * 节点路径
         */
        private String path;

        CheckExistsBuilder(CuratorFramework curator) { this.curator = curator; }

        /**
        * 路径
        *
        * @param path 路径
        * @return 路径的结果
         */
        public CheckExistsBuilder path(String path) { this.path = path; return this; }

        /**
        * 判断是否存在。
        * @return forBool的结果
         */
        public boolean forBool() throws Exception {
            return curator.checkExists().forPath(path) != null;
        }
    }

    /**
    * 获取子节点构建器。
    * @author CH
    * @since 4.0.0
     */
    public static class GetChildrenBuilder {
        /**
        * Curator 客户端实例
         */
        private final CuratorFramework curator;
        /**
        * 节点路径
         */
        private String path;

        GetChildrenBuilder(CuratorFramework curator) { this.curator = curator; }

        /**
        * 路径
        *
        * @param path 路径
        * @return 路径的结果
         */
        public GetChildrenBuilder path(String path) { this.path = path; return this; }

        /**
        * 获取子节点列表。
        * @return for列表的结果
         */
        public List<String> forList() throws Exception {
            return curator.getChildren().forPath(path);
        }

        /**
        * 获取子节点数量。
        * @return for数量的结果
         */
        public int forCount() throws Exception {
            return forList().size();
        }
    }
}
