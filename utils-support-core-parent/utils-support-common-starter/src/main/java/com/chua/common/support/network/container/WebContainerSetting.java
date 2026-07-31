package com.chua.common.support.network.container;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Web 容器配置，用于控制嵌入式 Servlet 容器的启动参数。
 *
 * <p>支持 Tomcat、Undertow 等主流嵌入式 Web 容器的通用配置，
 * 以及各容器特有的扩展配置。
 *
 * @author CH
 * @since 1.0.0
 */
@Data
public class WebContainerSetting {

    /**
     * 创建一份默认的 Web 容器配置。
     *
     * @return 新的默认配置实例
     */
    public static WebContainerSetting defaults() {
        return new WebContainerSetting();
    }

    /** 绑定主机地址，默认 0.0.0.0 */
    private String host = "0.0.0.0";

    /** 绑定端口，默认 8080 */
    private int port = 8080;

    /** 上下文路径，默认 / */
    private String contextPath = "/";

    /** 是否自动解压部署单元 */
    private boolean unpackWar = true;

    /** 最大线程数，默认 200 */
    private int maxThreads = 200;

    /** 最小空闲线程数，默认 10 */
    private int minSpareThreads = 10;

    /** 连接超时时间（毫秒），默认 60000 */
    private int connectionTimeout = 60000;

    /** 字符编码，默认 UTF-8 */
    private String charset = "UTF-8";

    /** 最大请求体大小（字节），默认 10MB */
    private long maxRequestBodySize = 10 * 1024 * 1024;

    /** 是否启用访问日志 */
    private boolean accessLogEnabled;

    /** 访问日志输出目录 */
    private String accessLogDirectory = "logs";

    /** 优雅关闭等待时间（秒），默认 30 */
    private int gracefulShutdownTimeout = 30;

    /** 远程文件下载缓存目录，默认使用系统临时目录 */
    private String downloadDir;

    /** 部署单元列表 */
    private List<DeployUnit> deployUnits = new ArrayList<>();

    /** SSL/TLS 配置 */
    private SslConfig ssl = new SslConfig();

    /** 追加的部署单元，不会覆盖已有列表 */
    public void addDeployUnit(DeployUnit unit) {
        if (deployUnits == null) {
            deployUnits = new ArrayList<>();
        }
        deployUnits.add(unit);
    }

    /**
     * 部署单元定义，描述一个待部署的归档文件。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DeployUnit {
        /** 部署单元类型 */
        @Builder.Default
        private DeployUnitType type = DeployUnitType.WAR;

        /** 归档文件绝对路径或 classpath 路径 */
        private String path;

        /** 部署后的上下文路径（仅对 WAR 有效），默认从文件名推导 */
        private String contextPath;

        /** 是否在启动时部署 */
        @Builder.Default
        private boolean autoDeploy = true;
    }

    /**
     * SSL/TLS 配置。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SslConfig {
        /** 是否启用 SSL/TLS */
        private boolean enabled;

        /** KeyStore 文件路径（JKS/PKCS12） */
        private String keyStorePath;

        /** KeyStore 密码 */
        private String keyStorePassword;

        /** KeyStore 类型，默认 PKCS12 */
        private String keyStoreType = "PKCS12";

        /** SSL 证书文件路径（PEM） */
        private String certPath;

        /** SSL 私钥文件路径（PEM） */
        private String keyPath;

        /** SSL 私钥密码 */
        private String keyPassword;

        /** SSL 协议，默认 TLS */
        private String sslProtocol = "TLS";
    }
}
