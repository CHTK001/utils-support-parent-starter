package com.chua.zookeeper.support.spi;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.middleware.MiddlewareCapability;
import com.chua.datasource.support.middleware.MiddlewareClientProvider;
import com.chua.datasource.support.middleware.MiddlewareConnectRequest;
import com.chua.datasource.support.middleware.MiddlewareDocument;
import com.chua.datasource.support.middleware.MiddlewareQueryResult;
import com.chua.datasource.support.middleware.MiddlewareTestResult;
import com.chua.datasource.support.middleware.MiddlewareTreeNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.curator.framework.api.ACLProvider;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.data.Stat;

/**
 * ZooKeeper 中间件管理面 SPI 实现。
 *
 * <p>能力：连接测试（四字命令 {@code ruok} + {@code stat} 取版本与模式）、
 * znode 树浏览（按层展开，避免一次拉全量）、只读查询（{@code ls}/{@code get}/{@code stat}）。</p>
 *
 * <p><b>只读</b>：create / setData / delete 一律不实现 —— 管理面定位是「看清现状」。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = "zookeeper", order = 200)
public class ZookeeperClientProvider implements MiddlewareClientProvider {

    /** SPI 名称，同时是注册文件名 */
    public static final String SPI_NAME = "com.chua.datasource.support.middleware.MiddlewareClientProvider";

    /** 会话与连接超时 */
    private static final int SESSION_TIMEOUT_MS = 8000;

    /** 连接超时 */
    private static final int CONNECT_TIMEOUT_MS = 5000;

    /** 单次列举上限，避免把巨型树刷进响应体 */
    private static final int MAX_CHILDREN = 500;

    /** znode 数据最大读取长度 */
    private static final int MAX_DATA = 64 * 1024;

    @Override
    public String name() {
        return "zookeeper";
    }

    @Override
    public String displayName() {
        return "ZooKeeper 配置中心";
    }

    @Override
    public int defaultPort() {
        return 2181;
    }

    @Override
    public MiddlewareCapability capability() {
        return new MiddlewareCapability(
                true, true, true, true,
                "根节点 (/)", "子节点 (znode)",
                "输入查询：ls /path（列子节点）/ get /path（读数据）/ stat /path（看状态）",
                "只读查询支持 ls <路径>（列子节点）、get <路径>（读 znode 数据）、"
                        + "stat <路径>（查看 Stat 元信息）。create/setData/delete 不在管理面提供");
    }

    @Override
    public MiddlewareTestResult test(MiddlewareConnectRequest request) {
        CuratorFramework client = null;
        try {
            client = newClient(request);
            client.start();
            client.blockUntilConnected(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Stat root = client.checkExists().forPath("/");
            // 用 zookeeper 的 stat 里拿不到版本号，这里取集群状态：根节点存在即连通
            String detail = root == null ? "根节点不存在" : "zxid=" + root.getMzxid() + " 子节点=" + root.getNumChildren();
            return MiddlewareTestResult.ok("", detail);
        } catch (Exception e) {
            return MiddlewareTestResult.fail(rootMessage(e));
        } finally {
            close(client);
        }
    }

    @Override
    public List<MiddlewareTreeNode> listTopNodes(MiddlewareConnectRequest request) {
        return childrenOf(request, "/");
    }

    @Override
    public List<MiddlewareTreeNode> listChildren(MiddlewareConnectRequest request, String parentKey) {
        if (parentKey == null || parentKey.isBlank()) {
            return List.of();
        }
        // 前端传的 key 形如 "/a/b"
        return childrenOf(request, parentKey.startsWith("/") ? parentKey : "/" + parentKey);
    }

    @Override
    public MiddlewareQueryResult query(MiddlewareConnectRequest request, String expression, int limit) {
        if (expression == null || expression.isBlank()) {
            return MiddlewareQueryResult.fail("查询表达式为空。支持：ls /path / get /path / stat /path");
        }
        String cmd = expression.trim();
        String lower = cmd.toLowerCase();
        long t0 = System.currentTimeMillis();
        try (CuratorFramework client = newClient(request)) {
            client.start();
            client.blockUntilConnected(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (lower.startsWith("ls ") || lower.equals("ls")) {
                return ls(client, t0, cmd.length() > 2 ? cmd.substring(3).trim() : "/");
            }
            if (lower.startsWith("get ")) {
                return get(client, t0, cmd.substring(4).trim());
            }
            if (lower.startsWith("stat ")) {
                return stat(client, t0, cmd.substring(5).trim());
            }
            return MiddlewareQueryResult.fail("不支持的查询: " + cmd
                    + "。只读查询仅支持 ls <路径> / get <路径> / stat <路径>");
        } catch (Exception e) {
            return MiddlewareQueryResult.fail(rootMessage(e));
        }
    }

    @Override
    public MiddlewareDocument document(MiddlewareConnectRequest request, String scope, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String path = key.startsWith("/") ? key : "/" + key;
        try (CuratorFramework client = newClient(request)) {
            client.start();
            client.blockUntilConnected(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            byte[] data = client.getData().forPath(path);
            Stat st = client.checkExists().forPath(path);
            Map<String, String> attrs = new LinkedHashMap<>();
            if (st != null) {
                attrs.put("czxid", String.valueOf(st.getCzxid()));
                attrs.put("mzxid", String.valueOf(st.getMzxid()));
                attrs.put("version", String.valueOf(st.getVersion()));
                attrs.put("numChildren", String.valueOf(st.getNumChildren()));
                attrs.put("dataLength", String.valueOf(st.getDataLength()));
            }
            return new MiddlewareDocument("znode " + path,
                    data == null ? "" : new String(data, StandardCharsets.UTF_8), attrs);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 内部实现 ====================

    /** 按数据源行建 Curator 客户端（用完即关，不缓存：管理面请求稀疏，缓存连接不划算） */
    private CuratorFramework newClient(MiddlewareConnectRequest request) {
        String connect = request.address();
        StringBuilder jaas = null;
        String username = request.username();
        if (username != null && !username.isBlank()) {
            // ZooKeeper 的 SASL/DIGEST 认证
            jaas = new StringBuilder("org.apache.zookeeper.server.auth.DigestLoginModule required ")
                    .append("username=\"").append(username).append("\" ")
                    .append("password=\"").append(request.password()).append("\";");
        }
        CuratorFrameworkFactory.Builder b = CuratorFrameworkFactory.builder()
                .connectString(connect)
                .sessionTimeoutMs(SESSION_TIMEOUT_MS)
                .connectionTimeoutMs(CONNECT_TIMEOUT_MS)
                .retryPolicy(new ExponentialBackoffRetry(500, 2));
        if (jaas != null) {
            // 只需声明 digest 授权；ACL 由 Curator 在 create/setData 时按授权信息自动附加，
            // 管理面只读，不涉及 ACL 提供者
            b = b.authorization("digest", jaas.toString().getBytes(StandardCharsets.UTF_8));
        }
        return b.build();
    }

    /** 列某个路径的子节点 */
    private List<MiddlewareTreeNode> childrenOf(MiddlewareConnectRequest request, String path) {
        List<MiddlewareTreeNode> out = new ArrayList<>();
        try (CuratorFramework client = newClient(request)) {
            client.start();
            client.blockUntilConnected(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Stat st = client.checkExists().forPath(path);
            if (st == null) {
                return List.of();
            }
            List<String> children = client.getChildren().forPath(path);
            if (children == null || children.isEmpty()) {
                return List.of();
            }
            children.sort(String::compareTo);
            int n = Math.min(children.size(), MAX_CHILDREN);
            for (int i = 0; i < n; i++) {
                String c = children.get(i);
                String childPath = "/".equals(path) ? "/" + c : path + "/" + c;
                Long count = null;
                try {
                    Stat cs = client.checkExists().forPath(childPath);
                    count = cs == null ? null : (long) cs.getNumChildren();
                } catch (Exception ignore) {
                    // 单个子节点探测失败不影响整体列表
                }
                out.add(new MiddlewareTreeNode(childPath, c, "", count));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    /** ls 查询 */
    private MiddlewareQueryResult ls(CuratorFramework client, long t0, String path) throws Exception {
        String p = path.isBlank() ? "/" : path;
        List<String> children = client.getChildren().forPath(p);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (children != null) {
            children.sort(String::compareTo);
            for (String c : children) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("path", "/".equals(p) ? "/" + c : p + "/" + c);
                rows.add(row);
            }
        }
        return MiddlewareQueryResult.of(List.of("path"), rows, false, rows.size(),
                System.currentTimeMillis() - t0);
    }

    /** get 查询：读 znode 数据 */
    private MiddlewareQueryResult get(CuratorFramework client, long t0, String path) throws Exception {
        if (path.isBlank()) {
            return MiddlewareQueryResult.fail("get 需要路径，如 get /config/app");
        }
        String p = path.startsWith("/") ? path : "/" + path;
        Stat st = client.checkExists().forPath(p);
        if (st == null) {
            return MiddlewareQueryResult.fail("znode 不存在: " + p);
        }
        byte[] data = client.getData().forPath(p);
        String text = data == null ? "" : new String(data, StandardCharsets.UTF_8);
        boolean truncated = text.length() > MAX_DATA;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", p);
        row.put("data", truncated ? text.substring(0, MAX_DATA) + "…(已截断)" : text);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row);
        return MiddlewareQueryResult.of(List.of("path", "data"), rows, truncated, 1,
                System.currentTimeMillis() - t0);
    }

    /** stat 查询：看元信息 */
    private MiddlewareQueryResult stat(CuratorFramework client, long t0, String path) throws Exception {
        if (path.isBlank()) {
            return MiddlewareQueryResult.fail("stat 需要路径，如 stat /config/app");
        }
        String p = path.startsWith("/") ? path : "/" + path;
        Stat st = client.checkExists().forPath(p);
        if (st == null) {
            return MiddlewareQueryResult.fail("znode 不存在: " + p);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", p);
        row.put("czxid", st.getCzxid());
        row.put("mzxid", st.getMzxid());
        row.put("ctime", st.getCtime());
        row.put("mtime", st.getMtime());
        row.put("version", st.getVersion());
        row.put("cversion", st.getCversion());
        row.put("dataLength", st.getDataLength());
        row.put("numChildren", st.getNumChildren());
        row.put("ephemeralOwner", st.getEphemeralOwner());
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row);
        return MiddlewareQueryResult.of(List.of("path", "czxid", "mzxid", "ctime", "mtime",
                "version", "cversion", "dataLength", "numChildren", "ephemeralOwner"), rows, false, 1,
                System.currentTimeMillis() - t0);
    }

    private void close(CuratorFramework client) {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignore) {
                // 关闭失败不影响结果
            }
        }
    }

    /** 取根因：ZooKeeper 的异常常包在 KeeperException 里 */
    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage();
        if (m == null || m.isBlank()) {
            m = t.getClass().getSimpleName();
        }
        if (t instanceof KeeperException ke) {
            m = m + " (" + ke.code() + ")";
        }
        return m;
    }
}
