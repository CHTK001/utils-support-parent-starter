package com.chua.neo4j.support.engine;

/**
 * Neo4j 引擎集成测试用实体。
 *
 * <p>节点标签取实体类简单名 {@code Person}，字段由 getter 提取写入节点属性。</p>
 *
 * @author CH
 */
public class Person {

    /** 主键 */
    private Long id;

    /** 姓名 */
    private String name;

    /** 年龄 */
    private Integer age;

    /** 无参构造器 */
    public Person() {
    }

    /**
     * 全参构造器
     * @param id ID，不允许为 null
     * @param name 名称，不允许为 null
     * @param age 方法入参 age
     */
    public Person(Long id, String name, Integer age) {
        this.id = id;
        this.name = name;
        this.age = age;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }
}
