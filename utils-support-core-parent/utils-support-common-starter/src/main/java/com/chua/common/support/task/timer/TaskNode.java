package com.chua.common.support.task.timer;
import lombok.Data;
import lombok.NoArgsConstructor;
@Data
@NoArgsConstructor
public class TaskNode {
    private TaskNode prev;
    private TaskNode next;
    private TimerTask task;
    private int slotIndex;
    public TaskNode(TaskNode prev,TaskNode next,TimerTask task,int slotIndex){this.prev=prev;this.next=next;this.task=task;this.slotIndex=slotIndex;}
}
