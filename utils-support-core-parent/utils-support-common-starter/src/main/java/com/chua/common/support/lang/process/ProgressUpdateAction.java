package com.chua.common.support.lang.process;


/**
* 进度更新动作，负责定时刷新进度条显示。
* <p>
* 定期调用渲染器和消费者，将进度状态渲染并输出到终端。
*
* @author CH
* @since 2024-01-01
* @version 1.0.0
 */
class ProgressUpdateAction implements Runnable {

    /**
    * 进度状态
     */
    ProgressState progress;

    /**
    * 进度条渲染器
     */
    private final ProgressBarRenderer renderer;

    /**
    * 进度条消费者
     */
    private final ProgressBarConsumer consumer;

    /**
    * 是否持续更新（不跳过重复值）
     */
    private final boolean continuousUpdate;

    /**
    * 完成后是否清除显示
     */
    private final boolean clearDisplayOnFinish;

    /**
    * 上次刷新的进度值
     */
    volatile private long last;

    /**
    * 是否为首次刷新
     */
    volatile private boolean first;

    /**
    *             
    *
    * @param progress                      
    * @param renderer                   
    * @param consumer                   
    * @param continuousUpdate                   
    * @param clearDisplayOnFinish                            
     */
    ProgressUpdateAction(
            ProgressState progress,
            ProgressBarRenderer renderer,
            ProgressBarConsumer consumer,
            boolean continuousUpdate,
            boolean clearDisplayOnFinish
    ) {
        this.progress = progress;
        this.renderer = renderer;
        this.consumer = consumer;
        this.continuousUpdate = continuousUpdate;
        this.clearDisplayOnFinish = clearDisplayOnFinish;
        this.last = progress.start;
        this.first = true;
    }

    /**
    *                      
    * <p>
    *                                                                                     
     */
    void refresh() {
        if (continuousUpdate || (progress.current > last)) {
            forceRefresh();
        }
        //                                                                               #91   
    }

    /**
    *                            
    * <p>
    *                                                                
     */
    public void forceRefresh() {
        String rendered = renderer.render(progress, consumer.getMaxRenderedLength());
        consumer.accept(rendered);
        last = progress.current;
    }

    /**
    *                   
    * <p>
    *                                                                                        
    *                                                                   
     */
    @Override
    public void run() {
        if (first) {
            forceRefresh();
            first = false;
        } else {
            if (!progress.paused) {
                refresh();
            }
            if (!progress.alive) {
                forceRefresh();
                if (clearDisplayOnFinish) {
                    consumer.clear();
                }
                consumer.close();
                TerminalUtils.closeTerminal();
            }
        }
    }

}
