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

    /**
     * 逻辑服名称
     */
    private final String name;
    /**
     * Action 扫描根类
     */
    private final Class<?> scanActionClass;
    /**
     * 是否开启调试模式
     */
    private final boolean debugMode;
    /**
     * BarSkeletonBuilder 自定义配置器
     */
    private final Consumer<BarSkeletonBuilder> skeletonConfigurer;

    /**
     * 创建 IonetLogicServer 实例
     * @param name name
     * @param scanActionClass scanActionClass
     * @param debugMode debugMode
     * @param skeletonConfigurer skeletonConfigurer
     */
    public IonetLogicServer(String name, Class<?> scanActionClass, boolean debugMode,
                            Consumer<BarSkeletonBuilder> skeletonConfigurer) {
        this.name = name;
        this.scanActionClass = scanActionClass;
        this.debugMode = debugMode;
        this.skeletonConfigurer = skeletonConfigurer;
    }

    @Override
    /** SettingBarSkeletonBuilder */
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
    /** SettingServerBuilder */
    public void settingServerBuilder(ServerBuilder builder) {
        builder.setName(name);
    }

    @Override
    /** StartupSuccess */
    public void startupSuccess(BarSkeleton barSkeleton) {
        log.info("[IonetLogicServer] {} started successfully", name);
    }
}