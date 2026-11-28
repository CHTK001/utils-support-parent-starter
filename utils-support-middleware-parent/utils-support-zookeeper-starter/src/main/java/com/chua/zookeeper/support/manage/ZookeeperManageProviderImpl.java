// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.zookeeper.support.manage;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.middleware.zookeeper.ZookeeperAclInfo;
import com.chua.datasource.support.middleware.zookeeper.ZookeeperChildNodeInfo;
import com.chua.datasource.support.middleware.zookeeper.ZookeeperClusterNode;
import com.chua.datasource.support.middleware.zookeeper.ZookeeperManageProvider;
import com.chua.datasource.support.middleware.zookeeper.ZookeeperManageTarget;
import com.chua.datasource.support.middleware.zookeeper.ZookeeperNodeDetail;
import com.chua.datasource.support.middleware.zookeeper.ZookeeperNodeInfo;
import com.chua.datasource.support.middleware.zookeeper.ZookeeperTestResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.retry.RetryNTimes;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * ZooKeeper 管理面 SPI 实现：树浏览、节点读写、ACL 查看与 {@code mntr} 指标采集。
 *
 * <p>按「连接串 + chroot + 账号 + 配置版本」维护长会话，配置改动（{@code versionToken} 变化）
 * 即换新会话，避免旧连接串继续生效；空闲会话在被再次取用时回收。</p>
 *
 * <p>所有远端异常都翻译成 {@link IllegalStateException} 的可读原因（宿主直接转业务错误码），
 * 不返回演示数据、不吞异常。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
@Spi(value = "zookeeper", order = 200)
public class ZookeeperManageProviderImpl implements ZookeeperManageProvider {

    /**
     * 会话超时（毫秒）
     */
    private static final int SESSION_TIMEOUT_MS = 8000;

    /**
     * 连接超时（毫秒）
     */
    private static final int CONNECT_TIMEOUT_MS = 5000;

    /**
     * 四字命令连接与读取超时（毫秒）
     */
    private static final int FOUR_LETTER_TIMEOUT_MS = 3000;

    /**
     * 空闲会话回收阈值（毫秒）
     */
    private static final long IDLE_EVICT_MS = 10 * 60 * 1000L;

    /**
     * 单次列举上限，避免把巨型树刷进响应体
     */
    private static final int MAX_CHILDREN = 500;

    /**
     * 节点数据最大读取长度
     */
    private static final int MAX_DATA = 64 * 1024;

    /**
     * 展示用时间格式
     */
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 长会话缓存：连接标识 → Curator 客户端
     */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Override
    public ZookeeperTestResult test(ZookeeperManageTarget target) {
        try {
            CuratorFramework curator = session(target);
            List<String> hosts = target.hosts();
            String version = hosts.isEmpty() ? "" : serverVersion(hosts.get(0), "srvr");
            return new ZookeeperTestResult(true, version, sessionId(curator), "连接成功");
        } catch (IllegalStateException e) {
            return new ZookeeperTestResult(false, null, null, e.getMessage());
        }
    }

    @Override
    public List<ZookeeperNodeInfo> tree(ZookeeperManageTarget target, String parentPath) {
        String parent = requirePath(parentPath == null || parentPath.isBlank() ? "/" : parentPath);
        return withSession(target, curator -> {
            List<String> names = listChildren(curator, parent);
            List<ZookeeperNodeInfo> nodes = new ArrayList<>(names.size());
            for (String name : names) {
                String child = childOf(parent, name);
                Stat stat = checkExists(curator, child);
                nodes.add(new ZookeeperNodeInfo(child, name, isEphemeral(stat),
                        stat == null ? 0 : stat.getNumChildren()));
            }
            return nodes;
        });
    }

    @Override
    public List<ZookeeperChildNodeInfo> children(ZookeeperManageTarget target, String parentPath) {
        String parent = requirePath(parentPath == null || parentPath.isBlank() ? "/" : parentPath);
        return withSession(target, curator -> {
            List<String> names = listChildren(curator, parent);
            List<ZookeeperChildNodeInfo> children = new ArrayList<>(names.size());
            for (String name : names) {
                String child = childOf(parent, name);
                Stat stat = checkExists(curator, child);
                children.add(new ZookeeperChildNodeInfo(name, child, isEphemeral(stat), readData(curator, child)));
            }
            return children;
        });
    }

