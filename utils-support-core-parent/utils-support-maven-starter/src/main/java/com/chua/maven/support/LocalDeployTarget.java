package com.chua.maven.support;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 本地文件系统部署目标。
 * <p>
 * 将构建产版本复制到本地目录。这是 {@link MavenDeployTarget} 的默认实现。
 * </p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * LocalDeployTarget target = new LocalDeployTarget("/opt/app");
 * target.connect();
 * target.upload("target/myapp.jar", "myapp.jar");
 * target.disconnect();
 *
 * // 或通过传输客户端
 * MavenClient.create().projectPath("pom.xml").goal("package")
 *     .compileAndDeploy()
 *     .deployTo(new LocalDeployTarget("/opt/app"));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class LocalDeployTarget implements MavenDeployTarget {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LocalDeployTarget.class);

    /**
     * 目标根目录
     */
    private final String rootDir;

    /**
     * 是否已连接
     */
    private boolean ready;

    /**
     * 部署回调
     */
    private MavenDeployCallback callback;

    /**
     * 构造本地部署目标
     *
     * @param rootDir 目标根目录路径
     */
    public LocalDeployTarget(String rootDir) {
        this.rootDir = rootDir;
    }

    @Override
    public String name() {
        return "本地文件系统: " + rootDir;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public void connect() {
        File dir = new File(rootDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        ready = true;
        log.info("[maven] 本地部署目标就绪: {}", rootDir);
    }

    @Override
    public void upload(String localPath, String targetPath) {
        ensureReady();
        try {
            Path source = Path.of(localPath);
            Path target = Path.of(rootDir, targetPath);
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("[maven] 本地部署: {} -> {}", localPath, target.toAbsolutePath());
        } catch (IOException e) {
            throw new MavenDeployException("本地部署失败: " + localPath + " -> " + targetPath, e);
        }
    }

    @Override
    public void createDirectory(String path) {
        ensureReady();
        File dir = new File(rootDir, path);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        log.info("[maven] 创建目录: {}", dir.getAbsolutePath());
    }

    @Override
    public boolean exists(String path) {
        return new File(rootDir, path).exists();
    }

    @Override
    public void delete(String path) {
        File file = new File(rootDir, path);
        if (file.exists()) {
            try {
                Files.delete(file.toPath());
                log.info("[maven] 删除文件: {}", file.getAbsolutePath());
            } catch (IOException e) {
                throw new MavenDeployException("删除文件失败: " + file.getAbsolutePath(), e);
            }
        }
    }

    @Override
    public void disconnect() {
        ready = false;
    }

    @Override
    public void setCallback(MavenDeployCallback callback) {
        this.callback = callback;
    }

    /**
     * 获取根目录路径
     *
     * @return 根目录
     */
    public String getRootDir() {
        return rootDir;
    }

    /**
     * 确保已连接
     */
    private void ensureReady() {
        if (!ready) {
            throw new MavenDeployException("部署目标未连接，请先调用 connect()");
        }
    }
}