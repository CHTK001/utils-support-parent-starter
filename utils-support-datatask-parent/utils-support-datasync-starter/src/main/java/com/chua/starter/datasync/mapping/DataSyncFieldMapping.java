package com.chua.starter.datasync.mapping;

import java.util.List;
import java.util.Map;

/**
* 数据同步字段映射。
*
* @author CH
* @since 4.0.0.42
 */
public interface DataSyncFieldMapping {

    /**
    * 获取源字段名。
    *
    * @return 源字段名
     */
    String sourceField();

    /**
    * 获取目标字段名。
    *
    * @return 目标字段名
     */
    String targetField();

    /**
    * 获取类型转换器。
    *
    * @return 类型转换器
     */
    String converter();
}
