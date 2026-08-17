package com.chua.ionet.support.server;

import com.iohao.net.framework.core.BarSkeleton;
import com.iohao.net.framework.core.BarSkeletonBuilder;
import com.iohao.net.framework.core.flow.internal.DebugInOut;
import com.iohao.net.framework.protocol.ServerBuilder;
import com.iohao.net.server.LogicServer;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/**
 * ionet 逻辑服封装 — 实现 LogicServer 接口
 * <p>
 * 封装 BarSkeletonBuilder（业务框架）和 ServerBuilder（逻辑服配置）的设置，
 * 开发者只需提供 Action 扫描根类即可。
 *
 * @author CH
 */
@Slf4j
public class IonetLogicServer implements LogicServer {

    private final String name;
    private final Class<?> scanActionClass;
    private final boolean debugMode;
    private final Consumer<BarSkeletonBuilder> skeletonConfigurer;

    public IonetLogicServer(String name, Class<?> scanActionClass, boolean debugMode,
                            Consumer<BarSkeletonBuilder> skeletonConfigurer) {
        this.name = name;
        this.scanActionClass = scanActionClass;
        this.debugMode = debugMode;
        this.skeletonConfigurer = skeletonConfigurer;
    }

    @Override
    public void settingBarSkeletonBuilder(BarSkeletonBuilder builder) {
        // 扫描 Action 类所在包
        builder.scanActionPackage(scanActionClass);

        // 开启调试插件
        if (debugMode) {
            builder.addInOut(new DebugInOut());
        }

        // 用户自定义扩展
        if (skeletonConfigurer != null) {
            skeletonConfigurer.accept(builder);
        }
    }

    @Override
    public void settingServerBuilder(ServerBuilder builder) {
        builder.setName(name);
    }

    @Override
    public void startupSuccess(BarSkeleton barSkeleton) {
        log.info("[IonetLogicServer] {} started successfully", name);
    }
}