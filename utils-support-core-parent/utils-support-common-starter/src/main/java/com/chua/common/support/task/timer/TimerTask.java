package com.chua.common.support.task.timer;
import lombok.Data;
import lombok.ToString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
@Data
@ToString
public class TimerTask {
    private static final Logger log = LoggerFactory.getLogger(TimerTask.class);
    private final String id;
    private final Runnable task;
    private final String name;
    private final long deadline;
    private final long period;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicInteger executeCount = new AtomicInteger(0);
    private volatile int slotIndex = -1;
    private volatile TaskNode node;
    public TimerTask(String id,String name,Runnable task,long deadline){this(id,name,task,deadline,-1L);}
    public TimerTask(String id,String name,Runnable task,long deadline,long period){this.id=id;this.name=name!=null?name:id;this.task=task;this.deadline=deadline;this.period=period;}
    public boolean run(){
        if(isCancelled())return false;
        if(task==null)return true;
        executeCount.incrementAndGet();
        try{task.run();return true;}
        catch(Exception e){log.warn("error: "+e.getMessage());return false;}
    }
    public boolean cancel(){return cancelled.compareAndSet(false,true);}
    public boolean isCancelled(){return cancelled.get();}
    public boolean isDeadline(long now){return now>=deadline;}
    public long nextDeadline(){return period<=0?-1L:deadline+period;}
    public int getExecuteCount(){return executeCount.get();}
}
