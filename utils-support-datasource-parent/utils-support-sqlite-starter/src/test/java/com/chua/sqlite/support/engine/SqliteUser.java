package com.chua.sqlite.support.engine;

import com.chua.datasource.support.annotation.TableName;

/**
 * SQLite 引擎集成测试用实体。
 *
 * @author CH
 */
@TableName("t_sqlite_user")
public class SqliteUser {

    /** 主键 */
    private Long id;

    /** 用户名 */
    private String name;

    /** 年龄 */
    private Integer age;

    /** 部门编号（驼峰，验证驼峰转下划线列名解析） */
    private Long deptId;

    /** 金额 */
    private Double amount;

    /** 无参构造器 */
    public SqliteUser() {
    }

    /** 全参构造器 */
    public SqliteUser(Long id, String name, Integer age, Long deptId, Double amount) {
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
