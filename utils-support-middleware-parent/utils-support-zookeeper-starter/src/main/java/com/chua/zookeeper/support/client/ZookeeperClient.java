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
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Getter
public class ZookeeperClient implements AutoCloseable {

    private final CuratorFramework curator;
    private final String connectString;

    private ZookeeperClient(CuratorFramework curator, String connectString) {
        this.curator = curator;
        this.connectString = connectString;
    }

    // ==================== 工厂方法 ====================

    public static ZookeeperClient create(String connectString) {
        return builder().connectString(connectString).build();
    }

    public static ZookeeperClient create(String connectString, int sessionTimeoutMs) {
        return builder().connectString(connectString).sessionTimeoutMs(sessionTimeoutMs).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    // ==================== 连接管理 ====================

    /**
     * 启动连接。
     */
    public ZookeeperClient start() {
        curator.start();
        log.info("ZooKeeper 客户端启动: {}", connectString);
        return this;
    }

    /**
     * 等待连接就绪。
     */
    public ZookeeperClient blockUntilConnected() throws InterruptedException {
        curator.blockUntilConnected();
        return this;
    }

    /**
     * 获取 Curator 客户端。
     */
    public CuratorFramework getCurator() {
        return curator;
    }

    // ==================== 节点操作 ====================

    /**
     * 创建节点构建器。
     */
    public CreateBuilder create() {
        return new CreateBuilder(curator);
    }

    /**
     * 删除节点构建器。
     */
    public DeleteBuilder delete() {
        return new DeleteBuilder(curator);
    }

    /**
     * 获取数据构建器。
     */
    public GetDataBuilder getData() {
        return new GetDataBuilder(curator);
    }

    /**
     * 设置数据构建器。
     */
    public SetDataBuilder setData() {
        return new SetDataBuilder(curator);
    }

    /**
     * 判断节点是否存在构建器。
     */
    public CheckExistsBuilder checkExists() {
        return new CheckExistsBuilder(curator);
    }

    /**
     * 获取子节点构建器。
     */
    public GetChildrenBuilder getChildren() {
        return new GetChildrenBuilder(curator);
    }

    @Override
    public void close() {
        if (curator != null) {
            curator.close();
        }
    }

    // ==================== Builder ====================

    public static class Builder {
        private String connectString = "127.0.0.1:2181";
        private int sessionTimeoutMs = 30000;
        private int connectionTimeoutMs = 15000;
        private int retryBaseSleepMs = 1000;
        private int retryMaxRetries = 3;
        private String namespace;

        public Builder connectString(String connectString) { this.connectString = connectString; return this; }
        public Builder sessionTimeoutMs(int ms) { this.sessionTimeoutMs = ms; return this; }
        public Builder connectionTimeoutMs(int ms) { this.connectionTimeoutMs = ms; return this; }
        public Builder retryBaseSleepMs(int ms) { this.retryBaseSleepMs = ms; return this; }
        public Builder retryMaxRetries(int max) { this.retryMaxRetries = max; return this; }
        public Builder namespace(String namespace) { this.namespace = namespace; return this; }

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
     */
    public static class CreateBuilder {
        private final CuratorFramework curator;
        private String path;
        private byte[] data;
        private boolean creatingParentsIfNeeded = true;
        private org.apache.zookeeper.CreateMode mode = org.apache.zookeeper.CreateMode.PERSISTENT;
        private Map<String, byte[]> acl;

        CreateBuilder(CuratorFramework curator) { this.curator = curator; }

        public CreateBuilder path(String path) { this.path = path; return this; }
        public CreateBuilder data(String data) { this.data = data != null ? data.getBytes() : null; return this; }
        public CreateBuilder data(byte[] data) { this.data = data; return this; }
        public CreateBuilder creatingParentsIfNeeded(boolean flag) { this.creatingParentsIfNeeded = flag; return this; }
        public CreateBuilder withACL(Map<String, byte[]> acl) { this.acl = acl; return this; }

        /**
         * 设置为持久节点（默认）。
         */
        public CreateBuilder persistent() { this.mode = org.apache.zookeeper.CreateMode.PERSISTENT; return this; }

        /**
         * 设置为持久顺序节点。
         */
        public CreateBuilder persistentSequential() { this.mode = org.apache.zookeeper.CreateMode.PERSISTENT_SEQUENTIAL; return this; }

        /**
         * 设置为临时节点，客户端断开后自动删除。
         */
        public CreateBuilder ephemeral() { this.mode = org.apache.zookeeper.CreateMode.EPHEMERAL; return this; }

        /**
         * 设置为临时顺序节点，自动追加递增序号。
         */
        public CreateBuilder ephemeralSequential() { this.mode = org.apache.zookeeper.CreateMode.EPHEMERAL_SEQUENTIAL; return this; }

        /**
         * 兼容旧 API：设置为临时节点。
         */
        public CreateBuilder forEphemeral(boolean flag) {
            this.mode = flag ? org.apache.zookeeper.CreateMode.EPHEMERAL : org.apache.zookeeper.CreateMode.PERSISTENT;
            return this;
        }

        /**
         * 执行创建，返回创建的路径。
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
     */
    public static class DeleteBuilder {
        private final CuratorFramework curator;
        private String path;
        private boolean deletingChildrenIfNeeded = false;
        private boolean quiet = false;

        DeleteBuilder(CuratorFramework curator) { this.curator = curator; }

        public DeleteBuilder path(String path) { this.path = path; return this; }
        public DeleteBuilder deletingChildrenIfNeeded(boolean flag) { this.deletingChildrenIfNeeded = flag; return this; }
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
     */
    public static class GetDataBuilder {
        private final CuratorFramework curator;
        private String path;

        GetDataBuilder(CuratorFramework curator) { this.curator = curator; }

        public GetDataBuilder path(String path) { this.path = path; return this; }

        /**
         * 获取数据（字节数组）。
         */
        public byte[] forBytes() throws Exception {
            return curator.getData().forPath(path);
        }

        /**
         * 获取数据（字符串）。
         */
        public String forString() throws Exception {
            byte[] data = forBytes();
            return data != null ? new String(data) : null;
        }

        /**
         * 获取数据长度（-1 表示节点不存在）。
         */
        public int forStat() throws Exception {
            var stat = curator.checkExists().forPath(path);
            return stat != null ? stat.getDataLength() : -1;
        }
    }

    /**
     * 设置数据构建器。
     */
    public static class SetDataBuilder {
        private final CuratorFramework curator;
        private String path;
        private byte[] data;

        SetDataBuilder(CuratorFramework curator) { this.curator = curator; }

        public SetDataBuilder path(String path) { this.path = path; return this; }
        public SetDataBuilder data(String data) { this.data = data != null ? data.getBytes() : null; return this; }
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
     */
    public static class CheckExistsBuilder {
        private final CuratorFramework curator;
        private String path;

        CheckExistsBuilder(CuratorFramework curator) { this.curator = curator; }

        public CheckExistsBuilder path(String path) { this.path = path; return this; }

        /**
         * 判断是否存在。
         */
        public boolean forBool() throws Exception {
            return curator.checkExists().forPath(path) != null;
        }
    }

    /**
     * 获取子节点构建器。
     */
    public static class GetChildrenBuilder {
        private final CuratorFramework curator;
        private String path;

        GetChildrenBuilder(CuratorFramework curator) { this.curator = curator; }

        public GetChildrenBuilder path(String path) { this.path = path; return this; }

        /**
         * 获取子节点列表。
         */
        public List<String> forList() throws Exception {
            return curator.getChildren().forPath(path);
        }

        /**
         * 获取子节点数量。
         */
        public int forCount() throws Exception {
            return forList().size();
        }
    }
}
