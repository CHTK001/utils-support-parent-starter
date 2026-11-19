# utils-support-json-starter

> JSON 操作模块：基于 [jayway JsonPath](https://github.com/json-path/JsonPath) 提供 **JSONPath 表达式解析、数据提取与修改** 能力，同时以 SPI 形式提供 `com.chua.common.support.lang.json.JsonPath` 的默认实现（SPI 名 `json`）。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`，全仓开启预览特性编译）｜包名：`com.chua.json.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-json-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | JSONPath 表达式级的 JSON 读取/修改工具，基于 jayway JsonPath | 不是 JSON 序列化/反序列化库，不是 JSON 文件读写器 |
| 入口 | `JsonPathUtils` 静态工具类；`JsonPath.getInstance()` SPI 入口 | 不是 Spring Bean，无自动装配、无配置类 |
| 实现 | `JsonPathImpl`：`com.chua.common.support.lang.json.JsonPath` 的默认 SPI 实现（`@Spi("json")` + `@SpiDefault`） | 不绑定具体业务，不持有任何业务表 |
| 依赖 | 仅依赖 `utils-support-common-starter` 与 `com.jayway.jsonpath:json-path`（底层解析走 Jackson） | 不依赖 Spring、数据库驱动、网络栈 |
| 行为 | 默认「路径不存在返回 null」（`Option.DEFAULT_PATH_LEAF_TO_NULL`）；另有严格模式抛 `PathNotFoundException` | 不做 JSON 格式校验、格式化、压缩 |

### 1.3 规模

- Java 源文件 **2** 个，均位于 `com.chua.json.support`：
  - `JsonPathUtils` —— 静态工具类（`@since 4.0.0.42`）；
  - `JsonPathImpl` —— SPI 实现（`@since 4.0.0.42`）。
- SPI 注册文件 **1** 个：`src/main/resources/META-INF/extensions/com.chua.common.support.lang.json.JsonPath`。
- 无测试源码目录、无 Spring 自动装配类、无配置属性类。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 读值（自动类型推断） | `JsonPathUtils.read(json, "$.store.book[0].title")` | 字符串→`String`、数字→`Double`、布尔→`Boolean`、数组→`List`、对象→`LinkedHashMap`、null→`null` |
| 读指定类型 / 泛型 | `read(json, path, Class)` / `read(json, path, TypeRef)` | 如 `read(json, "$..title", new TypeRef<List<String>>() {})` |
| 读列表 / 读对象 | `readList(json, path)` / `readMap(json, path)` | 路径应指向数组 / JSON 对象 |
| 修改 JSON | `set` / `delete` / `add` / `put` | 四个写操作均返回**新的** JSON 字符串 |
| 判断存在 / 取长度 | `isExist(json, path)` / `length(json, path)` | 异常或路径不存在时分别返回 `false` / `0` |
| 一次解析多次操作 | `parse(json)` 返回 `DocumentContext` 链式修改 | SPI 侧等价写法：`JsonPath.getInstance().parse(json)...toJson()` |
| 严格模式 / 自定义配置 | `readStrict(json, path)` / `read(json, path, Configuration)` | 严格模式路径不存在抛 `PathNotFoundException`；可传自定义 `Configuration` |
| SPI 入口 | `JsonPath.getInstance()` | 默认实现即本模块 `JsonPathImpl`；未找到实现时抛 `IllegalStateException` |

### 2.2 `JsonPathUtils` 全量方法（均为 `public static`）

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `read(String json, String jsonPath)` | `T` | 自动类型推断读取；基于默认配置（路径不存在返回 null） |
| `read(String json, String jsonPath, Class<T> type)` | `T` | 读取并映射为指定类型 |
| `read(String json, String jsonPath, TypeRef<T> typeRef)` | `T` | 读取复杂泛型（如 `List<User>`、`Map<String, Object>`） |
| `read(InputStream input, String jsonPath)` | `T` | 从输入流读取；**不会自动关闭流，调用方负责关闭** |
| `readList(String json, String jsonPath)` | `List<T>` | 读取列表 |
| `readMap(String json, String jsonPath)` | `Map<String, Object>` | 读取对象 |
| `set(String json, String jsonPath, Object value)` | `String` | 设置路径的值；路径不存在时按配置尝试创建中间节点 |
| `delete(String json, String jsonPath)` | `String` | 删除路径 |
| `add(String json, String jsonPath, Object value)` | `String` | 向数组末尾追加元素 |
| `put(String json, String jsonPath, String key, Object value)` | `String` | 向对象设置键值对（相当于 `Map.put`） |
| `isExist(String json, String jsonPath)` | `boolean` | 路径存在返回 `true`；异常时返回 `false` |
| `length(String json, String jsonPath)` | `int` | 数组长度或字符串长度；`null`/异常返回 `0`；其它标量返回 `1` |
| `parse(String json)` | `DocumentContext` | 解析为文档上下文，支持 `set`/`delete`/`add`/`jsonString()` 链式操作 |
| `readStrict(String json, String jsonPath)` | `T` | 严格模式：路径不存在抛 `PathNotFoundException` |
| `read(String json, String jsonPath, Configuration config)` | `T` | 使用自定义 `Configuration` 读取 |
| `getDefaultConfig()` | `Configuration` | 获取默认配置实例 |

### 2.3 `JsonPath` SPI 接口（`com.chua.common.support.lang.json.JsonPath`）

接口定义在 `utils-support-common-starter`，本模块提供实现。接口为**双层设计**：

- **一次性方法**：`read(json, path)`、`read(json, path, type)`、`set(json, path, value)`、`delete`、`add`、`put`、`isExist(json, path)`、`length(json, path)` —— 每次传入完整 JSON 字符串；
- **链式方法**：`parse(json)` 后，`read(path)`、`read(path, type)`、`set(path, value)`、`delete(path)`、`add(path, value)`、`put(path, key, value)`、`isExist(path)`、`length(path)`、`toJson()` 作用于内部文档。

错误行为（以 `JsonPathImpl` 源码为准）：

| 方法 | 失败行为 |
| --- | --- |
| `read`（一次性与链式） | 捕获异常返回 `null` |
| `isExist` | 捕获异常返回 `false` |
| `length` | 捕获异常返回 `0` |
| `set` / `delete` / `add` / `put`（一次性） | 抛 `IllegalStateException`，消息如 `JSONPath 设置失败: <路径>` |
| 链式方法未先 `parse` | 抛 `IllegalStateException`：`请先调用 parse(String) 解析 JSON 后再进行链式操作` |

线程安全：实现由 `ServiceProvider` 作为单例缓存；一次性方法无共享可变状态，线程安全；`parse` 每次返回持有独立 `DocumentContext` 的新实例，链上方法只作用于该实例，可多线程并发使用。

### 2.4 JSONPath 表达式速记

```text
$                            根对象
$.store.book[0]              store 下第一本书
$.store.book[*]              store 下所有书
$.store.book[?(@.price<10)]  价格小于 10 的书
$.store.book[0:3]            前三本书
$..author                    所有 author 字段（递归查找）
$.store.*                    store 下所有字段
```

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-json-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 最短可运行示例

```java
import com.chua.common.support.lang.json.JsonPath;
import com.chua.json.support.JsonPathUtils;

public class JsonPathDemo {

    public static void main(String[] args) {
        String json = "{\"store\":{\"book\":[{\"title\":\"Java\",\"price\":29.9}]}}";

        // 1) 静态工具：读取（自动类型推断 / 指定类型）
        String title = JsonPathUtils.read(json, "$.store.book[0].title");               // "Java"
        double price = JsonPathUtils.read(json, "$.store.book[0].price", double.class); // 29.9
        boolean exists = JsonPathUtils.isExist(json, "$.store.book[0]");                 // true

        // 2) 静态工具：修改（返回新字符串，不改入参）
        String updated = JsonPathUtils.set(json, "$.store.book[0].price", 35.0);

        // 3) SPI：一次解析、链式多次修改
        String result = JsonPath.getInstance()
                .parse(json)
                .set("$.store.book[0].price", 35.0)
                .add("$.store.book", java.util.Map.of("title", "Python", "price", 39.9))
                .toJson();

        System.out.println(title + " / " + price + " / " + exists);
        System.out.println(updated);
        System.out.println(result);
    }
}
```

> 前提：类路径上必须有本模块（或其它 `JsonPath` SPI 实现）。若一个实现都没有，`JsonPath.getInstance()` 会抛出
> `IllegalStateException("未找到 JsonPath SPI 实现，请添加 utils-support-json-starter 依赖")`。

---

## 4. 配置项

**无。**

依据：

1. `pom.xml` 只有 `utils-support-common-starter` 与 `com.jayway.jsonpath:json-path` 两个依赖声明，无任何属性段；
2. 源码未读取任何系统属性、环境变量或 Spring 配置（无 `@ConfigurationProperties`、无 `@Value`、无配置类）；
3. 模块没有 Spring 自动装配，行为由代码内的固定 `Configuration` 决定：
   - JSON 提供者与映射提供者：Jackson（`JacksonJsonProvider` / `JacksonMappingProvider`）；
   - 默认选项：`Option.DEFAULT_PATH_LEAF_TO_NULL`（路径不存在返回 null 而非抛异常）。

如需改变行为，只有两个**编程式**入口（无配置文件可调）：

- `JsonPathUtils.read(json, path, config)` 传入自定义 `Configuration`（例如加 `Option.ALWAYS_RETURN_LIST`）；
- 自行实现 `JsonPath` SPI 并注册（见下一节），替换默认实现。

---

## 5. SPI 扩展点

本模块参与的唯一 SPI 是 **`com.chua.common.support.lang.json.JsonPath`**（JSONPath 门面，接口定义在 `utils-support-common-starter`）。

| 项 | 值 |
| --- | --- |
| 接口 | `com.chua.common.support.lang.json.JsonPath` |
| 本模块实现 | `com.chua.json.support.JsonPathImpl`，注解 `@Spi("json")` + `@SpiDefault`（默认实现） |
| 注册文件 | `src/main/resources/META-INF/extensions/com.chua.common.support.lang.json.JsonPath` |
| 注册文件当前内容 | `json=com.chua.json.support.JsonPathImpl` |
| 获取方式 | `JsonPath.getInstance()`（先取默认实现，找不到再按名 `json` 取）；或 `ServiceProvider.of(JsonPath.class).getExtension("json")` |

替换 / 追加实现的方式：

1. 新建类实现 `com.chua.common.support.lang.json.JsonPath`，标注 `@Spi("自定义名")`；
2. 在本模块同路径的注册文件 `META-INF/extensions/com.chua.common.support.lang.json.JsonPath` 中追加一行 `自定义名=实现类全限定名`；
3. 通过 `ServiceProvider.of(JsonPath.class)` 按名获取；`JsonPath.getInstance()` 仍返回 `@SpiDefault` 默认实现。

---

## 6. 构建与依赖

### 6.1 构建

在模块目录（`utils-support-filesystem-parent/utils-support-json-starter`）执行仓库约定命令：

```bash
mvn -o -q compile -DskipTests    # 期望 EXIT=0
```

父模块 `utils-support-filesystem-parent` 预留了 GraalVM `native` profile（`native-maven-plugin` 1.1.14）。

### 6.2 依赖清单（pom.xml）

| 依赖 | 说明 |
| --- | --- |
| `com.chua:utils-support-common-starter` | 同版本（`${project.version}`）；提供 `JsonPath` SPI 接口与 `ServiceProvider` |
| `com.jayway.jsonpath:json-path` | JSONPath 引擎；版本由父 POM 统一管理，依赖树中为 **2.10.0** |

### 6.3 运行期传递依赖（节选自模块依赖树 `dtrees.txt`）

| 依赖 | 版本 | 来源 |
| --- | --- | --- |
| `com.fasterxml.jackson.core:jackson-databind` | 2.21.4 | 经 `utils-support-common-starter` 传递（JSONPath 的 JSON/Mapping 提供者底层） |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.19.0 | 经 `utils-support-common-starter` 传递 |
| `com.google.guava:guava` | 33.4.8-jre | 经 `utils-support-common-starter` 传递 |
| `org.slf4j:slf4j-api` | 2.0.18 | 经 `utils-support-common-starter` 传递 |
| `io.projectreactor:reactor-core` | 3.8.6 | 经 `utils-support-common-starter` 传递 |
| `net.minidev:json-smart` | 2.6.0 | `json-path` 的传递依赖（runtime） |

provided 范围：`org.projectlombok:lombok` 1.18.46、`org.jetbrains:annotations` 24.1.0、`com.google.code.findbugs:jsr305` 3.0.2。

依赖关系图：

```text
utils-support-json-starter
├── utils-support-common-starter
└── com.jayway.jsonpath:json-path
```

---

## 7. 边界：这些能力不在本模块

| 能力 | 正确归属 |
| --- | --- |
| `JsonPath` SPI **接口定义** | `utils-support-common-starter`（`com.chua.common.support.lang.json.JsonPath`）；本模块只是默认实现方 |
| 对象 ↔ JSON 的整体序列化 / 反序列化 | 直接用 Jackson，或 `utils-support-common-starter` 的 `serialize.Serializer` 契约（默认 JSON 实现） |
| JSON **文件**的按格式读写（含 JSON5） | `file.FileSystem` SPI 的 `json` 实现（`JsonFileSystem`，位于 `utils-support-common-starter`） |
| XML / YAML 等其它格式的路径查询 | 本模块不提供；jayway JsonPath 仅面向 JSON |
| Spring 自动装配、配置绑定、健康检查 | 本模块没有；它只是被依赖时带入 SPI 实现 |
| JSON 数据校验、格式化、压缩 | 本模块不提供 |
