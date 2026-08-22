package com.chua.common.support.task.timer;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
@Slf4j
public class HashedWheelTimer implements Timer {
    private static final AtomicLong ID_SEQ = new AtomicLong(0);
    private static final int DEFAULT_SLOTS = 512;
    private static final long DEFAULT_TICK_DURATION_MS = 1L;
    private final int mask;
    private final AtomicReferenceArray<TaskNode> wheel;
    private final long tickDurationNanos;
    private final AtomicLong tickCount = new AtomicLong(0);
    private volatile int taskCount = 0;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final java.util.concurrent.Executor executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Thread tickThread;
    public HashedWheelTimer(int slots,long tickDuration,TimeUnit timeUnit){
        this.tickDurationNanos=timeUnit.toNanos(tickDuration);
        int effectiveSlots=Integer.highestOneBit(slots)<<1;
        if(effectiveSlots<=slots)effectiveSlots<<=1;
        this.mask=effectiveSlots-1;
        this.wheel=new AtomicReferenceArray<>(effectiveSlots);
        for(int i=0;i<effectiveSlots;i++)wheel.set(i,new TaskNode(null,null,null,i));
        startTickLoop();
    }
    public static Timer newTimer(){return new HashedWheelTimer(DEFAULT_SLOTS,DEFAULT_TICK_DURATION_MS,TimeUnit.MILLISECONDS);}
    public static Timer newTimer(long tickDuration,TimeUnit unit){return new HashedWheelTimer(DEFAULT_SLOTS,tickDuration,unit);}
    public static Timer newTimer(int slots,long tickDuration,TimeUnit unit){return new HashedWheelTimer(slots,tickDuration,unit);}
    @Override public boolean schedule(TimerTask task){
        if(!running.get()||task==null||task.getDeadline()<=0)return false;
        long delayNanos=Math.max(0L,task.getDeadline()-System.nanoTime());
        long ticks=delayNanos/tickDurationNanos;
        int slot=calcSlot(tickCount.get(),ticks);
        TaskNode head=wheel.get(slot);
        TaskNode newNode=new TaskNode(null,head.getNext(),task,slot);
        if(head.getNext()!=null)head.getNext().setPrev(newNode);
        head.setNext(newNode);
        task.setNode(newNode);task.setSlotIndex(slot);taskCount++;
        return true;
    }
    @Override public TimerTask schedule(Runnable task,long delay,TimeUnit timeUnit){
        String id="tw-"+ID_SEQ.getAndIncrement();
        TimerTask t=new TimerTask(id,id,task,System.nanoTime()+timeUnit.toNanos(delay));
        schedule(t);return t;
    }
    @Override public TimerTask scheduleAtFixedRate(Runnable task,long initialDelay,long period,TimeUnit timeUnit){
        String id="tw-"+ID_SEQ.getAndIncrement();
        long deadline=System.nanoTime()+timeUnit.toNanos(initialDelay);
        long periodNanos=timeUnit.toNanos(period);
        TimerTask[] outer={null};
        outer[0]=new TimerTask(id,id,()->{
            if(task!=null)task.run();
            long nd=System.nanoTime()+periodNanos;
            TimerTask[] nextHolder={null};
            nextHolder[0]=new TimerTask(id,id,()->{task.run();scheduleRateTask(nextHolder[0],periodNanos);},nd);
            schedule(nextHolder[0]);
        },deadline);
        schedule(outer[0]);return outer[0];
    }
    private void scheduleRateTask(TimerTask prev,long periodNanos){
        long nd=System.nanoTime()+periodNanos;
        TimerTask[] nextHolder={null};
        nextHolder[0]=new TimerTask(prev.getId(),prev.getName(),()->{prev.getTask().run();scheduleRateTask(nextHolder[0],periodNanos);},nd);
        schedule(nextHolder[0]);
    }
    @Override public boolean cancel(TimerTask task){
        if(task==null||!running.get()||!task.cancel())return false;
        TaskNode node=task.getNode();if(node==null)return true;
        unlink(wheel.get(node.getSlotIndex()),node);taskCount--;return true;
    }
    private void unlink(TaskNode head,TaskNode node){
        TaskNode prev=node.getPrev(),next=node.getNext();
        if(prev!=null)prev.setNext(next);else head.setNext(next);
        if(next!=null)next.setPrev(prev);
        node.setPrev(null);node.setNext(null);
    }
    @Override public long getTickCount(){return tickCount.get();}
    @Override public int getTaskCount(){return taskCount;}
    @Override public boolean isRunning(){return running.get();}
    @Override public void shutdown(){
        if(!running.compareAndSet(true,false))return;
        log.info("shutdown tickCount={}",tickCount.get());
        for(int i=0;i<wheel.length();i++){TaskNode cur=wheel.get(i).getNext();while(cur!=null){cur.getTask().cancel();cur=cur.getNext();}wheel.get(i).setNext(null);}
        taskCount=0;Thread t=tickThread;if(t!=null&&t.isAlive())t.interrupt();
    }
    private void startTickLoop(){tickThread=Thread.ofPlatform().daemon(true).name("time-wheel-tick").start(this::tickLoop);}
    private void tickLoop(){
        long nanoTime=System.nanoTime();
        while(running.get()){
            try{long sleepNanos=tickDurationNanos-(System.nanoTime()-nanoTime);if(sleepNanos>0)Thread.sleep(sleepNanos/1_000_000,(int)(sleepNanos%1_000_000));nanoTime=System.nanoTime();processCurrentSlot();tickCount.incrementAndGet();}
            catch(InterruptedException e){if(running.get())Thread.currentThread().interrupt();break;}
            catch(Exception e){log.warn("tick error",e);}
        }
    }
    private void processCurrentSlot(){
        int slot=(int)(tickCount.get()&mask);
        TaskNode head=wheel.get(slot);
        List<TimerTask>fired=new java.util.ArrayList<>();
        long now=System.nanoTime();
        for(TaskNode cur=head.getNext();cur!=null;){
            TimerTask task=cur.getTask();
            if(!task.isCancelled()&&task.isDeadline(now)){fired.add(task);TaskNode next=cur.getNext();unlink(head,cur);cur=next;taskCount--;}
            else{cur=cur.getNext();}
        }
        for(TimerTask t:fired)executor.execute(t::run);
    }
    private int calcSlot(long currentTick,long ticks){return (int)((currentTick+ticks)&mask);}
}
