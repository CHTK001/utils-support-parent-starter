package com.chua.common.support.lang.process;
import java.time.Duration;
import java.time.Instant;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
/**
* 进度状态，记录任务的进度信息。
* <p>
* 包含当前进度、最大值、开始时间、已消耗时间等状态数据。
*
* @author CH
* @since 2024-01-01
* @version 1.0.0
 */
public class ProgressState {
    /**
    * 任务名称
    */
    String taskName;
    /**
    * 附加消息
    */
    String extraMessage = "";
    /**
    * 是否为不定进度模式
    */
    boolean indefinite = false;
    /**
    * 起始位置
    * <p>
    * 进度条示意图：
    * [===============|=========>             ]
    *  0             start     current        max
    */
    long start;
    /**
    * 当前进度
    */
    long current;
    /**
    * 最大值
    */
    long max;
    /**
    * 开始时间
    */
    Instant startInstant;
    /**
    * 开始前已消耗时间
    */
    Duration elapsedBeforeStart;
    /**
    * 是否存活
    */
    volatile boolean alive = true;
    /**
    * 是否暂停
    */
    volatile boolean paused = false;
    /**
    *             
    *
    * @param taskName             
    * @param initialMax                               0                     
    * @param startFrom                
    * @param elapsedBeforeStart                      
    */
    ProgressState(String taskName, long initialMax, long startFrom, Duration elapsedBeforeStart) {
        this.taskName = taskName;
        if (initialMax < 0) {
            indefinite = true;
        } else {
            this.max = initialMax;
        }
        this.start = startFrom;
        this.current = startFrom;
        this.startInstant = Instant.now();
        this.elapsedBeforeStart = elapsedBeforeStart;
    }
    /**
    *                   
    *
    * @return             
    */
    public String getTaskName() {
        return taskName;
    }
    /**
    *                   
    *
    * @return             
    */
    public synchronized String getExtraMessage() {
        return extraMessage;
    }
    /**
    *                      
    *
    * @return                
    */
    public synchronized long getStart() {
        return start;
    }
    /**
    *                      
    *
    * @return                
    */
    public synchronized long getCurrent() {
        return current;
    }
    /**
    *                      
    *
    * @return                
    */
    public synchronized long getMax() {
        return max;
    }
    /**
    *                         0.0-1.0   
    *
    * @return                               0.0   1.0      
    */
    public synchronized double getNormalizedProgress() {
        if (max <= 0) {
            return 0.0;
        } else if (current > max) {
            return 1.0;
        } else {
            return ((double) current) / max;
        }
    }
    /**
    *                   
    *
    * @return             
    */
    public synchronized Instant getStartInstant() {
        return startInstant;
    }
    /**
    *                            
    *
    * @return                      
    */
    public synchronized Duration getElapsedBeforeStart() {
        return elapsedBeforeStart;
    }
    /**
    *                            
    *
    * @return                      
    */
    public synchronized Duration getElapsedAfterStart() {
        return (startInstant == null)
                ? Duration.ZERO
                : Duration.between(startInstant, Instant.now());
    }
    /**
    *                      
    *
    * @return                                   +                   
    */
    public synchronized Duration getTotalElapsed() {
        return getElapsedBeforeStart().plus(getElapsedAfterStart());
    }
    /**
    *                      
    *
    * @return true                      false                   
    */
    public synchronized boolean isIndefinite() {
        return indefinite;
    }
    /**
    *             
    *
    * @return true                      false                
    */
    public synchronized boolean isAlive() {
        return alive;
    }
    /**
    *             
    *
    * @return true                   false                
    */
    public synchronized boolean isPaused() {
        return paused;
    }
    /**
    */
    synchronized void setAsDefinite() {
        indefinite = false;
    }
    /**
    */
    synchronized void setAsIndefinite() {
        indefinite = true;
    }
    /**
    *                            
    *
    * @param n                
    */
    synchronized void maxHint(long n) {
        max = n;
    }
    /**
    *                   
    *
    * @param n             
    */
    synchronized void stepBy(long n) {
        current += n;
        if (current > max) {
            max = current;
        }
    }
    /**
    *                         
    *
    * @param n                
    */
    synchronized void stepTo(long n) {
        current = n;
        if (current > max) {
            max = current;
        }
    }
    /**
    *                   
    *
    * @param msg             
    */
    synchronized void setExtraMessage(String msg) {
        extraMessage = msg;
    }
    /**
    * <p>
    *                                              
    */
    synchronized void pause() {
        paused = true;
        start = current;
        elapsedBeforeStart = elapsedBeforeStart.plus(Duration.between(startInstant, Instant.now()));
    }
    /**
    * <p>
    *                               
    */
    synchronized void resume() {
        paused = false;
        startInstant = Instant.now();
    }
    /**
    * <p>
    *                                                 
    */
    synchronized void reset() {
        start = 0;
        current = 0;
        startInstant = Instant.now();
        elapsedBeforeStart = Duration.ZERO;
    }
    /**
    * <p>
    *                         false                        
    */
    synchronized void kill() {
        alive = false;
    }
}
