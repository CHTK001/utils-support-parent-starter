package com.chua.nitrite.support.engine;

import org.dizitart.no2.repository.annotations.Id;

/**
 * Nitrite 仓库测试实体（顶层公有类，Nitrite 对象映射要求）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class User {

    /**
     * 标识
     */
    @Id
    public String id;

    /**
     * 名称
     */
    public String name;

    /**
     * 年龄
     */
    public Integer age;

    public User() {
    }

    public User(String id, String name, Integer age) {
        this.id = id;
        this.name = name;
        this.age = age;
    }
}
