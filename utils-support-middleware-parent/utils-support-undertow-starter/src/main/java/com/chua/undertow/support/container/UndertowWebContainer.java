package com.chua.undertow.support.container;

import com.chua.common.support.network.container.AbstractWebContainer;
import com.chua.common.support.network.container.DeployUnitType;
import com.chua.common.support.network.container.WebContainer;
import com.chua.common.support.network.container.WebContainerSetting;
import com.chua.common.support.spi.annotations.Spi;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.resource.ClassPathResourceManager;
import io.undertow.server.handlers.resource.FileResourceManager;
import io.undertow.server.handlers.resource.ResourceHandler;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.ServletContainer;
import io.undertow.servlet.api.ServletInfo;
import lombok.extern.slf4j.Slf4j;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Undertow 的嵌入式 Web 容器实现。
 *
 * <p>Undertow 是 Red Hat 开源的高性能 Web 服务器，具有轻量级、
 * 低内存占用、高吞吐量的特点。
 *
 * <p>核心特性：
 * <ul>
 *   <li>纯异步非阻塞 I/O（基于 XNIO）</li>
 *   <li>支持 Servlet 3.1+ 规范</li>
 *   <li>支持 WebSocket 和 HTTP/2</li>
 *   <li>嵌入式部署，零外部依赖</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"undertow", "jboss-undertow"})
public class UndertowWebContainer extends AbstractWebContainer {

    private Undertow undertow;
    private ServletContainer servletContainer;
    private final Map<String, DeploymentManager> deployments = new ConcurrentHashMap<>();
    private final PathHandler pathHandler = Handlers.path();

    @Override
    public String getName() {
        return "undertow";
    }

    @Override
    protected void doDeploy(String archivePath, String contextPath, DeployUnitType type) {
        if (undertow != null && undertow.getListenerInfo().isEmpty()) {
            throw new ContainerException("Undertow 服务器尚未启动，无法动态部署");
        }

        try {
            switch (type) {
                case WAR:
                    deployWar(archivePath, contextPath);
                    break;
                case JAR:
                    log.info("Undertow 容器通过 FAT JAR 模式部署: contextPath={}, path={}", contextPath, archivePath);
                    deployFatJar(archivePath, contextPath);
                    break;
                case EAR:
                    log.info("EAR 文件在 Undertow 中不支持原生部署，尝试作为资源处理: contextPath={}",
                            contextPath);
                    deployResource(archivePath, contextPath);
                    break;
                default:
                    throw new ContainerException("不支持的部署类型: " + type);
            }
        } catch (Exception e) {
            throw new ContainerException("Undertow 部署失败: " + archivePath, e);
        }
    }

    /**
     * 部署 WAR 文件。
     */
    private void deployWar(String archivePath, String contextPath) throws Exception {
        File warFile = resolveFile(archivePath);
        DeploymentInfo deploymentInfo = new DeploymentInfo()
                .setClassLoader(getClass().getClassLoader())
                .setContextPath(contextPath)
                .setDeploymentName(contextPath);

        if (warFile.isDirectory()) {
            // 目录作为资源根
            deploymentInfo.setResourceManager(
                    new FileResourceManager(warFile, 1024));
        } else {
            // WAR 文件
            deploymentInfo.setResourceManager(
                    new FileResourceManager(warFile.getParentFile(), 1024));
        }

        deploymentInfo.addListener(
                new io.undertow.servlet.api.ListenerInfo(jakarta.servlet.ServletContextListener.class));

        DeploymentManager manager = servletContainer.addDeployment(deploymentInfo);
        manager.deploy();
        var httpHandler = manager.start();
        pathHandler.addPrefixPath(contextPath, httpHandler);
        deployments.put(contextPath, manager);
        log.info("Undertow 部署完成: contextPath={}, path={}", contextPath, archivePath);
    }

    /**
     * 部署 Fat JAR（Spring Boot 等可执行 JAR）。
     */
    private void deployFatJar(String archivePath, String contextPath) throws Exception {
        File jarFile = resolveFile(archivePath);
        DeploymentInfo deploymentInfo = new DeploymentInfo()
                .setClassLoader(getClass().getClassLoader())
                .setContextPath(contextPath)
                .setDeploymentName(contextPath)
                .setResourceManager(
                        new FileResourceManager(jarFile.getParentFile(), 1024));

        deploymentInfo.addListener(
                new io.undertow.servlet.api.ListenerInfo(jakarta.servlet.ServletContextListener.class));

        DeploymentManager manager = servletContainer.addDeployment(deploymentInfo);
        manager.deploy();
        var httpHandler = manager.start();
        pathHandler.addPrefixPath(contextPath, httpHandler);
        deployments.put(contextPath, manager);
        log.info("Undertow Fat JAR 部署完成: contextPath={}, path={}", contextPath, archivePath);
    }

