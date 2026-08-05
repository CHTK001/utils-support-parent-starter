package com.chua.common.support.lang.process;

import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 *                                                       
 * <p>
 *           Consumer   Appendable     AutoCloseable                                             
 *                                                                                                          
 *
 * @author CH
 * @since 2023-04-05
 * @version 1.0.0
 */
public interface ProgressBarConsumer extends Consumer<String>, Appendable, AutoCloseable {

    /**
     *                                                 
     *
     * @return                                     
     * @example getMaxRenderedLength()                   80                     80                           
     */
    int getMaxRenderedLength();

    /**
     *                                              
     *
     * @param rendered                                         "[#####-----] 50% Completed"
     * @example accept("[#####-----] 50% Completed")                                        
     */
    @Override
    void accept(String rendered);

    /**
     *                            
     * 
     * @example clear()                                                       
     */
    default void clear() {
        accept("\r" + Util.repeat(' ', getMaxRenderedLength()) + "\r");
    }

    /**
     *                               
     *
     * @param csq                                   "Processing..."
     * @return                               
     * @example append("Processing...")                               Processing...      
     */
    @Override
    default ProgressBarConsumer append(CharSequence csq) {
        accept(csq.toString());
        return this;
    }

    /**
     *                                              
     *
     * @param csq                                     "Processing data..."
     * @param start                                   0
     * @param end                                        10
     * @return                               
     * @example append("Processing data...", 0, 10)             "Processing"      
     */
    @Override
    default ProgressBarConsumer append(CharSequence csq, int start, int end) {
        accept(csq.subSequence(start, end).toString());
        return this;
    }

    /**
     *                               
     *
     * @param c                             '.'
     * @return                               
     * @example append('.')                                           
     */
    @Override
    default ProgressBarConsumer append(char c) {
        accept(String.valueOf(c));
        return this;
    }

    /**
     *                                              
     * 
     * @throws RuntimeException                                                                                           
     * @example close()                         
     */
    @Override
    void close();

}