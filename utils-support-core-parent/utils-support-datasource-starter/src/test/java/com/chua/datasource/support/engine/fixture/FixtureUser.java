package com.chua.datasource.support.engine.fixture;

/**
 * 引擎测试用用户实体（驼峰字段，验证驼峰转下划线列名解析）。
 *
 * @author CH
 */
public class FixtureUser {

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
     * 部门编号
    */
    private Long deptId;

    /**
     * 金额
    */
    private Double amount;

    /**
     * 无参构造器。
     */
    public FixtureUser() {
    }

    /**
     * 全参构造器。
     *
     * @param id     主键
     * @param name   用户名
     * @param age    年龄
     * @param deptId 部门编号
     * @param amount 金额
     */
    public FixtureUser(Long id, String name, Integer age, Long deptId, Double amount) {
        this.id = id;
        this.name = name;
        this.age = age;
        this.deptId = deptId;
        this.amount = amount;
    }

    /**
     * 获取主键。
     *
     * @return 主键
     */
    public Long getId() {
        return id;
    }

    /**
     * 设置主键。
     *
     * @param id 主键
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * 获取用户名。
     *
     * @return 用户名
     */
    public String getName() {
        return name;
    }

    /**
     * 设置用户名。
     *
     * @param name 用户名
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 获取年龄。
     *
     * @return 年龄
     */
    public Integer getAge() {
        return age;
    }

    /**
     * 设置年龄。
     *
     * @param age 年龄
     */
    public void setAge(Integer age) {
        this.age = age;
    }

    /**
     * 获取部门编号。
     *
     * @return 部门编号
     */
    public Long getDeptId() {
        return deptId;
    }

    /**
     * 设置部门编号。
     *
     * @param deptId 部门编号
     */
    public void setDeptId(Long deptId) {
        this.deptId = deptId;
    }

    /**
     * 获取金额。
     *
     * @return 金额
     */
    public Double getAmount() {
        return amount;
    }

    /**
     * 设置金额。
     *
     * @param amount 金额
     */
    public void setAmount(Double amount) {
        this.amount = amount;
    }
}