    @Override
    public ZookeeperNodeDetail node(ZookeeperManageTarget target, String path) {
        String node = requirePath(path);
        return withSession(target, curator -> {
            Stat stat = checkExists(curator, node);
            if (stat == null) {
                throw new KeeperException.NoNodeException(node);
            }
            String data = decode(curator.getData().forPath(node));
            return new ZookeeperNodeDetail(node, isEphemeral(stat),
                    format(stat.getCtime()), format(stat.getMtime()),
                    stat.getVersion(), stat.getNumChildren(), stat.getDataLength(), data,
                    stat.getCzxid(), stat.getMzxid(), stat.getPzxid(),
                    stat.getCversion(), stat.getAversion(), stat.getEphemeralOwner());
        });
    }

    @Override
    public boolean create(ZookeeperManageTarget target, String path, String data, boolean ephemeral) {
        String node = requirePath(path);
        byte[] payload = bytes(data);
        CreateMode mode = ephemeral ? CreateMode.EPHEMERAL : CreateMode.PERSISTENT;
        return withSession(target, curator -> {
            curator.create().creatingParentsIfNeeded().withMode(mode).forPath(node, payload);
            return true;
        });
    }

    @Override
    public boolean update(ZookeeperManageTarget target, String path, String data) {
        String node = requirePath(path);
        byte[] payload = bytes(data);
        return withSession(target, curator -> {
            if (checkExists(curator, node) == null) {
                throw new KeeperException.NoNodeException(node);
            }
            curator.setData().forPath(node, payload);
            return true;
        });
    }

    @Override
    public boolean delete(ZookeeperManageTarget target, String path, boolean recursive) {
        String node = requirePath(path);
        return withSession(target, curator -> {
            if (recursive) {
                // 递归删除：Curator 从叶子向根逐个 delete，保证中间节点不空
                curator.delete().guaranteed().deletingChildrenIfNeeded().forPath(node);
            } else {
                curator.delete().forPath(node);
            }
            return true;
        });
    }

    @Override
    public List<ZookeeperAclInfo> acl(ZookeeperManageTarget target, String path) {
        String node = requirePath(path);
        return withSession(target, curator -> {
            if (checkExists(curator, node) == null) {
                throw new KeeperException.NoNodeException(node);
            }
            List<ACL> acls = curator.getACL().forPath(node);
            List<ZookeeperAclInfo> result = new ArrayList<>(acls == null ? 0 : acls.size());
            if (acls != null) {
                for (ACL acl : acls) {
                    result.add(new ZookeeperAclInfo(acl.getId().getScheme(), acl.getId().getId(),
                            acl.getPerms(), permsText(acl.getPerms())));
                }
            }
            return result;
        });
    }

    @Override
    public List<ZookeeperClusterNode> cluster(ZookeeperManageTarget target) {
        List<String> hosts = target.hosts();
        if (hosts.isEmpty()) {
            throw new IllegalStateException("数据源 " + target.name() + " 未填写主机地址");
        }
        List<ZookeeperClusterNode> result = new ArrayList<>(hosts.size());
        for (String host : hosts) {
            String body = fourLetter(host, "mntr", FOUR_LETTER_TIMEOUT_MS);
            String error = body.isBlank() ? "四字命令 mntr 未放开或不可达" : null;
            result.add(new ZookeeperClusterNode(host, body, error));
        }
        return result;
    }

    // ==================== 会话管理 ====================

