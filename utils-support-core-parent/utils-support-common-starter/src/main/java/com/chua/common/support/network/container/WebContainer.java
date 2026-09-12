package com.chua.common.support.network.container;

import com.chua.common.support.spi.annotations.Spi;

/**
* Web 容器接口，定义嵌入式 Servlet 容器的标准生命周期操作。
*
* <p>通过 SPI 机制发现和加载容器实现，支持在运行时管理容器的生命周期。
* 典型实现包括 Tomcat、Undertow 等。
*
* <p>使用示例：
* <pre>{@code
* // 通过 SPI 获取容器实例
* WebContainer container = SpiServiceLoader.load(WebContainer.class, "tomcat");
*
* // 配置并部署
* WebContainerSetting setting = WebContainerSetting.defaults();
* setting.setPort(8080);
* setting.addDeployUnit(DeployUnit.builder()
*     .type(DeployUnitType.WAR)
*     .path("/path/to/myapp.war")
*     .build());
* container.initialize(setting);
* container.deploy("/path/to/myapp.war");
*
* // 启动
* container.start();
*
* // 获取容器状态
* ContainerStatus status = container.getStatus();
*
* // 停止
* container.stop();
* }</pre>
*
* @author CH
* @since 1.0.0
 */
public interface WebContainer {

    /**
    * 初始化容器，设置配置参数。
    *
    * @param setting 容器配置
    * @throws ContainerException 初始化失败时抛出
     */
    void initialize(WebContainerSetting setting);

    /**
    * 根据文件路径自动推断类型并部署归档文件。
    *
    * <p>支持本地文件路径（/path/to/app.war）和远程 URL（http://、https://）。
    * 远程 URL 会自动下载到临时目录后再部署。</p>
    *
    * @param archivePath 归档文件路径（.war、.jar 或 .ear），支持本地路径和远程 URL
    * @throws ContainerException 部署失败时抛出
    * @see #deploy(String, String, DeployUnitType)
     */
    void deploy(String archivePath);

    /**
    * 部署指定类型和上下文的归档文件。
    *
    * <p>支持本地文件路径和远程 URL，远程文件自动下载。</p>
    *
    * @param archivePath 归档文件路径，支持本地路径和远程 URL（http/https）
    * @param contextPath 上下文路径（可为 null，此时自动推导）
    * @param type        部署单元类型
    * @throws ContainerException 部署失败时抛出
     */
    void deploy(String archivePath, String contextPath, DeployUnitType type);

    /**
    * 从远程 URL 下载并部署归档文件。
    *
    * <p>等价于 {@code deploy(url)}，显式声明从远程下载。</p>
    *
    * @param url 远程归档文件 URL
    * @throws ContainerException 下载或部署失败时抛出
    * @see #deploy(String)
     */
    default void deployRemote(String url) {
        deploy(url);
    }

    /**
    * 从远程 URL 下载并部署归档文件（指定上下文路径和类型）。
    *
    * @param url         远程归档文件 URL
    * @param contextPath 上下文路径
    * @param type        部署单元类型
    * @throws ContainerException 下载或部署失败时抛出
     */
    default void deployRemote(String url, String contextPath, DeployUnitType type) {
        deploy(url, contextPath, type);
    }

    /**
    * 从配置中的所有部署单元批量部署。
    *
    * @throws ContainerException 部署失败时抛出
     */
    void deployAll();

    /**
    * 卸载指定上下文路径的应用。
    *
    * @param contextPath 上下文路径
    * @throws ContainerException 卸载失败时抛出
     */
    void undeploy(String contextPath);

    /**
    * 启动容器。调用前必须已完成初始化和部署。
    *
    * @throws ContainerException 启动失败时抛出
    * @see #initialize(WebContainerSetting)
     */
    void start();

    /**
    * 停止容器，释放所有资源。
    *
    * @throws ContainerException 停止失败时抛出
     */
    void stop();

    /**
    * 重启容器。
    *
    * @throws ContainerException 重启失败时抛出
     */
    void restart();

    /**
    * 获取容器当前运行状态。
    *
    * @return 容器状态对象
     */
    ContainerStatus getStatus();

    /**
    * 获取容器名称/标识。
    *
    * @return 容器名称，如 "tomcat"、"undertow"
     */
    String getName();

    /**
    * 判断容器是否正在运行。
    *
    * @return 运行中返回 true，否则返回 false
     */
    boolean isRunning();

    /**
    * 获取容器实际监听端口。
    *
    * @return 实际端口号，端口 0 时返回自动分配后的端口，未启动时返回配置端口
     */
    default int getPort() {
        return 0;
    }

    /**
    * 容器运行状态枚举。
     */
    enum ContainerStatus {
        /** 已创建，尚未初始化 */
        NEW,
        /** 已初始化，尚未启动 */
        INITIALIZED,
        /** 正在启动中 */
        STARTING,
        /** 运行中 */
        RUNNING,
        /** 正在停止中 */
        STOPPING,
        /** 已停止 */
        STOPPED,
        /** 失败/异常状态 */
        FAILED
    }

    /**
    * Web 容器异常。
     */
    class ContainerException extends RuntimeException {
        /**
        * 创建 ContainerException 实例
        * @param message message
         */
        public ContainerException(String message) {
            super(message);
        }

        /**
        * 创建 ContainerException 实例
        * @param message message
        * @param Throwable Throwable
         */
        public ContainerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
