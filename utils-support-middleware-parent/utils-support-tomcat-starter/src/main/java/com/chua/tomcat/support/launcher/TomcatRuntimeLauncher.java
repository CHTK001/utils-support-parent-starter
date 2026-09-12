package com.chua.tomcat.support.launcher;

import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.network.container.*;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.runtime.core.manager.RuntimeLauncher;
import com.chua.runtime.core.model.RuntimeArtifact;
import com.chua.runtime.core.model.RuntimeStatus;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.List;

/**
* Tomcat 运行时启动器 — 通过 {@link WebContainer} SPI 内嵌部署 WAR。
*
* <p>artifact 约定：
* <ul>
*   <li>{@code executable} — WAR 文件路径</li>
*   <li>{@code args[0]} — 部署路径（默认 "/"）</li>
*   <li>{@code args[1]} — 端口（默认 8080）</li>
*   <li>{@code args[2]} — 容器 SPI 名称（默认 "tomcat"，可选 "undertow"）</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi("TOMCAT")
public class TomcatRuntimeLauncher implements RuntimeLauncher {

    /** 容器 */
    private volatile WebContainer container;

    @Override
    /** 类型 */
    public String type() {
        return "TOMCAT";
    }

    @Override
    /** 开始 */
    public CmdResult start(RuntimeArtifact artifact) {
        if (container != null && container.isRunning()) {
            return CmdResult.builder()
                    .exitCode(0)
                    .command(artifact.getName() + " 已在运行")
                    .build();
        }

        try {
            Path warPath = artifact.getExecutable();
            if (warPath == null) {
                return CmdResult.builder()
                        .exitCode(CmdResult.EXIT_CODE_ERROR)
                        .stderr("TOMCAT artifact 缺少 WAR 文件路径")
                        .command(artifact.getName())
                        .build();
            }

            List<String> args = artifact.getArgs();
            String contextPath = (args != null && args.size() > 0 && args.get(0) != null)
                    ? args.get(0) : "/";
            int port = (args != null && args.size() > 1 && args.get(1) != null)
                    ? Integer.parseInt(args.get(1)) : 8080;
            String containerName = (args != null && args.size() > 2 && args.get(2) != null)
                    ? args.get(2) : "tomcat";

 // 通过 SPI 加载 web容器
            container = ServiceProvider.of(WebContainer.class).getExtension(containerName);
            if (container == null) {
                return CmdResult.builder()
                        .exitCode(CmdResult.EXIT_CODE_ERROR)
                        .stderr("未找到 WebContainer SPI: " + containerName)
                        .command(artifact.getName())
                        .build();
            }

            WebContainerSetting setting = new WebContainerSetting();
            setting.setPort(port);
            setting.setHost("0.0.0.0");
            WebContainerSetting.DeployUnit unit = new WebContainerSetting.DeployUnit();
            unit.setPath(warPath.toAbsolutePath().toString());
            unit.setContextPath(contextPath);
            unit.setType(DeployUnitType.WAR);
            unit.setAutoDeploy(true);
            setting.addDeployUnit(unit);

            container.initialize(setting);
            container.deployAll();
            container.start();

            log.info("TomcatRuntimeLauncher 启动成功: port={} contextPath={} war={}", port, contextPath, warPath);
            return CmdResult.builder()
                    .exitCode(0)
                    .stdout("工件[" + artifact.getId() + "] 启动成功，端口: " + port)
                    .command(artifact.getName())
                    .build();
        } catch (Exception e) {
            log.error("TomcatRuntimeLauncher 启动失败", e);
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .stderr(e.getMessage())
                    .command(artifact.getName())
                    .throwable(e)
                    .build();
        }
    }

    @Override
    /** 停止 */
    public CmdResult stop(RuntimeArtifact artifact) {
        if (container == null) {
            return CmdResult.builder().exitCode(0).command(artifact.getName() + " 未在运行").build();
        }
        try {
            container.stop();
            container = null;
            log.info("TomcatRuntimeLauncher 已停止");
            return CmdResult.builder().exitCode(0).build();
        } catch (Exception e) {
            log.error("TomcatRuntimeLauncher 停止失败", e);
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .stderr(e.getMessage())
                    .throwable(e)
                    .build();
        }
    }

    @Override
    /** 状态 */
    public RuntimeStatus status() {
        if (container != null && container.isRunning()) {
            return RuntimeStatus.RUNNING;
        }
        return RuntimeStatus.STOPPED;
    }
}