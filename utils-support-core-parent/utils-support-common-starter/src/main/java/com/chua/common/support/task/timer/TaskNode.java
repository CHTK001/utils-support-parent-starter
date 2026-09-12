package com.chua.common.support.task.timer;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
* 哈希轮定时器节点，构成双向链表。
*
* @author CH
* @since 4.0.0.42
 */
@Data
@NoArgsConstructor
public class TaskNode {

    /** 前驱节点 */
    private TaskNode prev;
    /** 后继节点 */
    private TaskNode next;
    /** 绑定的定时器任务 */
    private TimerTask task;
    /** 所在槽位索引 */
    private int slotIndex;

    /**
    * 创建节点。
    *
    * @param prev       前驱节点
    * @param next       后继节点
    * @param task       定时器任务
    * @param slotIndex  槽位索引
     */
    public TaskNode(TaskNode prev, TaskNode next, TimerTask task, int slotIndex) {
        this.prev = prev;
        this.next = next;
        this.task = task;
        this.slotIndex = slotIndex;
    }
}
