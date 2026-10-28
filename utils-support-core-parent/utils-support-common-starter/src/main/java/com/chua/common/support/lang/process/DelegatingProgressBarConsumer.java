package com.chua.common.support.lang.process;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
/**
 * 委托进度条消费者，将进度输出委托给指定 {@link Consumer}。
 * <p>
 * 适用于自定义进度输出逻辑的场景。
 *
 * @author Alex Peelman
 * @author CH
 * @since 2024-01-01
 * @version 1.0.0
 */
public class DelegatingProgressBarConsumer implements ProgressBarConsumer {
    /**
     * 最大进度显示长度
     */
    private final int maxProgressLength;
    /**
     * 委托的消费者
     */
    private final Consumer<String> consumer;
    /**
     * 构造 DelegatingProgressBarConsumer 实例。
     *             
     *
     * @param consumer                   
     */
    public DelegatingProgressBarConsumer(Consumer<String> consumer) {
        this(consumer, TerminalUtils.getTerminalWidth());
    }
    /**
     * 构造 DelegatingProgressBarConsumer 实例。
     *             
     *
     * @param consumer                   
     * @param maxProgressLength                      
     */
    public DelegatingProgressBarConsumer(Consumer<String> consumer, int maxProgressLength) {
        this.maxProgressLength = maxProgressLength;
        this.consumer = consumer;
    }
    /**
     * 获取 MaxRenderedLength。
     *                         
     *
     * @return                   
     */
    @Override
    public int getMaxRenderedLength() {
        return maxProgressLength;
    }
    /**
     * 接收并处理输入。
     *                                  
     *
     * @param str                               
     */
    @Override
    public void accept(String str) {
        this.consumer.accept(str);
    }
    /**
     * 关闭。
     * <p>
     *                                           
     */
    @Override
    public void close() {
        //          
    }
}
