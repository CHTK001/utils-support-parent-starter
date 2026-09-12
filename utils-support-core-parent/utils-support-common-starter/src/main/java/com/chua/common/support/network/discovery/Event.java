package com.chua.common.support.network.discovery;

import lombok.Getter;

/**
* 服务发现事件类型枚举。
* 用于标识服务实例在注册中心中的状态变化或生命周期事件。
* @author CH
* @since 4.0.0.42
 */
@Getter
public enum Event {

    /**
    * 新增服务实例
     */
    ADD("add"),

    /**
    * 更新服务实例信息
     */
    UPDATE("update"),

    /**
    * 移除服务实例
     */
    REMOVE("remove"),

    /**
    * 服务实例下线（不可用）
     */
    OFFLINE("offline"),

    /**
    * 服务实例上线（可用）
     */
    ONLINE("online");

    /**
    * 名称
     */
    private final String name;

    Event(String name) {
        this.name = name;
    }

}
