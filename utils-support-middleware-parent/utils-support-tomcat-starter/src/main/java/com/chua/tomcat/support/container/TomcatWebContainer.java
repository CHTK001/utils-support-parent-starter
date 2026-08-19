package com.chua.tomcat.support.container;

import com.chua.common.support.network.container.AbstractWebContainer;
import com.chua.common.support.network.container.DeployUnitType;
import com.chua.common.support.network.container.WebContainer;
import com.chua.common.support.network.container.WebContainerSetting;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Apache Tomcat 的嵌入式 Web 容器实现。
 *
 * <p>支持 WAR 包的部署与启动，通过 {@link AbstractWebContainer} 提供
 * 统一的容器生命周期管理。
 *
 * <p>核心特性：
 * <ul>
 *   <li>内嵌式部署，无需外部 Tomcat 安装</li>
 *   <li>支持多应用同时部署（多 Context）</li>
 *   <li>支持 WAR 自动解压部署</li>
 *   <li>支持 SSL/TLS 安全连接</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"tomcat", "apache-tomcat"})
public class TomcatWebContainer extends AbstractWebContainer {

    /** Tomcat */
    private Tomcat tomcat;
    /** contexts */
    private final Map<String, Context> contexts = new ConcurrentHashMap<>();

    @Override
    /** 获取Name */
    public String getName() {
        return "tomcat";
    }

    @Override
    /** 获取Port */
    public int getPort() {
        if (tomcat != null && tomcat.getConnector() != null) {
            return tomcat.getConnector().getLocalPort();
        }
        return setting != null ? setting.getPort() : 0;
    }

    @Override
    /** DoDeploy */
    protected void doDeploy(String archivePath, String contextPath, DeployUnitType type) {
        if (tomcat == null) {
            log.warn("Tomcat 引擎尚未初始化，部署将延迟到启动时执行");
            return;
        }

        try {
            switch (type) {
                case WAR:
                    deployWar(archivePath, contextPath);
                    break;
                case JAR:
                    log.info("Tomcat 容器通过 FAT JAR 模式部署: contextPath={}, path={}", contextPath, archivePath);
                    deployWar(archivePath, contextPath);
                    break;
                case EAR:
                    log.info("EAR 文件部署为 WAR 模式: contextPath={}, path={}", contextPath, archivePath);
                    deployWar(archivePath, contextPath);
                    break;
                default:
                    throw new ContainerException("不支持的部署类型: " + type);
            }
        } catch (Exception e) {
            throw new ContainerException("Tomcat 部署失败: " + archivePath, e);
        }
    }

    /**
     * 部署 WAR 文件到 Tomcat。
     * 先解压 WAR 到独立目录，再部署解压后的目录（避免 fixDocBase 失败）。
     */
    private void deployWar(String archivePath, String contextPath) throws Exception {
        var warFile = resolveFile(archivePath);
        // 部署目录：使用固定独立路径，避免 docBase 路径解析冲突
        var deployDir = new File(System.getProperty("java.io.tmpdir"),
                "guacamole-deploy" + File.separator + contextPath.replace("/", ""));
        if (!deployDir.exists() && !deployDir.mkdirs()) {
            log.warn("Tomcat 部署目录创建失败: {}", deployDir);
        }
        // 解压 WAR 到部署目录
        if (warFile.exists() && warFile.isFile()) {
            try (var zis = new java.util.zip.ZipInputStream(new java.io.FileInputStream(warFile))) {
                var entry = zis.getNextEntry();
                while (entry != null) {
                    var outFile = new File(deployDir, entry.getName());
                    if (entry.isDirectory()) {
                        if (!outFile.exists() && !outFile.mkdirs()) {
                            log.warn("解压目录创建失败: {}", outFile);
                        }
                    } else {
                        var parent = outFile.getParentFile();
                        if (parent != null && !parent.exists() && !parent.mkdirs()) {
                            log.warn("父目录创建失败: {}", parent);
                        }
                        try (var fos = new java.io.FileOutputStream(outFile)) {
                            zis.transferTo(fos);
                        }
                    }
                    entry = zis.getNextEntry();
                }
            }
            log.info("WAR 已解压到: {}", deployDir);
        }

        // 确保 host 的 appBase 存在
        var host = tomcat.getHost();
        if (host != null) {
            var appBase = new File(host.getAppBase());
            if (!appBase.exists() && !appBase.mkdirs()) {
                log.warn("Tomcat appBase 创建失败: {}", appBase);
            }
        }

        // 部署解压后的目录（不是 WAR 文件），避免 fixDocBase 失败
        var ctx = (org.apache.catalina.core.StandardContext) tomcat.addWebapp(contextPath, deployDir.getAbsolutePath());
        ctx.setDelegate(true);
        ctx.setParentClassLoader(getClass().getClassLoader());
        contexts.put(contextPath, ctx);
        log.info("Tomcat 部署完成: contextPath={}, path={}", contextPath, deployDir);
    }

    @Override
    /** DoUndeploy */
    protected void doUndeploy(String contextPath) {
        Context ctx = contexts.remove(contextPath);
        if (ctx != null && tomcat != null && tomcat.getHost() != null) {
            try {
                tomcat.getHost().removeChild(ctx);
                log.info("Tomcat 卸载完成: contextPath={}", contextPath);
            } catch (Exception e) {
                log.warn("Tomcat 卸载上下文失败: contextPath={}, error={}", contextPath, e.getMessage());
            }
        }
    }

