package com.chua.runtime.core.manager;

import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.runtime.core.model.RuntimeArtifact;
import com.chua.runtime.core.model.RuntimeStatus;

/**
* 运行时启动器 SPI 接口。
*
* <p>各 {@link com.chua.common.support.lang.cmd.RuntimeType} 通过 SPI 提供独立的启动/停止实现，
* 避免在 {@link DefaultRuntimeInstance} 中硬编码。容器类（Tomcat）可委托给
* {@link com.chua.common.support.network.container.WebContainer} 管理。</p>
*
* @author CH
* @since 4.0.0.42
 */
public interface RuntimeLauncher {

    /**
    * 当前启动器支持的 runtime类型 名称（如 {@code JAR}、{@code TOMCAT}）。
    *
    * @return 类型名
     */
    String type();

    /**
    * 启动工件。
    *
    * @param artifact 工件描述
    * @return 启动结果
     */
    CmdResult start(RuntimeArtifact artifact);

    /**
    * 停止工件。
    *
    * @param artifact 工件描述
    * @return 停止结果
     */
    CmdResult stop(RuntimeArtifact artifact);

    /**
    * 当前状态。
    *
    * @return 状态
     */
    RuntimeStatus status();

    /**
    * 按类型名查找启动器 SPI 实现。
    *
    * @param type 类型名
    * @return 命中的启动器；未注册返回 {@code null}
     */
    static RuntimeLauncher find(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        return ServiceProvider.of(RuntimeLauncher.class).getExtension(type);
    }
}