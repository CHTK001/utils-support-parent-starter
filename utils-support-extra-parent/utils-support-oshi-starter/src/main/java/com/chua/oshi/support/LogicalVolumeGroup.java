package com.chua.oshi.support;

import lombok.Data;

/**
* 逻辑卷组信息实体类（LVM）。
*
* @author CH
* @since 4.0.0
 */
@Data
public class LogicalVolumeGroup {

    /**
    * 卷组名称。
     */
    private String name;

    /**
    * 物理卷名称列表。
     */
    private String[] physicalVolumes;

    /**
    * 卷组总容量（字节）。
     */
    private long totalSize;

    /**
    * 卷组可用容量（字节）。
     */
    private long freeSpace;
}