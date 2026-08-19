package com.chua.ionet.support.common;

import com.iohao.net.framework.annotations.ActionController;
import com.iohao.net.framework.annotations.ActionMethod;
import com.iohao.net.framework.core.BarSkeletonBuilder;

/**
 * ionet Action 工具类 — 提供常用的 Action 注册辅助方法
 * <p>
 * ionet 的 Action 需要使用 {@link ActionController} 和 {@link ActionMethod} 注解，
 * 本类提供辅助方法简化 BarSkeletonBuilder 的配置。
 *
 */
public final class IonetActions {

    private IonetActions() {}

    /**
     * 扫描多个 Action 类所在包
     * <p>
     * 每个传入的类，其所在包及子包下的所有 ActionController 类都会被注册。
     *
     * @param builder    业务框架构建器
     * @param actionClasses Action 类（只需传入每个包中的任意一个类）
     */
    public static void scanActionPackages(BarSkeletonBuilder builder, Class<?>... actionClasses) {
        for (Class<?> actionClass : actionClasses) {
            builder.scanActionPackage(actionClass);
        }
    }
}