package com.chua.common.support.network.client.yunxiao;

import lombok.Data;

/**
* 云效制品删除任务结果。
*
* <p>对应云效 OpenAPI {@code DeleteArtifact} 接口的返回实体，描述一次删除/恢复
* 批量任务的状态，包含行为类型、操作对象、任务 Id、仓库信息与任务状态等字段。</p>
*
* <p>字段命名与云效 OpenAPI 返回的 JSON 字段保持一致，由 Jackson 自动反序列化。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Data
public class YunxiaoDeleteResult {

    /**
    * 行为，取值：REPO_DEL 删除仓库、REPO_RES 恢复仓库、MODULE_DEL 删除制品、MODULE_RES 恢复制品
    */
    private String action;

    /**
    * 操作的对象信息
    */
    private String data;

    /**
    * 描述信息
    */
    private String description;

    /**
    * 任务创建时间（毫秒时间戳）
    */
    private Long gmtCreate;

    /**
    * 任务 Id
    */
    private Long id;

    /**
    * 仓库 Id
    */
    private String repoId;

    /**
    * 仓库类型
    */
    private String repoType;

    /**
    * 任务状态，取值：INIT 初始化、RUNNING 运行中、SUCCESS 运行成功、FAILED 运行失败
    */
    private String status;
}
