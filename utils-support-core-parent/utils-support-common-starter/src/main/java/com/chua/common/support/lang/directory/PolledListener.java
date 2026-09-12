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
}
