package com.chua.datasource.support.process;

import java.util.List;

/**
 * 会话进程管理器 SPI 接口。
 *
 * <p>与 {@code UserManager} / {@code IndexManager} 同一套注册方式：实现按方言协议登记在
 * {@code META-INF/extensions/com.chua.datasource.support.process.ProcessManager} 里，
 * 引擎侧用 {@code ServiceProvider.of(ProcessManager.class).getExtension(dialect.protocol())}
 * 取实现，取不到即该内核没有会话管理能力（返回 {@code null}，不抛异常）。</p>
 *
 * <p>能力探测与数据读取共用这一个接口：调用方能拿到实现 → 就有「显示进程」入口；
 * 拿不到 → 入口不该出现。</p>
 *
 * @author CH
 * @since 4.0.0.44
 */
public interface ProcessManager {

    /**
     * 返回 SPI 扩展键（与方言协议同名，如 {@code mysql}）。
     *
     * @return 数据库类型标识
     */
    String type();

    /**
     * 列出当前所有会话。
     *
     * @return 会话列表；无权限或内核不支持时实现应抛出异常，由调用方降级
     */
    List<ProcessInfo> listProcesses();

    /**
     * 结束指定会话。
     *
     * @param processId 会话 ID（{@link ProcessInfo#id()}）
     * @return 是否执行成功
     */
    boolean killProcess(String processId);
}
