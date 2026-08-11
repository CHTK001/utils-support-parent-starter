package com.chua.common.support.lang.process;

import com.chua.common.support.utils.ThreadUtils;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.Optional;
import java.util.Spliterator;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * 工具类，提供进度条相关的静态工具方法。
 * <p>
 * 包含线程池管理、控制台消费者创建、字符重复等通用功能。
 *
 * @author CH
 * @since 2024-01-01
 * @version 1.0.0
 */
class Util {

    /**
     * 进度条定时刷新线程池
     * <p>
     * 单线程调度池，用于定时刷新进度条显示。
     */
    static ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, ThreadUtils.newDaemonThreadFactory("ProgressBar"));

    /**
     *                                                    
     *
     * @param predefinedWidth                
     * @return                   
     */
    static ConsoleProgressBarConsumer createConsoleConsumer(int predefinedWidth) {
        PrintStream real = new PrintStream(new FileOutputStream(FileDescriptor.out));
        return createConsoleConsumer(real, predefinedWidth);
    }

    /**
     *                                                    
     *
     * @param out          
     * @return                   
     */
    static ConsoleProgressBarConsumer createConsoleConsumer(PrintStream out) {
        return createConsoleConsumer(out, -1);
    }

    /**
     *                         
     * <p>
     *                                                                                        
     *
     * @param out          
     * @param predefinedWidth                   -1                        
     * @return                   
     */
    static ConsoleProgressBarConsumer createConsoleConsumer(PrintStream out, int predefinedWidth) {
        return TerminalUtils.hasCursorMovementSupport()
                ? new ConsoleProgressBarConsumer(out, predefinedWidth)
                : new ConsoleProgressBarConsumer(out, predefinedWidth);
    }

    /**
     *                         
     *
     * @param c                   
     * @param n             
     * @return                      
     */
    static String repeat(char c, int n) {
        if (n <= 0) {
            return "";
        }
        char[] s = new char[n];
        for (int i = 0; i < n; i++) {
            s[i] = c;
        }
        return new String(s);
    }

    /**
     *                      
     * <p>
     *    Duration             "   :   :   "       
     *
     * @param d             
     * @return                                           HH:MM:SS   
     */
    static String formatDuration(Duration d) {
        long s = d.getSeconds();
        return String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    /**
     *                               
     * <p>
     *                                                                   
     *
     * @param progress             
     * @return                                                    
     */
    static Optional<Duration> linearEta(ProgressState progress) {
        if (progress.getMax() <= 0 || progress.isIndefinite()) {
            return Optional.empty();
        } else if (progress.getCurrent() - progress.getStart() == 0) {
            return Optional.empty();
        } else {
            return Optional.of(
                    progress.getElapsedAfterStart()
                            .dividedBy(progress.getCurrent() - progress.getStart())
                            .multipliedBy(progress.getMax() - progress.getCurrent())
            );
        }
    }

    /**
     *                         
     * <p>
     *                                                                                  available()            
     *
     * @param is          
     * @return                                                       -1
     */
    static long getInputStreamSize(InputStream is) {
        try {
            if (is instanceof FileInputStream) {
                return ((FileInputStream) is).getChannel().size();
            }

            //        InputStream::available                      
            int available = is.available();
            if (available > 0) {
                return available;
            }
        } catch (IOException ignored) {
            //             
        }
        return -1;
    }

    /**
     *       Spliterator         
     *
     * @param sp Spliterator      
     * @param <T>             
     * @return Spliterator                                    Long.MAX_VALUE         -1
     */
    static <T> long getSpliteratorSize(Spliterator<T> sp) {
        try {
            long size = sp.estimateSize();
            return size != Long.MAX_VALUE ? size : -1;
        } catch (Exception ignored) {
            //             
        }
        return -1;
    }
}
