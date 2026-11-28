# utils-support-tablesaw-starter

Tablesaw 内存表引擎，实现 common 中的 `Engine` 查询、分页、更新和删除包装器。适用于把 CSV 或 Java Bean 列表装入内存后做条件过滤、排序与投影；修改只作用于内存，需要显式 `save` 才写回文件。

## 安装

JDK 25：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-tablesaw-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

POM 直接依赖 `utils-support-common-starter` 与 `tablesaw-core:0.44.4`，无需数据库服务。`Engine` SPI 名为 `tablesaw`；CSV 加载/保存是实现扩展方法，使用这些方法时可直接创建 `TablesawEngine`。

## 从 CSV 加载、查询并保存

实体需要无参构造器及可写属性；以下类名 `Person` 对应内存表名 `person`，CSV 的列名为 `name,age`。

```java
import com.chua.tablesaw.support.engine.TablesawEngine;

public class TablesawExample {
    public static class Person {
        private String name;
        private int age;
        public Person() {}
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
    }

    public static void main(String[] args) {
        try (var engine = new TablesawEngine()) {
            engine.load("person", "people.csv", "UTF-8");
            var adults = engine.query(Person.class)
                    .ge(Person::getAge, 18)
                    .orderByAsc(Person::getName)
                    .page(1, 20);
            System.out.println(adults);
            int changed = engine.update(Person.class)
                    .eq(Person::getName, "Alice")
                    .set(Person::getAge, 30)
                    .update();
            System.out.println("更新行数=" + changed);
            engine.save("person", "people-updated.csv");
        }
    }
}
```

`load(name, path)` 默认按 UTF-8 读取；`load(name, path, charset)` 可指定编码。`load(name, InputStream)` 同样以 UTF-8 读 CSV，方法返回时关闭传入流。`save(name, path)` 输出 UTF-8 CSV，不自动创建缺失的父目录。

## 从对象列表创建表

在上例中也可用列表替代 CSV：

```java
var person = new TablesawExample.Person();
person.setName("Alice");
person.setAge(28);
try (var engine = new TablesawEngine()) {
    engine.store("person", java.util.List.of(person));
    long count = engine.query(TablesawExample.Person.class)
            .ge(TablesawExample.Person::getAge, 18).count();
    int removed = engine.delete(TablesawExample.Person.class)
            .lt(TablesawExample.Person::getAge, 18).remove();
    engine.save("person", "people.csv");
    System.out.println("符合条件=" + count + ", 删除=" + removed);
}
```

`store` 从首个对象的 getter 推导列和类型，同名表会替换；空列表不创建新表。它还登记“实体类 → 内存表”索引，因此 `store("任意表名", persons)` 后仍可用 `query(Person.class)` 访问该表。未命中该索引时才按类名的大写字母转下划线查找（`UserInfo → user_info`）；CSV 加载应使用该派生名。本实现不读取 datasource 模块的 `@TableName`。`getTable(name)` 可取得底层 Tablesaw `Table` 做原生处理，`removeTable(name)` 删除内存表。

## 查询语义与限制

- 支持比较、范围、字符串匹配、IN、空值条件及条件组合，支持排序、字段投影、`list/one/count/page`。`one()` 多行命中时取首行。
- `page` 从 1 开始，大小必须大于 0；分页使用自己的页码与大小，不叠加包装器的 limit/offset。`count` 忽略分页窗口。
- 更新的 `update()`、删除的 `remove()` 返回影响行数；不自动持久化、不提供数据库事务。并发修改同一表应由调用方协调。
- JOIN、GROUP BY/HAVING 与聚合投影不受支持，调用显式抛错；未注册的表、缺失列与不能映射的属性也不会伪装成空结果。
- 整个 CSV 常驻内存，分页只是结果截取，不是流式读取文件。`close()` 清空内存注册表并使引擎关闭。

## 附带的服务过滤器

本模块还包含 `com.chua.tablesaw.support.network.server.filter.BuiltinEndpointServerFilter`，提供 HTTP `/health`、`/metrics`、`/metrics/latency-histogram.svg`。它需要加入宿主 Server 的过滤链，不会因为创建 TablesawEngine 自动启动 HTTP 服务；这些端点也不是 CSV 数据查询接口。

返回 [数据库目录](../README.md) / [完整目录](../../README.md)。
