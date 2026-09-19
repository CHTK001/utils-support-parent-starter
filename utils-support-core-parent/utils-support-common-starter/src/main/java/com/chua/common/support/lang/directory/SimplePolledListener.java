package com.chua.common.support.lang.directory;

import lombok.extern.slf4j.Slf4j;

/**
 * 简单的 SLF4J 日志监听器，将文件变更事件输出到日志。
 * <p>适用于测试和调试场景，生产环境建议覆写或组合自定义监听器。</p>
 *
 * @author CH
 * @since 2024/12/12
 */
@Slf4j
public class SimplePolledListener implements PolledListener {

    @Override
    /**
     * On创建
    */
    public void onCreate(WatcherEvent event, EventObserver observer) {
        log.info("文件创建: {}/{}", observer.getCurrentPath(), observer.getTriggerFile());
    }

    @Override
    /**
     * OnModify
    */
    public void onModify(WatcherEvent event, EventObserver observer) {
        log.info("文件修改: {}/{}", observer.getCurrentPath(), observer.getTriggerFile());
    }

    @Override
    /**
     * On删除
    */
    public void onDelete(WatcherEvent event, EventObserver observer) {
        log.info("文件删除: {}/{}", observer.getCurrentPath(), observer.getTriggerFile());
    }

    @Override
    /**
     * OnOverflow
    */
    public void onOverflow(WatcherEvent event, EventObserver observer) {
        log.warn("事件溢出: {}/{}，部分事件可能丢失", observer.getCurrentPath(), observer.getTriggerFile());
    }
}
