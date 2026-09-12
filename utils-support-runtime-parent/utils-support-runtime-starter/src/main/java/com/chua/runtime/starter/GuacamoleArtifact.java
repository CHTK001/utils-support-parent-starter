package com.chua.runtime.starter;

import com.chua.common.support.lang.cmd.RuntimeType;
import com.chua.runtime.core.model.RuntimeArtifact;

import java.nio.file.Paths;

/**
* Guacamole 远程网关工件示例 — 构建 Guacamole 远程访问网关。
*
* <p>Apache Guacamole 是基于浏览器的远程桌面网关，支持 RDP/VNC/SSH 协议。</p>
*
* <p>典型链式安装：</p>
* <pre>
* RuntimeBoot
*     .create()
*     .withArtifact(GuacamoleArtifact.builder()
*         .artifactId("guacamole")
*         .version("1.5.5")
*         .build())
*     .install()
*     .startAsService()
*     .run();
* </pre>
*
* @author CH
* @since 4.0.0.42
 */
public final class GuacamoleArtifact {

    /** 创建 guacamoleartifact 实例 */
    private GuacamoleArtifact() {
        // NOTHING
    }

    /**
    * 创建 Guacamole 工件构建器。
    *
    * @param artifactId 工件 标识
    * @param version    版本
    * @return 构建器
     */
    public static GuacamoleBuilder builder(String artifactId, String version) {
        return new GuacamoleBuilder(artifactId, version);
    }

    /**
    * 创建默认 Guacamole 工件。
    *
    * @return RuntimeArtifact
     */
    public static RuntimeArtifact createDefault() {
        return builder("guacamole", "1.5.5").build();
    }

    /**
    * 创建 Guacamole 服务端（guacd）工件。
    *
    * @return RuntimeArtifact
     */
    public static RuntimeArtifact createServer() {
        return builder("guacd", "1.5.5")
                .type(RuntimeType.NATIVE)
                .executable(Paths.get("/usr/local/bin/guacd"))
                .args("-b", "0.0.0.0", "-p", "4822")
                .build();
    }

    /**
    * 创建 Guacamole Web 应用（Tomcat）工件。
    *
    * @return RuntimeArtifact
     */
    public static RuntimeArtifact createWeb() {
        return builder("guacamole-web", "1.5.5")
                .type(RuntimeType.TOMCAT)
                .executable(Paths.get("apache-tomcat/bin/startup.sh"))
                .args()
                .build();
    }

    /**
    * Guacamole 构建器。
    *
    * @since 4.0.0.42
    * @author CH
     */
    public static class GuacamoleBuilder {

        /**
        * 工件 标识
         */
        private String artifactId;

        /**
        * 版本
         */
        private String version;

        /**
        * 运行时类型
         */
        private RuntimeType type = RuntimeType.JAR;

        /**
        * 可执行文件
         */
        private java.nio.file.Path executable;

        /**
        * 工作目录
         */
        private java.nio.file.Path workDir;

        /**
        * 参数
         */
        private java.util.List<String> args = new java.util.ArrayList<>();

        /**
        * 启动超时
         */
        private long startupTimeoutMs = 60_000;

        /**
        * 健康检查 URL
         */
        private String healthCheckUrl;

        GuacamoleBuilder(String artifactId, String version) {
            this.artifactId = artifactId;
            this.version = version;
        }

        /**
        * 设置运行时类型。
        *
        * @param type 类型
        * @return 自身
         */
        public GuacamoleBuilder type(RuntimeType type) {
            this.type = type;
            return this;
        }

        /**
        * 设置可执行文件。
        *
        * @param executable 路径
        * @return 自身
         */
        public GuacamoleBuilder executable(java.nio.file.Path executable) {
            this.executable = executable;
            return this;
        }

        /**
        * 设置工作目录。
        *
        * @param workDir 路径
        * @return 自身
         */
        public GuacamoleBuilder workDir(java.nio.file.Path workDir) {
            this.workDir = workDir;
            return this;
        }

        /**
        * 设置启动参数。
        *
        * @param args 参数
        * @return 自身
         */
        public GuacamoleBuilder args(String... args) {
            this.args = java.util.Arrays.asList(args);
            return this;
        }

        /**
        * 设置启动超时。
        *
        * @param timeoutMs 超时毫秒
        * @return 自身
         */
        public GuacamoleBuilder startupTimeout(long timeoutMs) {
            this.startupTimeoutMs = timeoutMs;
            return this;
        }

        /**
        * 设置健康检查 URL。
        *
        * @param url URL
        * @return 自身
         */
        public GuacamoleBuilder healthCheck(String url) {
            this.healthCheckUrl = url;
            return this;
        }

        /**
        * 构建工件。
        *
        * @return RuntimeArtifact
         */
        public RuntimeArtifact build() {
            return RuntimeArtifact.builder()
                    .id(artifactId + "-" + version)
                    .name("Guacamole " + artifactId + " " + version)
                    .type(type)
                    .executable(executable)
                    .workDir(workDir)
                    .args(args)
                    .startupTimeoutMs(startupTimeoutMs)
                    .healthCheckUrl(healthCheckUrl)
                    .autoRestart(true)
                    .maxRestartAttempts(3)
                    .build();
        }
    }
}