    /**
     * 作为静态资源部署。
     */
    private void deployResource(String archivePath, String contextPath) throws Exception {
        File resourceFile = resolveFile(archivePath);
        ResourceHandler resourceHandler;
        if (resourceFile.isDirectory()) {
            resourceHandler = new ResourceHandler(new FileResourceManager(resourceFile, 1024));
        } else {
            resourceHandler = new ResourceHandler(new FileResourceManager(resourceFile.getParentFile(), 1024));
        }
        pathHandler.addPrefixPath(contextPath, resourceHandler);
        log.info("Undertow 静态资源部署完成: contextPath={}, path={}", contextPath, archivePath);
    }

    @Override
    protected void doUndeploy(String contextPath) {
        DeploymentManager manager = deployments.remove(contextPath);
        if (manager != null) {
            try {
                manager.stop();
                manager.undeploy();
                pathHandler.removePrefixPath(contextPath);
                log.info("Undertow 卸载完成: contextPath={}", contextPath);
            } catch (Exception e) {
                log.warn("Undertow 卸载失败: contextPath={}, error={}", contextPath, e.getMessage());
            }
        }
    }

    @Override
    protected void doStart() {
        try {
            servletContainer = ServletContainer.Factory.newInstance();
            buildHandlers();

            Undertow.Builder builder = Undertow.builder()
                    .addHttpListener(setting.getPort(), setting.getHost())
                    .setHandler(pathHandler)
                    .setIoThreads(Runtime.getRuntime().availableProcessors())
                    .setWorkerThreads(setting.getMaxThreads());

            // 缓冲区配置
            builder.setBufferSize(16384);
            builder.setDirectBuffers(true);

            // SSL 配置
            configureSsl(builder);

            undertow = builder.build();
            undertow.start();
            log.info("Undertow 服务器已启动: {}:{}", setting.getHost(), setting.getPort());
        } catch (Exception e) {
            throw new ContainerException("Undertow 启动失败", e);
        }
    }

    @Override
    protected void doStop() {
        try {
            // 先停止所有部署
            for (Map.Entry<String, DeploymentManager> entry : deployments.entrySet()) {
                try {
                    entry.getValue().stop();
                    entry.getValue().undeploy();
                } catch (Exception e) {
                    log.warn("Undertow 停止部署失败: contextPath={}", entry.getKey());
                }
            }
            deployments.clear();

            if (undertow != null) {
                undertow.stop();
                log.info("Undertow 服务器已停止");
            }
        } finally {
            undertow = null;
            servletContainer = null;
        }
    }

    /**
     * 构建处理器链。
     */
    private void buildHandlers() {
        // 部署所有待部署单元到路径处理器
        if (setting.getDeployUnits() != null) {
            for (WebContainerSetting.DeployUnit unit : setting.getDeployUnits()) {
                if (unit.isAutoDeploy()) {
                    try {
                        String ctxPath = unit.getContextPath();
                        if (ctxPath == null || ctxPath.isEmpty()) {
                            ctxPath = resolveContextPath(unit.getPath(), unit.getType());
                        }
                        deployWar(unit.getPath(), ctxPath);
                        deployedUnits.add(new DeployUnitInfo(unit.getPath(), ctxPath, unit.getType()));
                    } catch (Exception e) {
                        log.warn("Undertow 部署失败: path={}, error={}", unit.getPath(), e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * 配置 SSL/TLS。
     */
    private void configureSsl(Undertow.Builder builder) {
        WebContainerSetting.SslConfig ssl = setting.getSsl();
        if (ssl == null || !ssl.isEnabled()) {
            return;
        }

        try {
            javax.net.ssl.SSLContext sslContext;
            if (ssl.getKeyStorePath() != null) {
                var keyStore = java.security.KeyStore.getInstance(ssl.getKeyStoreType());
                try (var is = new java.io.FileInputStream(ssl.getKeyStorePath())) {
                    keyStore.load(is, ssl.getKeyStorePassword() != null
                            ? ssl.getKeyStorePassword().toCharArray() : new char[0]);
                }
                var kmf = javax.net.ssl.KeyManagerFactory
                        .getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(keyStore, ssl.getKeyStorePassword() != null
                        ? ssl.getKeyStorePassword().toCharArray() : new char[0]);
                sslContext = javax.net.ssl.SSLContext.getInstance(ssl.getSslProtocol());
                sslContext.init(kmf.getKeyManagers(), null, null);
            } else {
                sslContext = javax.net.ssl.SSLContext.getDefault();
            }

            builder.addHttpsListener(8443, setting.getHost(), sslContext);
            log.info("Undertow SSL 已启用");
        } catch (Exception e) {
            log.warn("Undertow SSL 配置失败: {}", e.getMessage());
        }
    }

    /**
     * 解析文件路径。
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