    @Override
    /** Do开始 */
    protected void doStart() {
        try {
            tomcat = new Tomcat();
            configureTomcat();

            // 设置父类加载器，使 WAR 能委托找到 javax.servlet 等容器类
            tomcat.getEngine().setParentClassLoader(getClass().getClassLoader());

            // 部署来自 setting 的配置单元（deployWar 会同时加入 deployedUnits）
            deployPendingUnits();

            tomcat.start();
            log.info("Tomcat 引擎已启动");
        } catch (LifecycleException e) {
            throw new ContainerException("Tomcat 启动失败", e);
        }
    }

    @Override
    /** Do停止 */
    protected void doStop() {
        try {
            if (tomcat != null) {
                tomcat.stop();
                tomcat.destroy();
                contexts.clear();
                log.info("Tomcat 引擎已停止");
            }
        } catch (LifecycleException e) {
            throw new ContainerException("Tomcat 停止失败", e);
        } finally {
            tomcat = null;
        }
    }

    /**
     * 配置 Tomcat 引擎参数。
     */
    private void configureTomcat() {
        tomcat.setHostname(setting.getHost());
        tomcat.setPort(setting.getPort());

        // 基础目录
        var baseDir = new File(System.getProperty("java.io.tmpdir"),
                "tomcat-" + System.currentTimeMillis()).getAbsolutePath();
        tomcat.setBaseDir(baseDir);

        // 显式设置 catalina.home，并预先创建 appBase/webapps 目录
        var catalinaHome = new File(baseDir);
        System.setProperty("catalina.home", catalinaHome.getAbsolutePath());
        System.setProperty("catalina.base", catalinaHome.getAbsolutePath());
        var webappsDir = new File(baseDir, "webapps");
        if (!webappsDir.exists() && !webappsDir.mkdirs()) {
            log.warn("Tomcat appBase/webapps 目录创建失败: {}", webappsDir);
        }

        // 显式设置 host 的 appBase（否则默认是 user.dir/webapps）
        var host = tomcat.getHost();
        if (host == null) {
            host = new org.apache.catalina.core.StandardHost();
            host.setName(setting.getHost());
            tomcat.getEngine().addChild(host);
        }
        host.setAppBase(webappsDir.getAbsolutePath());

        // 连接器配置
        var connector = tomcat.getConnector();
        if (connector != null) {
            connector.setProperty("maxThreads", String.valueOf(setting.getMaxThreads()));
            connector.setProperty("minSpareThreads", String.valueOf(setting.getMinSpareThreads()));
            connector.setProperty("connectionTimeout", String.valueOf(setting.getConnectionTimeout()));
            connector.setProperty("URIEncoding", setting.getCharset());
            connector.setProperty("maxPostSize", String.valueOf(setting.getMaxRequestBodySize()));
        }

        // SSL 配置
        configureSsl();

        // 访问日志
        if (setting.isAccessLogEnabled()) {
            host.setAutoDeploy(false);
            var valve = new org.apache.catalina.valves.AccessLogValve();
            valve.setDirectory(setting.getAccessLogDirectory());
            valve.setPattern("common");
            valve.setSuffix(".log");
            host.getPipeline().addValve(valve);
        }
    }

    /**
     * 配置 SSL/TLS。
     */
    private void configureSsl() {
        WebContainerSetting.SslConfig ssl = setting.getSsl();
        if (ssl == null || !ssl.isEnabled()) {
            return;
        }

        try {
            org.apache.catalina.connector.Connector connector = tomcat.getConnector();
            if (connector != null) {
                connector.setSecure(true);
                connector.setScheme("https");
                connector.setProperty("SSLEnabled", "true");
                connector.setProperty("sslProtocol", ssl.getSslProtocol());

                if (ssl.getKeyStorePath() != null) {
                    connector.setProperty("keystoreFile", ssl.getKeyStorePath());
                    connector.setProperty("keystorePass",
                            ssl.getKeyStorePassword() != null ? ssl.getKeyStorePassword() : "");
                    connector.setProperty("keystoreType", ssl.getKeyStoreType());
                }

                if (ssl.getCertPath() != null && ssl.getKeyPath() != null) {
                    connector.setProperty("clientAuth", "false");
                }
            }
        } catch (Exception e) {
            log.warn("Tomcat SSL 配置失败: {}", e.getMessage());
        }
    }

    /**
     * 部署在初始化时注册但延迟到启动时执行的单元。
     */
    private void deployPendingUnits() {
        if (setting.getDeployUnits() == null) {
            return;
        }
        for (WebContainerSetting.DeployUnit unit : setting.getDeployUnits()) {
            if (unit.isAutoDeploy()) {
                String ctxPath = unit.getContextPath();
                if (ctxPath == null || ctxPath.isEmpty()) {
                    ctxPath = resolveContextPath(unit.getPath(), unit.getType());
                }
                deployWarSafely(unit.getPath(), ctxPath, unit.getType());
            }
        }
    }

    /** DeployWarSafely */
    private void deployWarSafely(String path, String ctxPath, DeployUnitType type) {
        try {
            deployWar(path, ctxPath);
            deployedUnits.add(new DeployUnitInfo(path, ctxPath, type));
        } catch (Exception e) {
            log.warn("Tomcat 延迟部署失败: path={}, error={}", path, e.getMessage());
        }
    }

    /**
     * 解析文件路径，支持文件系统路径和 classpath 前缀。
     */
    private File resolveFile(String path) {
        if (path.startsWith("classpath:")) {
            String resourcePath = path.substring("classpath:".length());
            var url = getClass().getClassLoader().getResource(resourcePath);
            if (url != null) {
                return new File(url.getFile());
            }
            throw new ContainerException("classpath 资源未找到: " + resourcePath);
        }
        return new File(path);
    }
}
