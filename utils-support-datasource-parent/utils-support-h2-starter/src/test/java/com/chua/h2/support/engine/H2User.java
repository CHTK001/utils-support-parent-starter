package com.chua.h2.support.engine;

import com.chua.datasource.support.annotation.TableName;

/**
 * H2 引擎集成测试用实体。
 *
 * @author CH
 */
@TableName("t_h2_user")
public class H2User {

    /**
     * 主键
    */
    private Long id;

    /**
     * 用户名
    */
    private String name;

    /**
     * 年龄
    */
    private Integer age;

    /**
     * 部门编号（驼峰，验证驼峰转下划线列名解析）
    */
    private Long deptId;

    /**
     * 金额
    */
    private Double amount;

    /**
     * 无参构造器
    */
    public H2User() {
    }

    /**
     * 全参构造器
     * @param id ID，不允许为 null
     * @param name 名称，不允许为 null
     * @param age 方法入参 age
     * @param deptId deptID，不允许为 null
     * @param amount 方法入参 amount
     */
    public H2User(Long id, String name, Integer age, Long deptId, Double amount) {
        this.id = id;
        this.name = name;
        this.age = age;
        this.deptId = deptId;
        this.amount = amount;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }

    public Long getDeptId() { return deptId; }
    public void setDeptId(Long deptId) { this.deptId = deptId; }

    public Double getAmount() { return amount; }
    public void setAmount(Double amount) { this.amount = amount; }
}
