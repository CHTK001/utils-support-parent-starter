package com.chua.docker.support.client;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.ListVolumesResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.model.Version;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Docker 客户端，基于 docker-java SDK，支持链式调用。
 *
 * <p>封装 docker-java SDK，提供简化的链式 API。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * DockerClient docker = DockerClient.builder()
 *     .tcp("172.16.0.40", 2375)
 *     .connectTimeout(5000)
 *     .readTimeout(30000)
 *     .build();
 *
 * // 容器操作
 * docker.container().create("my-app")
 *     .image("nginx:latest")
 *     .port(80, 80)
 *     .env("TZ", "Asia/Shanghai")
 *     .exec();
 *
 * docker.container().list().all(true).exec();
 * docker.container().stop("my-app").timeout(10).exec();
 *
 * // 镜像操作
 * docker.image().pull("nginx:latest").exec();
 * docker.image().list().exec();
 *
 * // 系统
 * docker.ping();
 * docker.info();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Getter
public class DockerClient implements Closeable {

    /** docker-java 原生客户端委托 */
    private final com.github.dockerjava.api.DockerClient delegate;

    /** Docker 主机地址 */
    private final String host;

    /**
     * 私有构造器，通过 Builder 创建。
     *
     * @param b 构建器
     */
    private DockerClient(Builder b) {
        this.host = b.host;

        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(b.host)
                .withDockerCertPath(b.certPath)
                .withApiVersion(b.apiVersion)
                .build();

        ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(100)
                .connectionTimeout(Duration.ofMillis(b.connectTimeout))
                .responseTimeout(Duration.ofMillis(b.readTimeout))
                .build();

        this.delegate = DockerClientImpl.getInstance(config, httpClient);
    }

    /**
     * 获取建造器。
     *
     * @return Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 快速创建客户端。
     *
     * @param host 主机地址
     * @return DockerClient
     */
    public static DockerClient create(String host) {
        return builder().host(host).build();
    }

    /**
     * 获取容器操作对象。
     *
     * @return ContainerOps
     */
    public ContainerOps container() {
        return new ContainerOps(this);
    }

    /**
     * 获取镜像操作对象。
     *
     * @return ImageOps
     */
    public ImageOps image() {
        return new ImageOps(this);
    }

    /**
     * 获取网络操作对象。
     *
     * @return NetworkOps
     */
    public NetworkOps network() {
        return new NetworkOps(this);
    }

    /**
     * 获取卷操作对象。
     *
     * @return VolumeOps
     */
    public VolumeOps volume() {
        return new VolumeOps(this);
    }

    /**
     * 获取 Docker 系统信息。
     *
     * @return Info
     */
    public Info info() {
        return delegate.infoCmd().exec();
    }

    /**
     * 获取 Docker 版本信息。
     *
     * @return Version
     */
    public Version version() {
        return delegate.versionCmd().exec();
    }

    /**
     * 检查 Docker 是否可用。
     *
     * @return Void
     */
    public Void ping() {
        return delegate.pingCmd().exec();
    }

