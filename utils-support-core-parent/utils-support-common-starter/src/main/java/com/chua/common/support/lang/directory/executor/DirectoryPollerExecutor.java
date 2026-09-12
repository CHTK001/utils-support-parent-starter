package com.chua.common.support.lang.directory.executor;


/**
* 目录轮询执行器接口，抽象轮询任务的启停生命周期。
* <p>
* 实现类可基于虚拟线程、平台线程或定时任务线程池驱动轮询，
* 针对不同场景选择适合的执行策略：
* <ul>
*   <li>WatchService 场景 — 单线程事件驱动</li>
*   <li>FTP/SSH/DB 场景 — 虚拟线程定时轮询</li>
* </ul>
* </p>
*
* @author CH
* @since 2024/12/12
 */
public interface DirectoryPollerExecutor extends AutoCloseable {

    /**
    * 启动轮询任务。
     */
    void start();

    /**
    * 停止轮询任务并释放资源。
     */
    @Override
    void close();
}
