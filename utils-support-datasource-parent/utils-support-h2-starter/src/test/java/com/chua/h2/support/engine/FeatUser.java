package com.chua.datasource.support.engine;

import com.chua.datasource.support.annotation.TableName;

/**
 * 功能点测试实体。对应表名：featuser（类名小写）。
 */
@TableName("featuser")
public class FeatUser {
    private Integer id;
    private String name;
    private Integer age;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }
}