    @Override
    /** 关闭 */
    public void close() {
        try {
            delegate.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== Builder ====================

    public static class Builder {

        /** Docker 主机地址，默认 Unix socket */
        private String host = "unix:///var/run/docker.sock";

        /** 证书路径 */
        private String certPath;

        /** API 版本 */
        private String apiVersion = "1.40";

        /** 连接超时毫秒 */
        private int connectTimeout = 5000;

        /** 读取超时毫秒 */
        private int readTimeout = 30000;

        /**
         * 设置 Docker 主机地址。
         *
         * @param h 主机地址
         * @return this
         */
        public Builder host(String h) {
            this.host = h;
            return this;
        }

        /**
         * 设置证书路径。
         *
         * @param p 证书路径
         * @return this
         */
        public Builder certPath(String p) {
            this.certPath = p;
            return this;
        }

        /**
         * 设置 API 版本。
         *
         * @param v API 版本
         * @return this
         */
        public Builder apiVersion(String v) {
            this.apiVersion = v;
            return this;
        }

        /**
         * 设置连接超时。
         *
         * @param ms 毫秒数
         * @return this
         */
        public Builder connectTimeout(int ms) {
            this.connectTimeout = ms;
            return this;
        }

        /**
         * 设置读取超时。
         *
         * @param ms 毫秒数
         * @return this
         */
        public Builder readTimeout(int ms) {
            this.readTimeout = ms;
            return this;
        }

        /**
         * 设置 TCP 连接地址。
         *
         * @param host 主机
         * @param port 端口
         * @return this
         */
        public Builder tcp(String host, int port) {
            this.host = "tcp://" + host + ":" + port;
            return this;
        }

        /**
         * 构建 DockerClient。
         *
         * @return DockerClient 实例
         */
        public DockerClient build() {
            return new DockerClient(this);
        }
    }

    // ==================== 容器操作 ====================

    public static class ContainerOps {

        /** 父客户端 */
        private final DockerClient client;

        /**
         * 构造容器操作对象。
         *
         * @param client 父客户端
         */
        ContainerOps(DockerClient client) {
            this.client = client;
        }

        /**
         * 创建容器。
         *
         * @param name 容器名称
         * @return CreateCmd
         */
        public CreateCmd create(String name) {
            return new CreateCmd(client, name);
        }

        /**
         * 启动容器。
         *
         * @param id 容器 ID
         * @return StartCmd
         */
        public StartCmd start(String id) {
            return new StartCmd(client, id);
        }

        /**
         * 停止容器。
         *
         * @param id 容器 ID
         * @return StopCmd
         */
        public StopCmd stop(String id) {
            return new StopCmd(client, id);
        }

        /**
         * 重启容器。
         *
         * @param id 容器 ID
         * @return RestartCmd
         */
        public RestartCmd restart(String id) {
            return new RestartCmd(client, id);
        }

        /**
         * 删除容器。
         *
         * @param id 容器 ID
         * @return RemoveCmd
         */
        public RemoveCmd remove(String id) {
            return new RemoveCmd(client, id);
        }

        /**
         * 列出容器。
         *
         * @return ListCmd
         */
        public ListCmd list() {
            return new ListCmd(client);
        }

        /**
         * 检查容器。
         *
         * @param id 容器 ID
         * @return InspectCmd
         */
        public InspectCmd inspect(String id) {
            return new InspectCmd(client, id);
        }

        /**
         * 获取容器日志。
         *
         * @param id 容器 ID
         * @return LogsCmd
         */
        public LogsCmd logs(String id) {
            return new LogsCmd(client, id);
        }

        /**
         * 在容器中执行命令。
         *
         * @param id 容器 ID
         * @return ExecCmd
         */
        public ExecCmd exec(String id) {
            return new ExecCmd(client, id);
        }

        /**
         * 杀掉容器进程。
         *
         * @param id 容器 ID
         * @return KillCmd
         */
        public KillCmd kill(String id) {
            return new KillCmd(client, id);
        }

        // ---- 异步操作 ----

        /**
         * 异步创建容器。
         *
         * @param name     容器名称
         * @param config   配置回调
         * @return 容器 ID
         */
        public CompletableFuture<String> createAsync(String name, Consumer<CreateCmd> config) {
            CreateCmd cmd = create(name);
            config.accept(cmd);
            return CompletableFuture.completedFuture(cmd.exec());
        }

        /**
         * 异步启动容器。
         *
         * @param id 容器 ID
         * @return CompletableFuture
         */
        public CompletableFuture<Void> startAsync(String id) {
            return start(id).exec();
        }

        /**
         * 异步停止容器。
         *
         * @param id 容器 ID
         * @return CompletableFuture
         */
        public CompletableFuture<Void> stopAsync(String id) {
            return stop(id).exec();
        }

        /**
         * 异步删除容器。
         *
         * @param id 容器 ID
         * @return CompletableFuture
         */
        public CompletableFuture<Void> removeAsync(String id) {
            return remove(id).exec();
        }

        /**
         * 异步列出容器。
         *
         * @return CompletableFuture
         */
        public CompletableFuture<List<Container>> listAsync() {
            return list().exec();
        }

        /**
         * 异步等待容器退出。
         *
         * @param id             容器 ID
         * @param timeoutSeconds 超时秒数
         * @param onExit         退出回调，参数为退出码
         * @return CompletableFuture
         */
        public CompletableFuture<Void> waitAsync(String id, long timeoutSeconds, Consumer<Long> onExit) {
            return CompletableFuture.runAsync(() -> {
                try {
                    Integer status = wait(id, timeoutSeconds);
                    onExit.accept(status != null ? status : -1L);
                } catch (Exception e) {
                    onExit.accept(-1L);
                }
            });
        }

        /**
         * 同步等待容器退出。
         *
         * @param id             容器 ID
         * @param timeoutSeconds 超时秒数
         * @return 退出码
         * @throws Exception 等待异常
         */
        public Integer wait(String id, long timeoutSeconds) throws Exception {
            return client.delegate.waitContainerCmd(id)
                    .exec(new WaitContainerResultCallback())
                    .awaitStatusCode();
        }

        // ---- CreateCmd ----

        @Getter
        public static class CreateCmd {

            /** 父客户端 */
            private final DockerClient client;

            /** 容器名称 */
            private final String name;

            /** 镜像 */
            private String image;

            /** 环境变量 */
            private final Map<String, String> env = new ConcurrentHashMap<>();

            /** 标签 */
            private final Map<String, String> labels = new ConcurrentHashMap<>();

            /** 端口映射 */
            private final Map<Integer, String> portBindings = new ConcurrentHashMap<>();

            /** 存储卷绑定 */
            private final List<Bind> binds = new ArrayList<>();

            /** 重启策略，默认 no */
            private String restartPolicy = "no";

            /** 标准输入打开 */
            private boolean stdinOpen = false;

            /** 分配 TTY */
            private boolean tty = false;

            /** 工作目录 */
            private String workingDir;

            /** 运行用户 */
            private String user;

            /** 内存限制 */
            private Long memory;

            /** CPU 配额（纳核） */
            private Long nanoCpus;

            /** 启动命令（覆盖镜像默认 CMD） */
            private List<String> command;

            /** 入口点（覆盖镜像默认 ENTRYPOINT） */
            private List<String> entrypoint;

            /**
             * 构造创建命令。
             *
             * @param client 父客户端
             * @param name   容器名称
             */
            CreateCmd(DockerClient client, String name) {
                this.client = client;
                this.name = name;
            }

            /**
             * 设置镜像。
             *
             * @param img 镜像名称
             * @return this
             */
            public CreateCmd image(String img) {
                this.image = img;
                return this;
            }

            /**
             * 设置环境变量。
             *
             * @param k 键
             * @param v 值
             * @return this
             */
            public CreateCmd env(String k, String v) {
                env.put(k, v);
                return this;
            }

            /**
             * 批量设置环境变量。
             *
             * @param e 环境变量表
             * @return this
             */
            public CreateCmd env(Map<String, String> e) {
                env.putAll(e);
                return this;
            }

            /**
             * 添加端口映射。
             *
             * @param hostPort      主机端口
             * @param containerPort 容器端口
             * @return this
             */
            public CreateCmd port(int hostPort, int containerPort) {
                portBindings.put(hostPort, containerPort + "/tcp");
                return this;
            }

            /**
             * 添加存储卷绑定。
             *
             * @param host      主机路径
             * @param container 容器路径
             * @return this
             */
            public CreateCmd volume(String host, String container) {
                binds.add(Bind.parse(host + ":" + container));
                return this;
            }

            /**
             * 设置标签。
             *
             * @param k 键
             * @param v 值
             * @return this
             */
            public CreateCmd label(String k, String v) {
                labels.put(k, v);
                return this;
            }

            /**
             * 设置重启策略。
             *
             * @param p 策略名称（no / always / on-failure / unless-stopped）
             * @return this
             */
            public CreateCmd restart(String p) {
                this.restartPolicy = p;
                return this;
            }

            /**
             * 设置标准输入打开。
             *
             * @param v 是否打开
             * @return this
             */
            public CreateCmd stdinOpen(boolean v) {
                this.stdinOpen = v;
                return this;
            }

            /**
             * 设置 TTY。
             *
             * @param v 是否分配 TTY
             * @return this
             */
            public CreateCmd tty(boolean v) {
                this.tty = v;
                return this;
            }

            /**
             * 设置工作目录。
             *
             * @param d 工作目录路径
             * @return this
             */
            public CreateCmd workingDir(String d) {
                this.workingDir = d;
                return this;
            }

            /**
             * 设置运行用户。
             *
             * @param u 用户名或 UID
             * @return this
             */
            public CreateCmd user(String u) {
                this.user = u;
                return this;
            }

            /**
             * 设置内存限制。
             *
             * @param bytes 字节数
             * @return this
             */
            public CreateCmd memory(long bytes) {
                this.memory = bytes;
                return this;
            }

            /**
             * 设置 CPU 核数。
             *
             * @param n CPU 核数
             * @return this
             */
            public CreateCmd cpus(long n) {
                this.nanoCpus = n * 1_000_000_000L;
                return this;
            }

            /**
             * 设置容器启动命令，覆盖镜像默认 CMD。
             *
             * @param c 命令及参数
             * @return this
             */
            public CreateCmd cmd(String... c) {
                this.command = c == null ? null : List.of(c);
                return this;
            }

            /**
             * 设置容器入口点，覆盖镜像默认 ENTRYPOINT。
             *
             * @param e 入口点及参数
             * @return this
             */
            public CreateCmd entrypoint(String... e) {
                this.entrypoint = e == null ? null : List.of(e);
                return this;
            }

            /**
             * 执行创建，返回容器 ID。
             *
             * @return 容器 ID
             */
            public String exec() {
                CreateContainerCmd create = client.delegate.createContainerCmd(image)
                        .withName(name)
                        .withLabels(labels)
                        .withRestartPolicy(RestartPolicy.parse(restartPolicy))
                        .withStdinOpen(stdinOpen)
                        .withTty(tty);
                if (workingDir != null) {
                    create.withWorkingDir(workingDir);
                }
                if (user != null) {
                    create.withUser(user);
                }
                if (memory != null) {
                    create.withMemory(memory);
                }
                if (!env.isEmpty()) {
                    List<String> envList = env.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .toList();
                    create.withEnv(envList);
                }
                if (command != null && !command.isEmpty()) {
                    create.withCmd(command);
                }
                if (entrypoint != null && !entrypoint.isEmpty()) {
                    create.withEntrypoint(entrypoint);
                }
                if (!portBindings.isEmpty()) {
                    Map<String, List<Map<String, String>>> primitive = new HashMap<>();
                    for (Map.Entry<Integer, String> e : portBindings.entrySet()) {
                        List<Map<String, String>> bindings = new ArrayList<>();
                        Map<String, String> binding = new HashMap<>();
                        binding.put("HostIp", "0.0.0.0");
                        binding.put("HostPort", String.valueOf(e.getKey()));
                        bindings.add(binding);
                        primitive.put(e.getValue(), bindings);
                    }
                    create.withPortBindings(Ports.fromPrimitive(primitive));
                }
                if (!binds.isEmpty()) {
                    create.withBinds(binds.toArray(new Bind[0]));
                }
                String id = create.exec().getId();
                log.info("容器创建: {} -> {}", name, id.substring(0, 12));
                return id;
            }
        }

        // ---- StartCmd ----

        @Getter
        public static class StartCmd {

            /** 父客户端 */
            private final DockerClient client;

            /** 容器 ID */
            private final String id;

            /**
             * 构造启动命令。
             *
             * @param client 父客户端
             * @param id     容器 ID
             */
            StartCmd(DockerClient client, String id) {
                this.client = client;
                this.id = id;
            }

            /**
             * 同步执行启动。
             */
            public void execSync() {
                client.delegate.startContainerCmd(id).exec();
                log.info("容器启动: {}", id.substring(0, 12));
            }

            /**
             * 异步执行启动。
             *
             * @return CompletableFuture
             */
            public CompletableFuture<Void> exec() {
                return CompletableFuture.runAsync(this::execSync);
            }

            /**
             * 执行启动并回调。
             *
             * @param callback 完成回调
             */
            public void exec(Consumer<Void> callback) {
                exec().thenAcceptAsync(callback);
            }
        }

        // ---- StopCmd ----

        @Getter
        public static class StopCmd {

            /** 父客户端 */
            private final DockerClient client;

            /** 容器 ID */
            private final String id;

            /** 等待超时秒数 */
            private int timeout = 10;

            /**
             * 构造停止命令。
             *
             * @param client 父客户端
             * @param id     容器 ID
             */
            StopCmd(DockerClient client, String id) {
                this.client = client;
                this.id = id;
            }

            /**
             * 设置等待超时。
             *
             * @param t 超时秒数
             * @return this
             */
            public StopCmd timeout(int t) {
                this.timeout = t;
                return this;
            }

            /**
             * 同步执行停止。
             */
            public void execSync() {
                client.delegate.stopContainerCmd(id).withTimeout(timeout).exec();
                log.info("容器停止: {}", id.substring(0, 12));
            }

            /**
             * 异步执行停止。
             *
             * @return CompletableFuture
             */
            public CompletableFuture<Void> exec() {
                return CompletableFuture.runAsync(this::execSync);
            }

            /**
             * 执行停止并回调。
             *
             * @param callback 完成回调
             */
            public void exec(Consumer<Void> callback) {
                exec().thenAcceptAsync(callback);
            }
        }

        // ---- RestartCmd ----

        @Getter
        public static class RestartCmd {

            /** 父客户端 */
            private final DockerClient client;

            /** 容器 ID */
            private final String id;

            /** 等待超时秒数 */
            private int timeout = 10;

            /**
             * 构造重启命令。
             *
             * @param client 父客户端
             * @param id     容器 ID
             */
            RestartCmd(DockerClient client, String id) {
                this.client = client;
                this.id = id;
            }

            /**
             * 设置等待超时。
             *
             * @param t 超时秒数
             * @return this
             */
            public RestartCmd timeout(int t) {
                this.timeout = t;
                return this;
            }

            /**
             * 同步执行重启。
             */
            public void execSync() {
                client.delegate.restartContainerCmd(id).withTimeout(timeout).exec();
            }

            /**
             * 异步执行重启。
             *
             * @return CompletableFuture
             */
            public CompletableFuture<Void> exec() {
                return CompletableFuture.runAsync(this::execSync);
            }

            /**
             * 执行重启并回调。
             *
             * @param callback 完成回调
             */
            public void exec(Consumer<Void> callback) {
                exec().thenAcceptAsync(callback);
            }
        }

        // ---- RemoveCmd ----

        @Getter
        public static class RemoveCmd {

            /** 父客户端 */
            private final DockerClient client;

            /** 容器 ID */
            private final String id;

            /** 强制删除 */
            private boolean force = false;

            /** 删除关联卷 */
            private boolean removeVolumes = false;

            /**
             * 构造删除命令。
             *
             * @param client 父客户端
             * @param id     容器 ID
             */
            RemoveCmd(DockerClient client, String id) {
                this.client = client;
                this.id = id;
            }

            /**
             * 设置强制删除。
             *
             * @param f 是否强制
             * @return this
             */
            public RemoveCmd force(boolean f) {
                this.force = f;
                return this;
            }

            /**
             * 设置删除关联卷。
             *
             * @param v 是否删除卷
             * @return this
             */
            public RemoveCmd removeVolumes(boolean v) {
                this.removeVolumes = v;
                return this;
            }

            /**
             * 同步执行删除。
             */
            public void execSync() {
                client.delegate.removeContainerCmd(id)
                        .withForce(force)
                        .withRemoveVolumes(removeVolumes)
                        .exec();
            }

            /**
             * 异步执行删除。
             *
             * @return CompletableFuture
             */
            public CompletableFuture<Void> exec() {
                return CompletableFuture.runAsync(this::execSync);
            }

            /**
             * 执行删除并回调。
             *
             * @param callback 完成回调
             */
            public void exec(Consumer<Void> callback) {
                exec().thenAcceptAsync(callback);
            }
        }

        // ---- ListCmd ----

        @Getter
        public static class ListCmd {

            /** 父客户端 */
            private final DockerClient client;

            /** 是否显示已停止容器 */
            private boolean all = false;

            /** 过滤条件 */
            private List<String> filters = new ArrayList<>();

            /**
             * 构造列表命令。
             *
             * @param client 父客户端
             */
            ListCmd(DockerClient client) {
                this.client = client;
            }

            /**
             * 设置是否显示所有容器。
             *
             * @param a 是否显示全部
             * @return this
             */
            public ListCmd all(boolean a) {
                this.all = a;
                return this;
            }

            /**
             * 添加过滤条件。
             *
             * @param f 过滤条件
             * @return this
             */
            public ListCmd filter(String... f) {
                filters.addAll(List.of(f));
                return this;
            }

            /**
             * 同步执行列表查询。
             *
             * @return 容器列表
             */
            public List<Container> execSync() {
                return client.delegate.listContainersCmd()
                        .withShowAll(all)
                        .withNameFilter(filters)
                        .exec();
            }

            /**
             * 异步执行列表查询。
             *
             * @return CompletableFuture
             */
            public CompletableFuture<List<Container>> exec() {
                return CompletableFuture.supplyAsync(() -> execSync());
            }

            /**
             * 执行列表查询并回调。
             *
             * @param callback 结果回调
             */
            public void exec(Consumer<List<Container>> callback) {
                exec().thenAcceptAsync(callback);
            }
        }

        // ---- InspectCmd ----

        @Getter
        public static class InspectCmd {

            /** 父客户端 */
            private final DockerClient client;

            /** 容器 ID */
            private final String id;

            /**
             * 构造检查命令。
             *
             * @param client 父客户端
             * @param id     容器 ID
             */
            InspectCmd(DockerClient client, String id) {
                this.client = client;
                this.id = id;
            }

            /**
             * 同步执行检查。
             *
             * @return InspectContainerResponse
             */
            public InspectContainerResponse execSync() {
                return client.delegate.inspectContainerCmd(id).exec();
            }

            /**
             * 异步执行检查。
             *
             * @return CompletableFuture
             */
            public CompletableFuture<InspectContainerResponse> exec() {
                return CompletableFuture.supplyAsync(this::execSync);
            }

            /**
             * 执行检查并回调。
             *
             * @param callback 结果回调
             */
            public void exec(Consumer<InspectContainerResponse> callback) {
                exec().thenAcceptAsync(callback);
            }
        }

        // ---- LogsCmd ----

        @Getter
        public static class LogsCmd {

            /** 父客户端 */
            private final DockerClient client;

            /** 容器 ID */
            private final String id;

            /** 获取标准输出 */
            private boolean stdout = true;

            /** 获取标准错误 */
            private boolean stderr = true;

            /** 尾部行数 */
            private int tail = 100;

            /**
             * 构造日志命令。
             *
             * @param client 父客户端
             * @param id     容器 ID
             */
            LogsCmd(DockerClient client, String id) {
                this.client = client;
                this.id = id;
            }

            /**
             * 设置尾部行数。
             *
             * @param t 行数
             * @return this
             */
            public LogsCmd tail(int t) {
                this.tail = t;
                return this;
            }

            /**
             * 同步获取日志。
             *
             * @return 日志文本
             */
            public String execSync() {
                CountDownLatch latch = new CountDownLatch(1);
                StringBuilder sb = new StringBuilder();
                client.delegate.logContainerCmd(id)
                        .withStdOut(stdout)
                        .withStdErr(stderr)
                        .withTail(tail)
                        .exec(new com.github.dockerjava.api.async.ResultCallback<Frame>() {
                            @Override
                            /** OnNext */
                            public void onNext(Frame frame) {
                                sb.append(new String(frame.getPayload(), UTF_8));
                            }

                            @Override
                            /** On开始 */
                            public void onStart(java.io.Closeable closeable) {
                            }

                            @Override
                            /** OnComplete */
                            public void onComplete() {
                                latch.countDown();
                            }

                            @Override
                            /** On记录错误 */
                            public void onError(Throwable throwable) {
                                latch.countDown();
                            }

                            @Override
                            /** 关闭 */
                            public void close() {
                            }
                        });
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return sb.toString();
            }

            /**
             * 异步获取日志。
             *
             * @return CompletableFuture
             */
            public CompletableFuture<String> exec() {
                return CompletableFuture.supplyAsync(this::execSync);
            }

            /**
             * 获取日志并回调。
             *
             * @param callback 结果回调
             */
            public void exec(Consumer<String> callback) {
                exec().thenAcceptAsync(callback);
            }
        }

        // ---- ExecCmd ----

        @Getter
        public static class ExecCmd {

            /** 父客户端 */
            private final DockerClient client;

            /** 容器 ID */
            private final String id;

            /** 执行的命令 */
            private String[] cmd;

            /**
             * 构造执行命令。
             *
             * @param client 父客户端
             * @param id     容器 ID
             */
            ExecCmd(DockerClient client, String id) {
                this.client = client;
                this.id = id;
            }

            /**
             * 设置执行的命令。
             *
             * @param cmd 命令和参数
             * @return this
             */
            public ExecCmd command(String... cmd) {
                this.cmd = cmd;
                return this;
            }

            /**
             * 同步执行命令。
             *
             * @return 命令输出
             */
            public String execSync() {
                String execId = client.delegate.execCreateCmd(id)
                        .withCmd(cmd)
                        .withAttachStdout(true)
                        .withAttachStderr(true)
                        .exec()
                        .getId();
                CountDownLatch latch = new CountDownLatch(1);
                StringBuilder sb = new StringBuilder();
                client.delegate.execStartCmd(execId)
                        .exec(new com.github.dockerjava.api.async.ResultCallback<Frame>() {
                            @Override
                            /** OnNext */
                            public void onNext(Frame frame) {
                                sb.append(new String(frame.getPayload(), UTF_8));
                            }

                            @Override
                            /** On开始 */
                            public void onStart(java.io.Closeable closeable) {
                            }

                            @Override
                            /** OnComplete */
                            public void onComplete() {
                                latch.countDown();
                            }

                            @Override
                            /** On记录错误 */
                            public void onError(Throwable throwable) {
                                latch.countDown();
                            }

                            @Override
                            /** 关闭 */
                            public void close() {
                            }
                        });
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return sb.toString();
            }

            /**
             * 异步执行命令。
             *
             * @return CompletableFuture
             */
            public CompletableFuture<String> exec() {
                return CompletableFuture.supplyAsync(this::execSync);
            }

            /**
             * 执行命令并回调。
             *
             * @param callback 结果回调
             */
            public void exec(Consumer<String> callback) {
                exec().thenAcceptAsync(callback);
            }
        }

        // ---- KillCmd ----

        @Getter
        public static class KillCmd {

            /** 父客户端 */
            private final DockerClient client;

            /** 容器 ID */
            private final String id;

            /** 信号名称 */
            private String signal = "KILL";

            /**
             * 构造杀掉命令。
             *
             * @param client 父客户端
             * @param id     容器 ID
             */
            KillCmd(DockerClient client, String id) {
                this.client = client;
                this.id = id;
            }

            /**
             * 设置信号。
             *
             * @param s 信号名称
             * @return this
             */
            public KillCmd signal(String s) {
                this.signal = s;
                return this;
            }

            /**
             * 同步执行杀掉。
             */
            public void execSync() {
                client.delegate.killContainerCmd(id).withSignal(signal).exec();
            }

            /**
             * 异步执行杀掉。
             *
             * @return CompletableFuture
             */
            public CompletableFuture<Void> exec() {
                return CompletableFuture.runAsync(this::execSync);
            }

            /**
             * 执行杀掉并回调。
             *
             * @param callback 完成回调
             */
            public void exec(Consumer<Void> callback) {
                exec().thenAcceptAsync(callback);
            }
        }
    }

    // ==================== 镜像操作 ====================

    public static class ImageOps {

        /** 父客户端 */
        private final DockerClient client;

        /**
         * 构造镜像操作对象。
         *
         * @param client 父客户端
         */
        ImageOps(DockerClient client) {
            this.client = client;
        }

        /**
         * 拉取镜像。
         *
         * @param name 镜像名称
         * @return PullImageCmd
         */
        public PullImageCmd pull(String name) {
            return new PullImageCmd(client, name);
        }

        /**
         * 列出镜像。
         *
         * @return ListImageCmd
         */
        public ListImageCmd list() {
            return new ListImageCmd(client);
        }

        /**
         * 删除镜像。
         *
         * @param id 镜像 ID
         * @return RemoveImageCmd
         */
        public RemoveImageCmd remove(String id) {
            return new RemoveImageCmd(client, id);
        }

        /**
         * 检查镜像。
         *
         * @param id 镜像 ID
         * @return InspectImageResponse
         */
        public InspectImageResponse inspect(String id) {
            return client.delegate.inspectImageCmd(id).exec();
        }

        // ---- 异步操作 ----

        /**
         * 异步列出镜像。
         *
         * @return CompletableFuture
         */
        public CompletableFuture<List<Image>> listAsync() {
            return CompletableFuture.supplyAsync(() -> list().exec());
        }

        /**
         * 异步拉取镜像。
         *
         * @param name       镜像名称
         * @param tag        标签
         * @param onProgress 进度回调
         * @return CompletableFuture
         */
        public CompletableFuture<Void> pullAsync(String name, String tag, Consumer<String> onProgress) {
            return CompletableFuture.runAsync(() -> pull(name).tag(tag).execAsync(onProgress));
        }

        /**
         * 异步删除镜像。
         *
         * @param id 镜像 ID
         * @return CompletableFuture
         */
        public CompletableFuture<Void> removeAsync(String id) {
            return CompletableFuture.runAsync(() -> remove(id).exec());
        }

        @Getter
        public static class PullImageCmd {

            /** 父客户端 */
            private final DockerClient client;

            /** 镜像名称 */
            private final String name;

            /** 标签 */
            private String tag = "latest";

            /**
             * 构造拉取命令。
             *
             * @param client 父客户端
             * @param name   镜像名称
             */
            PullImageCmd(DockerClient client, String name) {
                this.client = client;
                this.name = name;
            }

            /**
             * 设置标签。
             *
             * @param t 标签
             * @return this
             */
            public PullImageCmd tag(String t) {
                this.tag = t;
                return this;
            }

            /**
             * 同步拉取镜像。
             */
            public void exec() {
                try {
                    client.delegate.pullImageCmd(name + ":" + tag)
                            .exec(new PullImageResultCallback())
                            .awaitCompletion();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }

            /**
             * 异步拉取镜像（带进度回调）。
             *
             * @param onProgress 进度回调
             */
            public void execAsync(Consumer<String> onProgress) {
                try {
                    client.delegate.pullImageCmd(name + ":" + tag)
                            .exec(new PullImageResultCallback() {
                                @Override
                                /** OnNext */
                                public void onNext(PullResponseItem item) {
                                    if (item.getStatus() != null) {
                                        onProgress.accept(item.getStatus());
                                    }
                                }
                            })
                            .awaitCompletion();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }

            /**
             * 异步拉取，返回进度流。
             *
             * @param onProgress 进度回调
             * @param onError    错误回调
             * @return CompletableFuture
             */
            public CompletableFuture<Void> execAsyncFuture(Consumer<String> onProgress, Consumer<Throwable> onError) {
                return CompletableFuture.runAsync(() -> {
                    try {
                        execAsync(onProgress);
                    } catch (Exception e) {
                        onError.accept(e);
                    }
                });
            }
        }

        @Getter
        public static class ListImageCmd {

            /** 父客户端 */
            private final DockerClient client;

            /**
             * 构造列表命令。
             *
             * @param client 父客户端
             */
            ListImageCmd(DockerClient client) {
                this.client = client;
            }

            /**
             * 执行列表查询。
             *
             * @return 镜像列表
             */
            public List<Image> exec() {
                return client.delegate.listImagesCmd().exec();
            }
        }

        @Getter
        public static class RemoveImageCmd {

            /** 父客户端 */
            private final DockerClient client;

            /** 镜像 ID */
            private final String id;

            /** 强制删除 */
            private boolean force = false;

            /**
             * 构造删除命令。
             *
             * @param client 父客户端
             * @param id     镜像 ID
             */
            RemoveImageCmd(DockerClient client, String id) {
                this.client = client;
                this.id = id;
            }

            /**
             * 设置强制删除。
             *
             * @param f 是否强制
             * @return this
             */
            public RemoveImageCmd force(boolean f) {
                this.force = f;
                return this;
            }

            /**
             * 执行删除。
             */
            public void exec() {
                client.delegate.removeImageCmd(id).withForce(force).exec();
            }
        }
    }

    // ==================== 网络操作 ====================

    public static class NetworkOps {

        /** 父客户端 */
        private final DockerClient client;

        /**
         * 构造网络操作对象。
         *
         * @param client 父客户端
         */
        NetworkOps(DockerClient client) {
            this.client = client;
        }

        /**
         * 创建网络。
         *
         * @param name 网络名称
         * @return CreateNetworkCmd
         */
        public CreateNetworkCmd create(String name) {
            return new CreateNetworkCmd(client, name);
        }

        /**
         * 列出网络。
         *
         * @return ListNetworkCmd
         */
        public ListNetworkCmd list() {
            return new ListNetworkCmd(client);
        }

        /**
         * 删除网络。
         *
         * @param id 网络 ID
         * @return RemoveNetworkCmd
         */
        public RemoveNetworkCmd remove(String id) {
            return new RemoveNetworkCmd(client, id);
        }

        /**
         * 连接容器到网络。
         *
         * @param networkId   网络 ID
         * @param containerId 容器 ID
         */
        public void connect(String networkId, String containerId) {
            client.delegate.connectToNetworkCmd()
                    .withNetworkId(networkId)
                    .withContainerId(containerId)
                    .exec();
        }

        /**
         * 断开容器与网络的连接。
         *
         * @param networkId   网络 ID
         * @param containerId 容器 ID
         */
        public void disconnect(String networkId, String containerId) {
            client.delegate.disconnectFromNetworkCmd()
                    .withNetworkId(networkId)
                    .withContainerId(containerId)
                    .exec();
        }

        /**
         * 检查网络详情。
         *
         * @param id 网络 ID
         * @return Network
         */
        public Network inspect(String id) {
            return client.delegate.inspectNetworkCmd().withNetworkId(id).exec();
        }

        // ---- 异步操作 ----

        /**
         * 异步列出网络。
         *
         * @return CompletableFuture
         */
        public CompletableFuture<List<Network>> listAsync() {
            return CompletableFuture.supplyAsync(() -> list().exec());
        }

        /**
         * 异步创建网络。
         *
         * @param name   网络名称
         * @param config 配置回调
         * @return CompletableFuture
         */
        public CompletableFuture<String> createAsync(String name, Consumer<CreateNetworkCmd> config) {
            CreateNetworkCmd cmd = create(name);
            config.accept(cmd);
            return CompletableFuture.supplyAsync(cmd::exec);
        }

        /**
         * 异步删除网络。
         *
         * @param id 网络 ID
         * @return CompletableFuture
         */
        public CompletableFuture<Void> removeAsync(String id) {
            return CompletableFuture.runAsync(() -> remove(id).exec());
        }

        @Getter
        public static class CreateNetworkCmd {

            /** 父客户端 */
            private final DockerClient client;

            /** 网络名称 */
            private final String name;

            /** 驱动类型 */
            private String driver = "bridge";

            /** 内部网络 */
            private boolean internal = false;

            /** 选项 */
            private final Map<String, String> options = new ConcurrentHashMap<>();

            /** 标签 */
            private final Map<String, String> labels = new ConcurrentHashMap<>();

            /**
             * 构造创建网络命令。
             *
             * @param client 父客户端
             * @param name   网络名称
             */
            CreateNetworkCmd(DockerClient client, String name) {
                this.client = client;
                this.name = name;
            }

            /**
             * 设置驱动。
             *
             * @param d 驱动类型
             * @return this
             */
            public CreateNetworkCmd driver(String d) {
                this.driver = d;
                return this;
            }

            /**
             * 设置网络类型。
             *
             * @param v 是否内部网络
             * @return this
             */
            public CreateNetworkCmd internal(boolean v) {
                this.internal = v;
                return this;
            }

            /**
             * 添加选项。
             *
             * @param k 键
             * @param v 值
             * @return this
             */
            public CreateNetworkCmd option(String k, String v) {
                options.put(k, v);
                return this;
            }

            /**
             * 添加标签。
             *
             * @param k 键
             * @param v 值
             * @return this
             */
            public CreateNetworkCmd label(String k, String v) {
                labels.put(k, v);
                return this;
            }

            /**
             * 执行创建。
             *
             * @return 网络 ID
             */
            public String exec() {
                return client.delegate.createNetworkCmd()
                        .withName(name)
                        .withDriver(driver)
                        .withInternal(internal)
                        .withOptions(options)
                        .withLabels(labels)
                        .exec()
                        .getId();
            }
        }

        @Getter
        public static class ListNetworkCmd {

            /** 父客户端 */
            private final DockerClient client;

            /**
             * 构造列表命令。
             *
             * @param client 父客户端
             */
            ListNetworkCmd(DockerClient client) {
                this.client = client;
            }

            /**
             * 执行列表查询。
             *
             * @return 网络列表
             */
            public List<Network> exec() {
                return client.delegate.listNetworksCmd().exec();
            }
        }

        @Getter
        public static class RemoveNetworkCmd {

            /** 父客户端 */
            private final DockerClient client;

            /** 网络 ID */
            private final String id;

            /**
             * 构造删除网络命令。
             *
             * @param client 父客户端
             * @param id     网络 ID
             */
            RemoveNetworkCmd(DockerClient client, String id) {
                this.client = client;
                this.id = id;
            }

            /**
             * 执行删除。
             */
            public void exec() {
                client.delegate.removeNetworkCmd(id).exec();
            }
        }
    }

    // ==================== 卷操作 ====================

    public static class VolumeOps {

        /** 父客户端 */
        private final DockerClient client;

        /**
         * 构造卷操作对象。
         *
         * @param client 父客户端
         */
        VolumeOps(DockerClient client) {
            this.client = client;
        }

        /**
         * 创建卷。
         *
         * @param name 卷名称
         * @return CreateVolumeCmd
         */
        public CreateVolumeCmd create(String name) {
            return new CreateVolumeCmd(client, name);
        }

        /**
         * 列出卷。
         *
         * @return ListVolumeCmd
         */
        public ListVolumeCmd list() {
            return new ListVolumeCmd(client);
        }

        /**
         * 删除卷。
         *
         * @param name 卷名称
         * @return RemoveVolumeCmd
         */
        public RemoveVolumeCmd remove(String name) {
            return new RemoveVolumeCmd(client, name);
        }

        /**
         * 检查卷详情。
         *
         * @param name 卷名称
         * @return InspectVolumeResponse
         */
        public InspectVolumeResponse inspect(String name) {
            return client.delegate.inspectVolumeCmd(name).exec();
        }

        // ---- 异步操作 ----

        /**
         * 异步列出卷。
         *
         * @return CompletableFuture
         */
        public CompletableFuture<ListVolumesResponse> listAsync() {
            return CompletableFuture.supplyAsync(() -> list().exec());
        }

        /**
         * 异步创建卷。
         *
         * @param name   卷名称
         * @param config 配置回调
         * @return CompletableFuture
         */
        public CompletableFuture<String> createAsync(String name, Consumer<CreateVolumeCmd> config) {
            CreateVolumeCmd cmd = create(name);
            config.accept(cmd);
            return CompletableFuture.supplyAsync(cmd::exec);
        }

        /**
         * 异步删除卷。
         *
         * @param name 卷名称
         * @return CompletableFuture
         */
        public CompletableFuture<Void> removeAsync(String name) {
            return CompletableFuture.runAsync(() -> remove(name).exec());
        }

        @Getter
        public static class CreateVolumeCmd {

            /** 父客户端 */
            private final DockerClient client;

            /** 卷名称 */
            private final String name;

            /** 驱动类型 */
            private String driver = "local";

            /** 驱动选项 */
            private final Map<String, String> driverOpts = new ConcurrentHashMap<>();

            /** 标签 */
            private final Map<String, String> labels = new ConcurrentHashMap<>();

            /**
             * 构造创建卷命令。
             *
             * @param client 父客户端
             * @param name   卷名称
             */
            CreateVolumeCmd(DockerClient client, String name) {
                this.client = client;
                this.name = name;
            }

            /**
             * 设置驱动。
             *
             * @param d 驱动类型
             * @return this
             */
            public CreateVolumeCmd driver(String d) {
                this.driver = d;
                return this;
            }

            /**
             * 添加驱动选项。
             *
             * @param k 键
             * @param v 值
             * @return this
             */
            public CreateVolumeCmd driverOpt(String k, String v) {
                driverOpts.put(k, v);
                return this;
            }

            /**
             * 添加标签。
             *
             * @param k 键
             * @param v 值
             * @return this
             */
            public CreateVolumeCmd label(String k, String v) {
                labels.put(k, v);
                return this;
            }

            /**
             * 执行创建。
             *
             * @return 卷名称
             */
            public String exec() {
                return client.delegate.createVolumeCmd()
                        .withName(name)
                        .withDriver(driver)
                        .withDriverOpts(driverOpts)
                        .withLabels(labels)
                        .exec()
                        .getName();
            }
        }

        @Getter
        public static class ListVolumeCmd {

            /** 父客户端 */
            private final DockerClient client;

            /**
             * 构造列表命令。
             *
             * @param client 父客户端
             */
            ListVolumeCmd(DockerClient client) {
                this.client = client;
            }

            /**
             * 执行列表查询。
             *
             * @return ListVolumesResponse
             */
            public ListVolumesResponse exec() {
                return client.delegate.listVolumesCmd().exec();
            }
        }

        @Getter
        public static class RemoveVolumeCmd {

            /** 父客户端 */
            private final DockerClient client;

            /** 卷名称 */
            private final String name;

            /**
             * 构造删除卷命令。
             *
             * @param client 父客户端
             * @param name   卷名称
             */
            RemoveVolumeCmd(DockerClient client, String name) {
                this.client = client;
                this.name = name;
            }

            /**
             * 执行删除。
             */
            public void exec() {
                client.delegate.removeVolumeCmd(name).exec();
            }
        }
    }

    /**
     * Docker 客户端异常。
     */
    public static class DockerClientException extends RuntimeException {

        /**
         * 构造 Docker 客户端异常。
         *
         * @param msg   错误消息
         * @param cause 原因
         */
        public DockerClientException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
