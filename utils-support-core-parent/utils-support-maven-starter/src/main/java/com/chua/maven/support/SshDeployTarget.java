package com.chua.maven.support;

import java.util.List;

/**
 * SSH/SFTP 远程部署目标。
 * <p>
 * 基于 ssh-starter 的 SftpClient 实现远程服务器部署。
 * 需要类路径中存在 SftpClient（ssh-starter 依赖需手动提供）。
 * </p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * SshDeployTarget target = new SshDeployTarget("192.168.1.100", 22, "root", "password", "/opt/app");
 * target.connect();
 * target.upload("target/myapp.jar", "myapp.jar");
 * target.disconnect();
 *
 * // 或通过传输客户端
 * MavenClient.create()
 *     .projectPath("pom.xml")
 *     .goal("clean", "package")
 *     .compileAndDeploy()
 *     .deployTo(new SshDeployTarget(host, port, user, pass, "/opt/app"));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SshDeployTarget implements MavenDeployTarget {

    /** 日志 */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SshDeployTarget.class);

    /**
     * SSH 主机
     */
    private final String host;

    /**
     * SSH 端口
     */
    private final int port;

    /**
     * 用户名
     */
    private final String username;

    /**
     * 密码
     */
    private final String password;

    /**
     * 远程部署根目录
     */
    private final String remoteRoot;

    /**
     * SFTP 客户端
     */
    private Object sftpClient;

    /**
     * 是否已连接
     */
    private boolean ready;

    /**
     * 部署回调
     */
    private MavenDeployCallback callback;

    /**
     * 构造 SSH 部署目标。
     *
     * @param host       主机地址
     * @param port       端口
     * @param username   用户名
     * @param password   密码
     * @param remoteRoot 远程根目录
     */
    public SshDeployTarget(String host, int port, String username, String password, String remoteRoot) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.remoteRoot = remoteRoot;
    }

    /**
     * 构造 SSH 部署目标（默认 22 端口）。
     *
     * @param host       主机地址
     * @param username   用户名
     * @param password   密码
     * @param remoteRoot 远程根目录
     */
    public SshDeployTarget(String host, String username, String password, String remoteRoot) {
        this(host, 22, username, password, remoteRoot);
    }

    @Override
    /** Name */
    public String name() {
        return "SSH:" + host + ":" + port + " -> " + remoteRoot;
    }

    @Override
    /** 是否Ready */
    public boolean isReady() {
        return ready;
    }

    @Override
    /** 连接 */
    public void connect() {
        try {
            // 通过 Class.forName 动态加载避免编译时强依赖
            Class<?> builderClass = Class.forName("com.chua.ssh.support.client.SftpClient$Builder");
            Class<?> sftpClientClass = Class.forName("com.chua.ssh.support.client.SftpClient");

            // SftpClient.builder()
            Object builder = sftpClientClass.getMethod("builder").invoke(null);

            // .host().port().username().password().build()
            builderClass.getMethod("host", String.class).invoke(builder, host);
            builderClass.getMethod("port", int.class).invoke(builder, port);
            builderClass.getMethod("username", String.class).invoke(builder, username);
            builderClass.getMethod("password", String.class).invoke(builder, password);
            Object client = builderClass.getMethod("build").invoke(builder);

            // .connect()
            sftpClient = sftpClientClass.getMethod("connect").invoke(client);

            ready = true;
            log.info("[maven] SSH 部署目标连接成功: {}@{}:{} -> {}", username, host, port, remoteRoot);
        } catch (ClassNotFoundException e) {
            throw new MavenDeployException(
                    "SSH 部署需要依赖 utils-support-ssh-starter，请添加该依赖到 classpath", e);
        } catch (Exception e) {
            throw new MavenDeployException(
                    "SSH 连接失败: " + host + ":" + port, e);
        }
    }

    @Override
    /** Upload */
    public void upload(String localPath, String targetPath) {
        ensureReady();
        try {
            // sftpClient.upload().local(localPath).remote(targetPath).exec()
            Object uploadOp = sftpClient.getClass().getMethod("upload").invoke(sftpClient);
            uploadOp.getClass().getMethod("local", String.class).invoke(uploadOp, localPath);
            String remote = remoteRoot + "/" + targetPath;
            uploadOp.getClass().getMethod("remote", String.class).invoke(uploadOp, remote);
            uploadOp.getClass().getMethod("exec").invoke(uploadOp);

            log.info("[maven] SSH 上传: {} -> {}:{}", localPath, host, remote);
        } catch (Exception e) {
            throw new MavenDeployException(
                    "SSH 文件上传失败: " + localPath + " -> " + host, e);
        }
    }

    @Override
    /** 创建Directory */
    public void createDirectory(String path) {
        if (path == null || ".".equals(path)) {
            return;
        }
        ensureReady();
        try {
            String remote = remoteRoot + "/" + path;
            // sftpClient.mkdir().path(remote).exec()
            Object mkdirOp = sftpClient.getClass().getMethod("mkdir").invoke(sftpClient);
            mkdirOp.getClass().getMethod("path", String.class).invoke(mkdirOp, remote);
            mkdirOp.getClass().getMethod("recursive", boolean.class).invoke(mkdirOp, true);
            mkdirOp.getClass().getMethod("exec").invoke(mkdirOp);
        } catch (Exception e) {
            log.debug("[maven] SSH 创建目录异常（可能已存在）: {}", e.getMessage());
        }
    }

    @Override
    /** 是否存在 */
    public boolean exists(String path) {
        try {
            String remote = remoteRoot + "/" + path;
            Object statOp = sftpClient.getClass().getMethod("stat").invoke(sftpClient);
            statOp.getClass().getMethod("path", String.class).invoke(statOp, remote);
            statOp.getClass().getMethod("exec").invoke(statOp);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    /** 删除 */
    public void delete(String path) {
        ensureReady();
        try {
            String remote = remoteRoot + "/" + path;
            // sftpClient.rm().path(remote).exec()
            Object rmOp = sftpClient.getClass().getMethod("rm").invoke(sftpClient);
            rmOp.getClass().getMethod("path", String.class).invoke(rmOp, remote);
            rmOp.getClass().getMethod("exec").invoke(rmOp);
            log.info("[maven] SSH 删除: {}", remote);
        } catch (Exception e) {
            throw new MavenDeployException(
                    "SSH 删除失败: " + path + " -> " + host, e);
        }
    }

    @Override
    /** 断开 */
    public void disconnect() {
        if (sftpClient != null) {
            try {
                sftpClient.getClass().getMethod("disconnect").invoke(sftpClient);
            } catch (Exception e) {
                log.warn("[maven] SSH 断开异常: {}", e.getMessage());
            }
            sftpClient = null;
        }
        ready = false;
    }

    @Override
    /** 设置Callback */
    public void setCallback(MavenDeployCallback callback) {
        this.callback = callback;
    }

    @Override
    /** UploadBatch */
    public int uploadBatch(List<String> files, String targetDir) {
        ensureReady();
        int count = 0;
        String dir = targetDir;
        if (!dir.endsWith("/")) {
            dir += "/";
        }
        for (String file : files) {
            java.io.File f = new java.io.File(file);
            String remote = dir + f.getName();
            // 先创建目标目录
            createDirectory(dir);
            upload(file, remote);
            count++;
            if (callback != null) {
                int percent = count * 100 / files.size();
                callback.onDeployProgress("SSH 上传: " + f.getName(), percent);
            }
        }
        return count;
    }

    /**
     * 确保已连接
     */
    private void ensureReady() {
        if (!ready) {
            throw new MavenDeployException("SSH 部署目标未连接，请先调用 connect()");
        }
    }
}