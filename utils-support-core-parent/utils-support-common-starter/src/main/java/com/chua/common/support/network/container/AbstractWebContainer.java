package com.chua.common.support.network.container;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.utils.ThreadUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Web 容器的抽象基类，提供容器状态管理与部署单元解析的通用实现。
 *
 * <p>子类只需实现 {@link #doStart()} 和 {@link #doStop()} 方法，以及
 * {@link #doDeploy(String, String, DeployUnitType)} 即可完成容器适配。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public abstract class AbstractWebContainer implements WebContainer {

    /**
     * HTTP 连接超时（毫秒）
     */
    private static final int CONNECT_TIMEOUT_MS = 30_000;

    /**
     * HTTP 读取超时（毫秒），5 分钟
     */
    private static final int READ_TIMEOUT_MS = 300_000;

    /**
     * 下载缓冲区大小（字节）
     */
    private static final int DOWNLOAD_BUFFER_SIZE = 8_192;

    /**
     * 进度日志输出间隔（字节），1MB
     */
    private static final long PROGRESS_LOG_INTERVAL_BYTES = 1024L * 1024L;

    /**
     * HTTP User-Agent 请求头
     */
    private static final String HTTP_USER_AGENT = "WebContainer/1.0";

    /**
     * HTTP 协议前缀
     */
    private static final String HTTP_PREFIX = "http://";

    /**
     * HTTPS 协议前缀
     */
    private static final String HTTPS_PREFIX = "https://";

    /**
     * FTP 协议前缀
     */
    private static final String FTP_PREFIX = "ftp://";

    /**
     * classpath 路径前缀
     */
    private static final String CLASSPATH_PREFIX = "classpath:";

    /**
     * URL 查询字符串分隔符
     */
    private static final char QUERY_DELIMITER = '?';

    /**
     * 路径分隔符
     */
    private static final char PATH_SEPARATOR = '/';

    /**
     * 文件扩展名分隔符
     */
    private static final char EXTENSION_SEPARATOR = '.';

    /**
     * 根上下文路径
     */
    private static final String ROOT_CONTEXT_PATH = "/";

    /**
     * Spring Boot context-path 参数前缀
     */
    private static final String CONTEXT_PATH_ARG_PREFIX = "--server.servlet.context-path=";

    /**
     * Main 类部署线程名前缀
     */
    private static final String MAIN_THREAD_NAME_PREFIX = "main-";

    /**
     * 远程文件默认命名前缀
     */
    private static final String REMOTE_FILE_NAME_PREFIX = "remote_";

    /**
     * 默认下载缓存目录名
     */
    private static final String DEFAULT_DOWNLOAD_DIR_NAME = "webcontainer";

    /**
     * 字节单位换算基数
     */
    private static final long BYTES_PER_KB = 1024L;

    /**
     * 容器状态机引用
     */
    protected final AtomicReference<ContainerStatus> status = new AtomicReference<>(ContainerStatus.NEW);

    /**
     * 容器配置
     */
    protected WebContainerSetting setting;

    /**
     * 已部署单元列表
     */
    protected final List<DeployUnitInfo> deployedUnits = new ArrayList<>();

    @Override
    /**
     * 初始化
    */
    public void initialize(WebContainerSetting setting) {
        if (setting == null) {
            throw new ContainerException("容器配置不能为 null");
        }
        if (!status.compareAndSet(ContainerStatus.NEW, ContainerStatus.INITIALIZED)) {
            throw new ContainerException("容器状态不正确，当前状态: " + status.get());
        }
        this.setting = setting;
        log.info("{} 容器初始化完成，端口: {}", getName(), setting.getPort());
    }

    @Override
    /**
     * Deploy
    */
    public void deploy(String archivePath) {
        DeployUnitType type = DeployUnitType.fromFileName(archivePath);
        String contextPath = resolveContextPath(archivePath, type);
        deploy(archivePath, contextPath, type);
    }

    @Override
    /**
     * Deploy
    */
    public void deploy(String archivePath, String contextPath, DeployUnitType type) {
        ensureInitialized();
        if (isRunning()) {
            throw new ContainerException("容器已启动，不支持运行时部署");
        }
        if (StringUtils.isEmpty(archivePath)) {
            throw new ContainerException("归档文件路径不能为空");
        }

        // MAIN 类型：将 Main 类作为 Web 入口启动
        if (type == DeployUnitType.MAIN) {
            log.info("{} 容器部署 Main 类: class={}, contextPath={}", getName(), archivePath, contextPath);
            doDeployMain(archivePath, contextPath);
            deployedUnits.add(new DeployUnitInfo(archivePath, contextPath, type));
            return;
        }

        // 远程 URL 自动下载
        String localPath = resolvePath(archivePath);
        validateArchive(localPath, type);
        log.info("{} 容器部署: path={}, contextPath={}, type={}",
                getName(), localPath, contextPath, type);

        // FAT_JAR 和 SPRING_BOOT 作为标准 JAR 由子类部署
        DeployUnitType actualType = (type == DeployUnitType.FAT_JAR || type == DeployUnitType.SPRING_BOOT)
                ? DeployUnitType.JAR : type;
        doDeploy(localPath, contextPath, actualType);

        deployedUnits.add(new DeployUnitInfo(localPath, contextPath, type));
    }

    /**
     * 部署 Main 类作为 Web 服务入口。
     *
     * <p>子类可重写此方法以实现特定容器的 Main 类部署逻辑。
     * 默认实现基于反射调用 Main 类的 main 方法。</p>
     *
     * @param mainClass   主类全限定名
     * @param contextPath 上下文路径
     */
    protected void doDeployMain(String mainClass, String contextPath) {
        try {
             Class<?> clazz = ReflectUtils.forName(mainClass);
            String[] args = StringUtils.isEmpty(contextPath)
                    ? new String[0]
                    : new String[]{CONTEXT_PATH_ARG_PREFIX + contextPath};
            Thread thread = ThreadUtils.newThread(() -> {
                try {
                    ReflectUtils.invokeStatic(clazz, "main", void.class, new Class<?>[]{String[].class}, (Object) args);
                } catch (Exception e) {
                    log.error("Main 类执行失败: {}", mainClass, e);
                }
            }, MAIN_THREAD_NAME_PREFIX + mainClass);
            thread.setDaemon(true);
            thread.start();
            log.info("Main 类已启动: {}", mainClass);
        } catch (Exception e) {
            throw new ContainerException("Main 类部署失败: " + mainClass, e);
        }
    }

    @Override
    /**
     * DeployAll
    */
    public void deployAll() {
        ensureInitialized();
        if (setting.getDeployUnits() == null || setting.getDeployUnits().isEmpty()) {
            log.warn("没有找到待部署的单元配置");
            return;
        }
        for (WebContainerSetting.DeployUnit unit : setting.getDeployUnits()) {
            if (unit.isAutoDeploy()) {
                String ctx = unit.getContextPath();
                if (StringUtils.isEmpty(ctx)) {
                    ctx = resolveContextPath(unit.getPath(), unit.getType());
                }
                deploy(unit.getPath(), ctx, unit.getType());
            }
        }
    }

    @Override
    /**
     * Undeploy
    */
    public void undeploy(String contextPath) {
        if (!isRunning()) {
            throw new ContainerException("容器未启动，无法执行卸载操作");
        }
        log.info("{} 容器卸载: contextPath={}", getName(), contextPath);
        doUndeploy(contextPath);
        deployedUnits.removeIf(u -> contextPath.equals(u.getContextPath()));
    }

    @Override
    /**
     * 开始
    */
    public void start() {
        if (!status.compareAndSet(ContainerStatus.INITIALIZED, ContainerStatus.STARTING)) {
            ContainerStatus current = status.get();
            if (current == ContainerStatus.RUNNING) {
                log.warn("{} 容器已在运行中", getName());
                return;
            }
            throw new ContainerException("容器状态不正确，无法启动。当前状态: " + current);
        }
        log.info("{} 容器正在启动...", getName());
        try {
            doStart();
            status.set(ContainerStatus.RUNNING);
            log.info("{} 容器启动成功，监听 {}:{}", getName(), setting.getHost(), setting.getPort());
        } catch (Exception e) {
            status.set(ContainerStatus.FAILED);
            throw new ContainerException(getName() + " 容器启动失败", e);
        }
    }

    @Override
    /**
     * 停止
    */
    public void stop() {
        if (!status.compareAndSet(ContainerStatus.RUNNING, ContainerStatus.STOPPING)) {
            ContainerStatus current = status.get();
            if (current == ContainerStatus.STOPPED) {
                log.warn("{} 容器已停止", getName());
                return;
            }
            throw new ContainerException("容器状态不正确，无法停止。当前状态: " + current);
        }
        log.info("{} 容器正在停止...", getName());
        try {
            doStop();
            deployedUnits.clear();
            status.set(ContainerStatus.STOPPED);
            log.info("{} 容器已停止", getName());
        } catch (Exception e) {
            status.set(ContainerStatus.FAILED);
            throw new ContainerException(getName() + " 容器停止失败", e);
        }
    }

    @Override
    /**
     * Restart
    */
    public void restart() {
        log.info("{} 容器正在重启...", getName());
        if (isRunning()) {
            stop();
        }
        if (setting != null) {
            status.set(ContainerStatus.INITIALIZED);
        }
        start();
    }

    @Override
    /**
     * 获取Status
    */
    public ContainerStatus getStatus() {
        return status.get();
    }

    @Override
    /**
     * 是否Running
    */
    public boolean isRunning() {
        return status.get() == ContainerStatus.RUNNING;
    }

    /**
     * 子类实现：启动容器。
     */
    protected abstract void doStart();

    /**
     * 子类实现：停止容器。
     */
    protected abstract void doStop();

    /**
     * 子类实现：部署归档文件。
     *
     * @param archivePath 归档文件路径
     * @param contextPath 上下文路径
     * @param type        部署单元类型
     */
    protected abstract void doDeploy(String archivePath, String contextPath, DeployUnitType type);

    /**
     * 子类实现：卸载应用（默认空实现）。
     *
     * @param contextPath 上下文路径
     */
    protected void doUndeploy(String contextPath) {
        // 默认空实现，子类可按需覆盖
    }

    /**
     * 确保容器已初始化。
     */
    protected void ensureInitialized() {
        if (setting == null) {
            throw new ContainerException("容器尚未初始化，请先调用 initialize()");
        }
    }

    /**
     * 根据归档文件路径解析上下文路径。
     *
     * @param archivePath 归档文件路径
     * @param type        部署单元类型
     * @return 解析出的上下文路径
     */
    protected String resolveContextPath(String archivePath, DeployUnitType type) {
        if (archivePath == null) {
            return ROOT_CONTEXT_PATH;
        }
        File file = new File(archivePath);
        String name = file.getName();
        int dotIndex = name.lastIndexOf(EXTENSION_SEPARATOR);
        if (dotIndex > 0) {
            name = name.substring(0, dotIndex);
        }
        if (type == DeployUnitType.EAR) {
            return ROOT_CONTEXT_PATH + name;
        }
        return ROOT_CONTEXT_PATH.equals(name) || name.isEmpty() ? ROOT_CONTEXT_PATH : ROOT_CONTEXT_PATH + name;
    }

    /**
     * 校验归档文件。
     *
     * @param archivePath 归档文件路径
     * @param type        部署单元类型
     */
    protected void validateArchive(String archivePath, DeployUnitType type) {
        File file = new File(archivePath);
        if (!file.exists() && !archivePath.startsWith(CLASSPATH_PREFIX)) {
            log.warn("归档文件不存在（文件系统路径）: {}，将尝试 classpath 加载", archivePath);
        }
    }

    // -------------------- 远程下载支持 --------------------

    /**
     * 判断路径是否为远程 URL。
     *
     * @param path 路径
     * @return true 表示是远程 URL
     */
    protected boolean isRemoteUrl(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase();
        return lower.startsWith(HTTP_PREFIX) || lower.startsWith(HTTPS_PREFIX) || lower.startsWith(FTP_PREFIX);
    }

    /**
     * 将路径解析为本地可用的文件路径。
     * <ul>
     *   <li>远程 URL → 自动下载到缓存目录，返回本地路径</li>
     *   <li>classpath: 前缀 → 保留原样，由子类处理</li>
     *   <li>本地路径 → 直接返回</li>
     * </ul>
     *
     * @param path 原始路径
     * @return 本地可用的文件路径
     */
    protected String resolvePath(String path) {
        if (StringUtils.isEmpty(path)) {
            return path;
        }
        if (isRemoteUrl(path)) {
            return downloadRemoteFile(path);
        }
        return path;
    }

    /**
     * 下载远程文件到本地缓存目录。
     *
     * <p>如果同名文件已存在且大小一致则跳过下载（缓存复用）。</p>
     *
     * @param remoteUrl 远程文件 URL
     * @return 下载后的本地文件绝对路径
     * @throws ContainerException 下载失败时抛出
     */
    protected String downloadRemoteFile(String remoteUrl) {
        String fileName = extractFileName(remoteUrl);
        File downloadDir = determineDownloadDir();
        File localFile = new File(downloadDir, fileName);

        // 缓存复用：同名文件已存在则跳过下载
        if (localFile.exists() && localFile.length() > 0) {
            log.info("{} 远程文件已缓存: {} → {}", getName(), remoteUrl, localFile.getAbsolutePath());
            return localFile.getAbsolutePath();
        }

        log.info("{} 正在下载远程文件: {}", getName(), remoteUrl);
        log.info("{} 保存到: {}", getName(), localFile.getAbsolutePath());

        HttpURLConnection conn = null;
        try {
            URL url = new URL(remoteUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", HTTP_USER_AGENT);
            conn.setInstanceFollowRedirects(true);

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                    || responseCode == HttpURLConnection.HTTP_MOVED_PERM
                    || responseCode == HttpURLConnection.HTTP_SEE_OTHER) {
                // 跟随重定向
                String redirectUrl = conn.getHeaderField("Location");
                conn.disconnect();
                conn = null;
                return downloadRemoteFile(redirectUrl);
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new ContainerException("远程文件下载失败，HTTP " + responseCode + ": " + remoteUrl);
            }

            long contentLength = conn.getContentLengthLong();
            long downloaded = 0;

            if (!downloadDir.exists()) {
                downloadDir.mkdirs();
            }

            try (InputStream in = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(localFile)) {

                byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    downloaded += bytesRead;
                    if (contentLength > 0 && downloaded % PROGRESS_LOG_INTERVAL_BYTES == 0) {
                        log.info("{} 下载进度: {} / {} ({}%)", getName(),
                                formatSize(downloaded), formatSize(contentLength),
                                downloaded * 100 / contentLength);
                    }
                }
            }

            log.info("{} 下载完成: {} ({}), 大小={}",
                    getName(), fileName, remoteUrl, formatSize(localFile.length()));
            return localFile.getAbsolutePath();

        } catch (IOException e) {
            // 删除不完整的文件
            if (localFile.exists()) {
                localFile.delete();
            }
            throw new ContainerException("远程文件下载失败: " + remoteUrl, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 从 URL 中提取文件名。
     *
     * @param url URL 字符串
     * @return 文件名，若无法提取则返回带时间戳的默认名
     */
    private String extractFileName(String url) {
        int queryIndex = url.indexOf(QUERY_DELIMITER);
        String path = queryIndex > 0 ? url.substring(0, queryIndex) : url;
        int lastSlash = path.lastIndexOf(PATH_SEPARATOR);
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            return path.substring(lastSlash + 1);
        }
        // fallback: 使用时间戳
        return REMOTE_FILE_NAME_PREFIX + System.currentTimeMillis();
    }

    /**
     * 确定下载缓存目录。
     *
     * @return 下载缓存目录
     */
    private File determineDownloadDir() {
        if (setting != null && !StringUtils.isEmpty(setting.getDownloadDir())) {
            return new File(setting.getDownloadDir());
        }
        return new File(System.getProperty("java.io.tmpdir"),
                DEFAULT_DOWNLOAD_DIR_NAME + File.separator + getName());
    }

    /**
     * 格式化文件大小显示。
     *
     * @param bytes 字节数
     * @return 可读的文件大小字符串
     */
    private static String formatSize(long bytes) {
        if (bytes < BYTES_PER_KB) {
            return bytes + " B";
        }
        if (bytes < BYTES_PER_KB * BYTES_PER_KB) {
            return String.format("%.1f KB", bytes / (double) BYTES_PER_KB);
        }
        if (bytes < BYTES_PER_KB * BYTES_PER_KB * BYTES_PER_KB) {
            return String.format("%.1f MB", bytes / (double) (BYTES_PER_KB * BYTES_PER_KB));
        }
        return String.format("%.2f GB", bytes / (double) (BYTES_PER_KB * BYTES_PER_KB * BYTES_PER_KB));
    }

    /**
     * 获取已部署的单元信息列表。
     *
     * @return 已部署单元信息副本列表
     */
    public List<DeployUnitInfo> getDeployedUnits() {
        return new ArrayList<>(deployedUnits);
    }

    /**
     * 已部署的单元信息。
     *
     * @since 4.0.0.42
     */
    @Data
    public static class DeployUnitInfo {
        /**
         * 归档文件路径
         */
        private final String path;

        /**
         * 上下文路径
         */
        private final String contextPath;

        /**
         * 部署单元类型
         */
        private final DeployUnitType type;

        /**
         * 构造已部署单元信息。
         *
         * @param path        归档文件路径
         * @param contextPath 上下文路径
         * @param type        部署单元类型
         */
        public DeployUnitInfo(String path, String contextPath, DeployUnitType type) {
            this.path = path;
            this.contextPath = contextPath;
            this.type = type;
        }

        /**
         * 转为字符串表示。
         *
         * @return 字符串表示
         */
        @Override
        public String toString() {
            return type + ":" + contextPath + "=" + path;
        }
    }
}
