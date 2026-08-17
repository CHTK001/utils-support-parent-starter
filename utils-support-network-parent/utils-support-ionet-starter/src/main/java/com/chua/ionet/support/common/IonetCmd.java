package com.chua.ionet.support.common;

import com.iohao.net.framework.core.CmdInfo;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * ionet 路由常量工具类 — 简化 CmdInfo 的创建
 * <p>
 * 使用示例：
 * <pre>
 * public interface MyCmd extends IonetCmd {
 *     int cmd = 1;
 *     int hello = 0;          // 子路由 1-0
 *     int echo = 1;           // 子路由 1-1
 *     CmdInfo helloCmd = of(cmd, hello);
 *     CmdInfo echoCmd = of(cmd, echo);
 * }
 * </pre>
 *
 * @author CH
 */
public interface IonetCmd {

    /**
     * 创建 CmdInfo 路由对象
     *
     * @param cmd    主路由
     * @param subCmd 子路由
     * @return CmdInfo
     */
    static CmdInfo of(int cmd, int subCmd) {
        return CmdInfo.of(cmd, subCmd);
    }

    /**
     * 动态分配子路由（从指定起始值递增）
     * <p>
     * 适用于广播路由等需要动态分配子路由号的场景：
     * <pre>
     * AtomicInteger broadcastIdx = IonetCmd.broadcastIndex(20);
     * CmdInfo listenData = of(cmd, broadcastIdx.getAndIncrement());
     * CmdInfo listenList = of(cmd, broadcastIdx.getAndIncrement());
     * </pre>
     */
    static AtomicInteger broadcastIndex(int start) {
        return new AtomicInteger(start);
    }
}