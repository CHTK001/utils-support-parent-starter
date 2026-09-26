package com.chua.common.support.lang.directory;


/**
 * 目录轮询监听器接口，定义文件变更事件的回调方法。
 * <p>所有方法均为 default 空实现，使用者只需按需覆写关心的回调。</p>
 *
 * @author CH
 * @since 2024/12/12
 */
public interface PolledListener {

    /**
     * 文件或目录创建时回调。
     *
     * @param event    事件类型（始终为 {@link WatcherEvent#CREATE}）
     * @param observer 事件观察者，包含路径、文件名称、时间戳等信息
     */
    default void onCreate(WatcherEvent event, EventObserver observer) {}

    /**
     * 文件或目录内容修改时回调。
     *
     * @param event    事件类型（始终为 {@link WatcherEvent#MODIFY}）
     * @param observer 事件观察者
     */
    default void onModify(WatcherEvent event, EventObserver observer) {}

    /**
     * 文件或目录删除时回调。
     *
     * @param event    事件类型（始终为 {@link WatcherEvent#DELETE}）
     * @param observer 事件观察者
     */
    default void onDelete(WatcherEvent event, EventObserver observer) {}

    /**
     * WatchEvent 溢出时回调，通常表示系统事件队列溢出导致部分事件丢失。
     *
     * @param event    事件类型（始终为 {@link WatcherEvent#OVERFLOW}）
     * @param observer 事件观察者
     */
    default void onOverflow(WatcherEvent event, EventObserver observer) {}

    /**
     * 轮询或分发过程中发生异常时回调。
     *
     * <p>异常若只写日志，业务方无法感知"我的监听器实际上已经不工作了"。
     * 本回调把异常沿监听器链路暴露出来，由监听器自行决定降级、重试还是上报告警。</p>
     *
     * <p>事件类型以 {@link WatcherEvent#OVERFLOW} 传递，语义为"事件流不可靠"，
     * 观察者中的路径与文件名指向异常发生的数据源，可为空。</p>
     *
     * @param event    事件类型，固定为 {@link WatcherEvent#OVERFLOW}
     * @param observer 事件观察者，携带出错的数据源路径与文件名
     * @param error    异常
     */
    default void onError(WatcherEvent event, EventObserver observer, Throwable error) {}
}