    /**
     * 取（必要时新建）长会话。
     *
     * @param target 连接目标
     * @return Curator 客户端
     * @throws IllegalStateException 连接失败
     */
    private CuratorFramework session(ZookeeperManageTarget target) {
        evictIdle();
        String key = key(target);
        Session cached = sessions.get(key);
        if (cached != null && cached.connected()) {
            cached.touch();
            return cached.curator();
        }
        synchronized (this) {
            Session again = sessions.get(key);
            if (again != null && again.connected()) {
                again.touch();
                return again.curator();
            }
            closeQuietly(key);
            String connectString = connectString(target);
            CuratorFramework curator = null;
            try {
                CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
                        .connectString(connectString)
                        .sessionTimeoutMs(SESSION_TIMEOUT_MS)
                        .connectionTimeoutMs(CONNECT_TIMEOUT_MS)
                        .retryPolicy(new RetryNTimes(0, 0));
                if (!target.namespace().isEmpty()) {
                    builder.namespace(target.namespace());
                }
                if (!target.username().isBlank()) {
                    builder.authorization("digest",
                            (target.username() + ":" + target.password())
                                    .getBytes(StandardCharsets.UTF_8));
                }
                curator = builder.build();
                curator.start();
                curator.blockUntilConnected(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (curator.getZookeeperClient() == null || !curator.getZookeeperClient().isConnected()) {
                    throw new IllegalStateException("连接超时（" + CONNECT_TIMEOUT_MS + "ms 内未连上 "
                            + connectString + "）");
                }
                sessions.put(key, new Session(curator));
                return curator;
            } catch (Exception e) {
                closeQuietly(curator);
                String reason = reasonOf(e);
                log.warn("[zookeeper] 数据源 {}({}) 连接失败: {}", target.name(), connectString, reason);
                throw new IllegalStateException("ZooKeeper " + target.name() + "(" + connectString
                        + ") " + reason, e);
            }
        }
    }

    /**
     * 在会话内执行操作，并把异常翻译成可读的业务异常。
     *
     * @param target 连接目标
     * @param work   实际操作
     * @param <T>    返回类型
     * @return 操作结果
     */
    private <T> T withSession(ZookeeperManageTarget target, CuratorFunction<T> work) {
        CuratorFramework curator = session(target);
        try {
            return work.apply(curator);
        } catch (Exception e) {
            String reason = reasonOf(e);
            log.warn("[zookeeper] 数据源 {}({}) 操作失败: {}", target.name(), connectStringQuietly(target), reason);
            throw new IllegalStateException("ZooKeeper " + target.name() + "("
                    + connectStringQuietly(target) + ") " + reason, e);
        }
    }

    /**
     * 空闲会话回收。
     */
    private void evictIdle() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            if (now - entry.getValue().lastUsed() > IDLE_EVICT_MS) {
                closeQuietly(entry.getKey());
            }
        }
    }

    /**
     * 关闭并移除会话。
     *
     * @param key 缓存键
     */
    private void closeQuietly(String key) {
        Session removed = sessions.remove(key);
        if (removed != null) {
            closeQuietly(removed.curator());
        }
    }

    /**
     * 静默关闭 Curator 客户端。
     *
     * @param curator 客户端，可为 null
     */
    private void closeQuietly(CuratorFramework curator) {
        if (curator == null) {
            return;
        }
        try {
            if (curator.getState() != CuratorFrameworkState.STOPPED) {
                curator.close();
            }
        } catch (Exception e) {
            log.debug("[zookeeper] 关闭会话失败: {}", e.getMessage());
        }
    }

    /**
     * 缓存键：连接串 + chroot + 账号 + 配置版本。
     *
     * @param target 连接目标
     * @return 键
     */
    private static String key(ZookeeperManageTarget target) {
        return connectStringQuietly(target) + "|" + target.namespace() + "|"
                + target.username() + "|" + target.versionToken();
    }

    /**
     * 连接串，解析失败时退化为原始 host。
     *
     * @param target 连接目标
     * @return 连接串
     */
    private static String connectStringQuietly(ZookeeperManageTarget target) {
        try {
            String value = connectString(target);
            return value.isEmpty() ? target.host() : value;
        } catch (Exception e) {
            return target.host();
        }
    }

    /**
     * 连接串，host 为空时抛异常。
     *
     * @param target 连接目标
     * @return 连接串
     */
    private static String connectString(ZookeeperManageTarget target) {
        if (target.host().isEmpty()) {
            throw new IllegalStateException("数据源 " + target.name() + " 未填写主机地址");
        }
        return target.connectString();
    }

    // ==================== 协议小工具 ====================

    /**
     * 列出子节点，并对异常路径给空列表（根节点不存在时按空树处理）。
     *
     * @param curator 客户端
     * @param path    父路径
     * @return 子节点名列表
     * @throws Exception 由调用方统一翻译
     */
    private static List<String> listChildren(CuratorFramework curator, String path) throws Exception {
        try {
            List<String> names = curator.getChildren().forPath(path);
            if (names == null) {
                return List.of();
            }
            return names.size() > MAX_CHILDREN ? names.subList(0, MAX_CHILDREN) : names;
        } catch (KeeperException.NoNodeException e) {
            return List.of();
        }
    }

    /**
     * 判断节点是否存在。
     *
     * @param curator 客户端
     * @param path    节点路径
     * @return 节点状态，不存在为 null
     * @throws Exception 由调用方统一翻译
     */
    private static Stat checkExists(CuratorFramework curator, String path) throws Exception {
        return curator.checkExists().forPath(path);
    }

    /**
     * 尽力读取节点数据，节点在列举后被删除时给占位而不是整页失败。
     *
     * @param curator 客户端
     * @param path    节点路径
     * @return 可展示文本
     */
    private static String readData(CuratorFramework curator, String path) {
        try {
            byte[] bytes = curator.getData().forPath(path);
            return decode(bytes);
        } catch (Exception e) {
            return "<读取失败: " + e.getClass().getSimpleName() + ">";
        }
    }

    /**
     * 会话 ID（十六进制）。
     *
     * @param curator 客户端
     * @return 会话 ID，取不到为空串
     */
    private static String sessionId(CuratorFramework curator) {
        try {
            return Long.toHexString(curator.getZookeeperClient().getZooKeeper().getSessionId());
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 服务端版本：从四字命令回包里取 {@code Zookeeper version} 行。
     *
     * @param address 节点地址
     * @param command 四字命令
     * @return 版本号，取不到为空串
     */
    private static String serverVersion(String address, String command) {
        String body = fourLetter(address, command, FOUR_LETTER_TIMEOUT_MS);
        if (body.isBlank()) {
            return "";
        }
        for (String line : body.split("\\r?\\n")) {
            int idx = line.indexOf("version:");
            if (idx >= 0) {
                return line.substring(idx + "version:".length()).trim();
            }
        }
        return "";
    }

    /**
     * 向单个 ZooKeeper 节点发送一次四字命令并返回原始响应。
     *
     * <p>服务端需在 {@code 4lw.commands.whitelist} 中放开对应命令，否则连接被拒或返回错误串。
     * 该方法不抛异常，失败返回空串，由调用方决定如何展示。</p>
     *
     * @param address   {@code host:port}
     * @param command   四字命令
     * @param timeoutMs 连接与读取超时（毫秒）
     * @return 响应全文，失败为空串
     */
    public static String fourLetter(String address, String command, int timeoutMs) {
        int split = address.lastIndexOf(':');
        if (split <= 0 || split == address.length() - 1) {
            return "";
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address.substring(0, split),
                    Integer.parseInt(address.substring(split + 1))), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.getOutputStream().write((command + "\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            socket.shutdownOutput();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("[zookeeper] {} 四字命令 {} 失败: {}", address, command, e.getMessage());
            return "";
        }
    }

    /**
     * 路径归一：必须以 {@code /} 开头，去掉尾部多余斜杠。
     *
     * @param path 原始路径
     * @return 归一后的路径
     * @throws IllegalArgumentException 路径为空或不合法
     */
    private static String requirePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("节点路径不能为空");
        }
        String value = path.trim();
        if (!value.startsWith("/")) {
            throw new IllegalArgumentException("节点路径必须以 / 开头: " + value);
        }
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * 拼接父路径与子节点名。
     *
     * @param parent 父路径
     * @param name   子节点名
     * @return 子节点绝对路径
     */
    private static String childOf(String parent, String name) {
        return "/".equals(parent) ? "/" + name : parent + "/" + name;
    }

    /**
     * 节点数据编码：空文本按零长度处理，避免 Curator 传 null。
     *
     * @param data 原始文本
     * @return 字节数组
     */
    private static byte[] bytes(String data) {
        return data == null ? new byte[0] : data.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 节点数据解码。
     *
     * @param data 原始字节
     * @return 可展示文本
     */
    private static String decode(byte[] data) {
        return data == null ? "" : new String(data, StandardCharsets.UTF_8);
    }

    /**
     * 是否临时节点（ephemeralOwner 非 0）。
     *
     * @param stat 节点状态，可为 null
     * @return 临时节点为真
     */
    private static boolean isEphemeral(Stat stat) {
        return stat != null && stat.getEphemeralOwner() != 0L;
    }

    /**
     * 事务时间转展示文本。
     *
     * @param timestamp 毫秒时间戳，0 表示未设置
     * @return 展示文本，未设置为空串
     */
    private static String format(long timestamp) {
        if (timestamp <= 0) {
            return "";
        }
        return DISPLAY.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()));
    }

    /**
     * 把 ACL 权限位掩码解析为可读组合。
     *
     * <p>位定义：1=READ, 2=WRITE, 4=CREATE, 8=DELETE, 16=ADMIN；31 为 ALL。</p>
     *
     * @param perms 权限位掩码
     * @return 可读权限组合
     */
    private static String permsText(int perms) {
        if (perms == 31) {
            return "ALL";
        }
        List<String> parts = new ArrayList<>(5);
        if ((perms & 1) != 0) {
            parts.add("READ");
        }
        if ((perms & 2) != 0) {
            parts.add("WRITE");
        }
        if ((perms & 4) != 0) {
            parts.add("CREATE");
        }
        if ((perms & 8) != 0) {
            parts.add("DELETE");
        }
        if ((perms & 16) != 0) {
            parts.add("ADMIN");
        }
        return parts.isEmpty() ? "NONE" : String.join("|", parts);
    }

    /**
     * 逐层取异常原因，ZooKeeper 的语义化异常换成中文。
     *
     * @param e 原始异常
     * @return 可读原因
     */
    private static String reasonOf(Throwable e) {
        if (e instanceof KeeperException.NoNodeException ex) {
            return "节点不存在: " + ex.getPath();
        }
        if (e instanceof KeeperException.NodeExistsException ex) {
            return "节点已存在: " + ex.getPath();
        }
        if (e instanceof KeeperException.NotEmptyException ex) {
            return "节点仍有子节点，请先删除子节点: " + ex.getPath();
        }
        if (e instanceof KeeperException.SessionExpiredException) {
            return "会话已过期，请重试";
        }
        if (e instanceof KeeperException.ConnectionLossException) {
            return "连接已中断，请重试";
        }
        if (e instanceof KeeperException.NoAuthException) {
            return "无权限访问该节点（digest 账号不足）";
        }
        if (e instanceof KeeperException.InvalidACLException) {
            return "ACL 不合法";
        }
        if (e instanceof KeeperException.BadArgumentsException) {
            return "参数不合法（路径过长或数据过大）";
        }
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return "等待响应被中断";
        }
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    /**
     * Curator 操作闭包（可抛受检异常）。
     *
     * @param <T> 返回类型
     * @author CH
     * @since 4.0.0.51
     */
    @FunctionalInterface
    private interface CuratorFunction<T> {

        /**
         * 执行操作。
         *
         * @param curator Curator 客户端
         * @return 结果
         * @throws Exception 远端异常
         */
        T apply(CuratorFramework curator) throws Exception;
    }

    /**
     * 缓存的长会话。
     *
     * @author CH
     * @since 4.0.0.51
     */
    private static final class Session {

        /**
         * Curator 客户端
         */
        private final CuratorFramework curator;

        /**
         * 最近使用时间（毫秒）
         */
        private volatile long lastUsed;

        /**
         * 构造 Session。
         *
         * @param curator Curator 客户端
         */
        private Session(CuratorFramework curator) {
            this.curator = curator;
            this.lastUsed = System.currentTimeMillis();
        }

        /**
         * 获取 Curator 客户端。
         *
         * @return Curator 客户端
         */
        CuratorFramework curator() {
            return curator;
        }

        /**
         * 最近使用时间。
         *
         * @return 毫秒时间戳
         */
        long lastUsed() {
            return lastUsed;
        }

        /**
         * 刷新最近使用时间。
         */
        void touch() {
            this.lastUsed = System.currentTimeMillis();
        }

        /**
         * 是否仍可复用（Curator 处于 STARTED 且有底层客户端）。
         *
         * @return 可复用为真
         */
        boolean connected() {
            return curator.getState() == CuratorFrameworkState.STARTED
                    && curator.getZookeeperClient() != null
                    && curator.getZookeeperClient().isConnected();
        }
    }
}
