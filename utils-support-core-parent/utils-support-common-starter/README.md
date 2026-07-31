# Utils Support Common Starter

> 通用工具库，提供丰富的 Java 工具类和功能模块，涵盖字符串处理、集合操作、HTTP 客户端、JSON 处理、加密解密、文件系统、SPI 服务发现等核心功能。

---

## 目录

- [快速开始](#快速开始)
- [工具类模块](#工具类模块)
  - [StringUtils 字符串工具](#stringutils-字符串工具)
  - [MapUtils Map 工具](#maputils-map工具)
  - [CollectionUtils 集合工具](#collectionutils-集合工具)
  - [ArrayUtils 数组工具](#arrayutils-数组工具)
  - [ClassUtils 类工具](#classutils-类工具)
  - [FileUtils 文件工具](#fileutils-文件工具)
  - [IoUtils IO 工具](#ioutils-io工具)
  - [DigestUtils 摘要加密工具](#digestutils-摘要加密工具)
  - [ImageUtils 图像工具](#imageutils-图像工具)
  - [IdUtils ID 生成工具](#idutils-id生成工具)
- [JSON 处理模块](#json-处理模块)
- [HTTP 客户端模块](#http-客户端模块)
- [Bean 操作模块](#bean-操作模块)
- [类型转换模块](#类型转换模块)
- [加密解密模块](#加密解密模块)
- [SPI 服务发现模块](#spi-服务发现模块)
- [集合扩展模块](#集合扩展模块)
- [文件系统模块](#文件系统模块)
- [缓存下载器模块](#缓存下载器模块)
- [进度条模块](#进度条模块)
- [统一返回结果模块](#统一返回结果模块)
- [版本号工具](#版本号工具)
- [日期时间模块](#日期时间模块)
- [对象池模块](#对象池模块)
- [ACME 证书模块](#acme-证书模块)
- [AI 图像生成模块](#ai-图像生成模块)
- [协议服务器模块](#协议服务器模块)
- [死信队列模块](#死信队列模块)
- [ObjectContext IoC容器模块](#objectcontext-ioc容器模块)
- [Sync 数据同步模块](#sync-数据同步模块)
- [Task 任务调度模块](#task-任务调度模块)
- [Orchestrator 任务编排模块](#orchestrator-任务编排模块)
- [Data 数据查询模块](#data-数据查询模块)
- [Lang 语言特性模块](#lang-语言特性模块)
- [文档中心](#文档中心)
- [依赖要求](#依赖要求)
- [许可证](#许可证)

---

## 快速开始

### Maven 依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-common-starter</artifactId>
    <version>${version}</version>
</dependency>
```

## 包迁移脚本

- `python migrate.py`：按阶段迁移包结构（覆盖 core/base/io/text/crypto/network/data/concurrent/storage/media/ui 及 lang、task 目录），留意终端输出的异常提示。
- `python cleanup.py`：迁移后清理同名嵌套目录（如 `io/stream/stream`），确保目录结构干净。

---

## 工具类模块

### StringUtils 字符串工具

#### 功能说明

提供全面的字符串处理能力，包括空值判断、截取、填充、格式化、编码转换、模式匹配等常用操作。

#### 适用场景

- 表单数据校验与清洗
- 文本格式化与模板处理
- URL 编解码
- SQL 注入防护

#### 使用示例

```java
import com.chua.common.support.core.utils.StringUtils;

// 空值判断
StringUtils.isEmpty(null);        // true
StringUtils.isEmpty("");          // true
StringUtils.isBlank("  ");        // true
StringUtils.isNotEmpty("hello");  // true

// 字符串截取
StringUtils.substring("hello world", 0, 5);  // "hello"
StringUtils.left("hello", 3);                // "hel"
StringUtils.right("hello", 3);               // "llo"

// 字符串填充
StringUtils.leftPad("123", 5, '0');   // "00123"
StringUtils.rightPad("123", 5, '0');  // "12300"
StringUtils.center("abc", 7, '*');    // "**abc**"

// 字符串替换
StringUtils.replace("hello world", "world", "java");  // "hello java"
StringUtils.removeAll("a1b2c3", "\\d");               // "abc"

// 字符串拼接与分割
StringUtils.join(Arrays.asList("a", "b", "c"), ",");  // "a,b,c"
StringUtils.split("a,b,c", ",");                      // ["a", "b", "c"]

// URL 编解码
StringUtils.urlEncode("中文");   // "%E4%B8%AD%E6%96%87"
StringUtils.urlDecode("%E4%B8%AD%E6%96%87");  // "中文"
```

---

### MapUtils Map 工具

#### 功能说明

提供 Map 集合的创建、转换、扁平化、嵌套解析等操作。

#### 适用场景

- 配置文件解析与处理
- 嵌套数据结构扁平化
- Map 与 Bean 互转
- 参数映射与转换

#### 使用示例

```java
import com.chua.common.support.core.utils.MapUtils;

// 创建 Map
Map<String, Object> map = MapUtils.newHashMap();
Map<String, Object> linkedMap = MapUtils.newLinkedHashMap();

// 扁平化嵌套 Map
Map<String, Object> nested = new HashMap<>();
nested.put("user", Map.of("name", "张三", "age", 25));
Map<String, Object> flat = MapUtils.flattenMap(nested);
// 结果: {"user.name": "张三", "user.age": 25}

// 反向嵌套
Map<String, Object> unflat = MapUtils.unflattenMap(flat);
// 结果: {"user": {"name": "张三", "age": 25}}

// 获取嵌套值
Object name = MapUtils.getNestedValue(nested, "user.name");  // "张三"

// 类型安全获取
String value = MapUtils.getString(map, "key", "默认值");
Integer num = MapUtils.getInteger(map, "count", 0);
Boolean flag = MapUtils.getBoolean(map, "enabled", false);
```

---

### CollectionUtils 集合工具

#### 功能说明

提供集合的判空、转换、分组、排序、去重、交集并集差集等操作。

#### 适用场景

- 集合数据处理与转换
- 批量数据分组统计
- 集合运算（交集、并集、差集）
- 安全的集合操作

#### 使用示例

```java
import com.chua.common.support.core.utils.CollectionUtils;

// 空值判断
CollectionUtils.isEmpty(null);       // true
CollectionUtils.isEmpty(new ArrayList<>());  // true
CollectionUtils.isNotEmpty(list);    // true

// 集合转换
List<String> names = CollectionUtils.toList(set);
Set<String> uniqueNames = CollectionUtils.toSet(list);

// 集合运算
List<String> union = CollectionUtils.union(list1, list2);         // 并集
List<String> intersection = CollectionUtils.intersection(list1, list2);  // 交集
List<String> difference = CollectionUtils.subtract(list1, list2);  // 差集

// 安全获取第一个/最后一个元素
String first = CollectionUtils.getFirst(list);
String last = CollectionUtils.getLast(list);

// 分批处理
CollectionUtils.partition(largeList, 100).forEach(batch -> {
    // 每批处理100条数据
    processBatch(batch);
});
```

---

### ArrayUtils 数组工具

#### 功能说明

提供数组的创建、判空、查找、合并、转换等操作。

#### 适用场景

- 数组数据处理
- 数组与集合互转
- 数组元素查找与过滤
- 数组拼接与拆分

#### 使用示例

```java
import com.chua.common.support.core.utils.ArrayUtils;

// 空值判断
ArrayUtils.isEmpty(null);           // true
ArrayUtils.isEmpty(new String[0]);  // true
ArrayUtils.isNotEmpty(arr);         // true

// 数组操作
String[] arr = ArrayUtils.add(new String[]{"a", "b"}, "c");  // ["a", "b", "c"]
String[] sub = ArrayUtils.subarray(arr, 0, 2);               // ["a", "b"]
boolean contains = ArrayUtils.contains(arr, "a");            // true

// 数组合并
String[] merged = ArrayUtils.addAll(arr1, arr2);

// 数组转换
List<String> list = ArrayUtils.toList(arr);
String[] newArr = ArrayUtils.toArray(list, String.class);
```

---

### ClassUtils 类工具

#### 功能说明

提供类加载、类型判断、反射操作、泛型解析等功能。

#### 适用场景

- 动态类加载与实例化
- 类型兼容性检查
- 泛型类型解析
- 反射工具封装

#### 使用示例

```java
import com.chua.common.support.core.utils.ClassUtils;

// 类加载检查
boolean present = ClassUtils.isPresent("com.example.MyClass");

// 动态加载类
Class<?> clazz = ClassUtils.forName("com.example.MyClass");

// 创建实例
Object instance = ClassUtils.forObject(MyClass.class);
Object instanceWithArgs = ClassUtils.forObject(MyClass.class, "arg1", 123);

// 类型判断
ClassUtils.isPrimitive(int.class);      // true
ClassUtils.isAssignable(ArrayList.class, List.class);  // true

// 获取所有接口
Set<Class<?>> interfaces = ClassUtils.getAllInterfaces(clazz);

// 获取类路径
String path = ClassUtils.getClassPath(MyClass.class);
```

---

### FileUtils 文件工具

#### 功能说明

提供文件读写、复制、删除、目录遍历、文件类型判断等操作。

#### 适用场景

- 文件批量处理
- 目录结构操作
- 文件内容读写
- 文件类型识别

#### 使用示例

```java
import com.chua.common.support.core.utils.FileUtils;

// 文件读取
String content = FileUtils.readFileToString(new File("test.txt"), "UTF-8");
List<String> lines = FileUtils.readLines(new File("test.txt"), "UTF-8");
byte[] bytes = FileUtils.readFileToByteArray(new File("test.bin"));

// 文件写入
FileUtils.writeStringToFile(new File("output.txt"), "内容", "UTF-8");
FileUtils.writeLines(new File("output.txt"), lines, "UTF-8");
FileUtils.writeByteArrayToFile(new File("output.bin"), bytes);

// 文件复制与移动
FileUtils.copyFile(srcFile, destFile);
FileUtils.copyDirectory(srcDir, destDir);
FileUtils.moveFile(srcFile, destFile);

// 文件删除
FileUtils.deleteQuietly(file);
FileUtils.deleteDirectory(dir);

// 获取文件扩展名
String ext = FileUtils.getExtension("test.txt");  // "txt"

// 获取文件大小（人类可读格式）
String size = FileUtils.byteCountToDisplaySize(1024 * 1024);  // "1 MB"
```

---

### IoUtils IO 工具

#### 功能说明

提供输入输出流的读写、复制、关闭等操作。

#### 适用场景

- 流数据处理
- 资源安全关闭
- 流转换与复制

#### 使用示例

```java
import com.chua.common.support.core.utils.IoUtils;

// 流转字符串
String content = IoUtils.toString(inputStream, "UTF-8");

// 流转字节数组
byte[] bytes = IoUtils.toByteArray(inputStream);

// 流复制
IoUtils.copy(inputStream, outputStream);

// 安全关闭资源
IoUtils.closeQuietly(inputStream);
IoUtils.closeQuietly(outputStream);

// 读取行
List<String> lines = IoUtils.readLines(reader);
```

---

### DigestUtils 摘要加密工具

#### 功能说明

提供 MD5、SHA、HMAC、TOTP、国密(SM2/SM3/SM4)、RSA 等多种加密和摘要算法。

#### 适用场景

- 密码加密存储
- 数据签名验证
- 文件完整性校验
- 双因素认证(TOTP)
- 国密算法应用

#### 使用示例

```java
import com.chua.common.support.core.utils.DigestUtils;

// MD5
String md5 = DigestUtils.md5Hex("hello");
String md5File = DigestUtils.md5Hex(new File("test.txt"));

// SHA系列
String sha256 = DigestUtils.sha256Hex("hello");
String sha512 = DigestUtils.sha512Hex("hello");

// HMAC
byte[] hmac = DigestUtils.hmacSha256("data".getBytes(), "secret".getBytes());

// TOTP (基于时间的一次性密码)
byte[] secret = "MYSECRET".getBytes(StandardCharsets.UTF_8);
String code = DigestUtils.totpNow(secret);

// MAC
byte[] tag = DigestUtils.mac("HmacSHA256", "data".getBytes(), secret);

// 国密 SM3
String sm3 = DigestUtils.sm3Hex("abc".getBytes());

// 国密 SM4 加解密
byte[] encrypted = DigestUtils.sm4EncryptEcb(data, key);
byte[] decrypted = DigestUtils.sm4DecryptEcb(encrypted, key);

// RSA 加解密
byte[] rsaEncrypted = DigestUtils.rsaEncrypt(data, publicKey);
byte[] rsaDecrypted = DigestUtils.rsaDecrypt(rsaEncrypted, privateKey);
```

---

### ImageUtils 图像工具

#### 功能说明

提供图像缩放、裁剪、旋转、水印、格式转换等操作。

#### 适用场景

- 图片处理与优化
- 缩略图生成
- 水印添加
- 图片格式转换

#### 使用示例

```java
import com.chua.common.support.core.utils.ImageUtils;

// 图片缩放
BufferedImage scaled = ImageUtils.scale(image, 200, 150);

// 图片裁剪
BufferedImage cropped = ImageUtils.crop(image, 10, 10, 100, 100);

// 图片旋转
BufferedImage rotated = ImageUtils.rotate(image, 90);

// 添加文字水印
BufferedImage watermarked = ImageUtils.addTextWatermark(image, "© 版权所有", font, color);

// Swing弹窗展示图片
BufferedImage img = ImageIO.read(new File("demo.png"));
ImageUtils.showImage(img, "示例预览");
```

---

### IdUtils ID 生成工具

#### 功能说明

提供 UUID、雪花算法、短 ID 等多种 ID 生成方式。

#### 适用场景

- 分布式唯一 ID 生成
- 数据库主键生成
- 订单号、流水号生成

#### 使用示例

```java
import com.chua.common.support.core.utils.IdUtils;

// UUID
String uuid = IdUtils.randomUUID();            // 标准UUID
String simpleUuid = IdUtils.simpleUUID();      // 无连字符UUID

// 雪花算法ID
long snowflakeId = IdUtils.getSnowflakeNextId();
String snowflakeIdStr = IdUtils.getSnowflakeNextIdStr();

// 短ID
String shortId = IdUtils.shortUuid();
```

---

## JSON 处理模块

### 功能说明

基于 Jackson 封装的 JSON 工具类，支持 JSON5 特性，提供序列化、反序列化、类型转换等功能。

### 适用场景

- API 数据序列化与反序列化
- 配置文件解析（支持 JSON5 注释）
- 对象与 JSON 互转
- 复杂泛型类型处理

### 使用示例

```java
import com.chua.common.support.json.Json;
import com.chua.common.support.json.JsonObject;
import com.chua.common.support.json.JsonArray;

// 对象转 JSON 字符串
User user = new User("张三", 25);
String json = Json.toJson(user);

// 格式化输出
String prettyJson = Json.toPrettyJson(user);

// JSON 字符串转对象
User user = Json.fromJson(json, User.class);

// 泛型类型转换
List<User> users = Json.fromJson(jsonArray, new TypeReference<List<User>>(){});

// JsonObject 操作
JsonObject obj = Json.parse(json);
String name = obj.getString("name");
int age = obj.getInteger("age");
obj.put("email", "test@example.com");

// JsonArray 操作
JsonArray arr = Json.parseArray(jsonArrayStr);
for (int i = 0; i < arr.size(); i++) {
    JsonObject item = arr.getJsonObject(i);
}

// Map 转 JSON
Map<String, Object> map = new HashMap<>();
map.put("key", "value");
String mapJson = Json.toJson(map);

// JSON 转 Map
Map<String, Object> result = Json.fromJson(json, Map.class);
```

---

## HTTP 客户端模块

### 功能说明

提供简洁、强大的 HTTP 客户端，支持 GET、POST、PUT、DELETE、PATCH、HEAD、OPTIONS 等请求方式，
支持 JSON、表单、文件上传（multipart）、流式输出等多种内容类型。

### 核心特性

| 特性 | 说明 |
| --- | --- |
| **链式构建器** | 流畅的 Fluent API，通过 `HttpClientFactory.of(url)` 链式构建请求 |
| **同步/异步双模式** | 同步方法 `get()/post()` 直接返回结果；异步方法 `getAsync()/postAsync()` 支持 CompletableFuture 和 Callback 两种回调 |
| **文件上传** | 原生 multipart/form-data 支持（含 boundary 分隔符），零依赖 |
| **鉴权快捷方法** | `auth(token)` Bearer Token、`authBasic(user, pass)` Basic 认证 |
| **重定向控制** | `onRedirect(callback)` 自定义重定向处理 |
| **超时控制** | 分别设置连接超时 `connectTimeout` 和读取超时 `readTimeout` |
| **SPI 多实现** | 自动选择 JDK HttpClient / OkHttp3 / Apache HttpClient5（按优先级） |
| **原生异步 I/O** | JDK 执行器使用 `sendAsync()` 实现零阻塞 NIO 异步请求 |

### 适用场景

- RESTful API 调用
- 文件上传下载
- 第三方服务集成
- 高并发 HTTP 请求

### 使用示例

#### 1. 同步 GET 请求

```java
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.network.client.ClientResponse;

ClientResponse resp = HttpClientFactory.of("https://api.example.com/users")
    .header("Authorization", "Bearer token")
    .query("page", "1")
    .query("size", "20")
    .get();

if (resp.isSuccess()) {
    String json = resp.getBodyString();
    System.out.println(json);
}
```

#### 2. 同步 POST JSON

```java
ClientResponse resp = HttpClientFactory.of("https://api.example.com/users")
    .json()  // Content-Type: application/json
    .body("{\"name\": \"张三\", \"age\": 25}")
    .post();
```

#### 3. 异步请求（CompletableFuture 模式）

```java
// 返回 CompletableFuture<ClientResponse>，可链式组合
HttpClientFactory.of("https://api.example.com/users")
    .accept("application/json")
    .getAsync()
    .thenApply(ClientResponse::getBodyString)
    .thenAccept(System.out::println)
    .exceptionally(err -> {
        System.err.println("请求失败: " + err.getMessage());
        return null;
    });
```

#### 4. 异步请求（Callback 回调模式）

```java
// Lambda 风格回调
HttpClientFactory.of("https://api.example.com/users")
    .getAsync(
        resp -> System.out.println("成功: " + resp.getBodyString()),
        err  -> System.err.println("失败: " + err.getMessage())
    );
```

#### 5. 文件上传（multipart/form-data）

```java
import java.nio.file.Files;
import java.nio.file.Paths;

byte[] fileBytes = Files.readAllBytes(Paths.get("photo.png"));

ClientResponse resp = HttpClientFactory.of("https://api.example.com/upload")
    .formData("file", fileBytes, "image/png", "photo.png")
    .formData("description", "A beautiful photo")
    .post();
```

#### 6. Bearer Token 和 Basic 认证

```java
// Bearer Token（JWT / OAuth2）
HttpClientFactory.of("https://api.example.com/protected")
    .auth("eyJhbGciOiJIUzI1NiIs...")
    .get();

// HTTP Basic 认证
HttpClientFactory.of("https://api.example.com/protected")
    .authBasic("admin", "123456")
    .get();
```

#### 7. 自定义重定向处理

```java
HttpClientFactory.of("https://httpbin.org/redirect/3")
    .onRedirect(resp -> {
        String location = resp.getHeader("Location");
        System.out.println("重定向至: " + location);
    })
    .get();
```

#### 8. 超时设置

```java
HttpClientFactory.of("https://api.example.com/slow-api")
    .connectTimeout(5000)   // 连接超时 5 秒
    .readTimeout(10000)     // 读取超时 10 秒
    .get();
```

#### 9. 异步请求链式组合

```java
// 组合两个异步请求
HttpClientFactory.of("https://api.example.com/auth")
    .authBasic("admin", "123456")
    .getAsync()
    .thenCompose(resp -> {
        String token = resp.getBodyString();
        return HttpClientFactory.of("https://api.example.com/data")
            .auth(token)
            .getAsync();
    })
    .thenAccept(resp -> System.out.println("数据: " + resp.getBodyString()));
```

#### 10. 直接使用 HttpClient 接口

```java
import com.chua.common.support.network.client.HttpClient;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.network.client.ClientRequest;
import com.chua.common.support.network.http.HttpMethod;

// 获取全局客户端实例
HttpClient client = HttpClientFactory.getClient();

// 同步调用
ClientResponse resp = client.get("https://api.example.com/health");

// 异步调用
client.executeAsync(ClientRequest.of("https://api.example.com/data"))
    .thenAccept(r -> System.out.println(r.getBodyString()));
```

### 异步实现原理

| 层级 | 实现方式 | 说明 |
| --- | --- | --- |
| `HttpClient` 接口 | `default CompletableFuture` | 接口默认方法，虚拟线程包装同步调用 |
| `AbstractHttpClient` | 模板方法 + 虚拟线程 | 保留 `beforeExecute`/`afterExecute` 扩展点 |
| `JdkHttpClientExecutor` | 原生 `sendAsync()` | 使用 JDK NIO 事件驱动，零阻塞 |
| `HttpClientBuilder` | `getAsync()/postAsync()` 等快捷方法 | 14 个异步快捷方法（7 方法 × 2 模式） |

---

## Bean 操作模块

### 功能说明

提供 Bean 属性复制、Map 与 Bean 互转、属性访问等功能，内置反射缓存优化性能。

### 适用场景

- DTO/VO/Entity 对象转换
- 动态属性访问
- 批量属性复制
- 配置对象填充

### 使用示例

```java
import com.chua.common.support.lang.bean.BeanUtils;
import com.chua.common.support.lang.bean.BeanMap;

// 属性复制
UserDTO dto = new UserDTO();
BeanUtils.

        copyProperties(user, dto);

// 带忽略属性的复制
BeanUtils.

        copyProperties(source, target, "id","createTime");

        // Bean 转 Map
        Map<String, Object> map = BeanUtils.toMap(user);

        // Map 转 Bean
        User user = BeanUtils.toBean(map, User.class);

        // BeanMap 操作（高性能）
        BeanMap beanMap = BeanMap.create(user);
        Object name = beanMap.get("name");
beanMap.

        put("name","新名称");

        // 属性扁平化
        Map<String, Object> flat = BeanUtils.flattenToProperties(user);
// 结果: {"name": "张三", "address.city": "北京", "address.street": "朝阳区"}

        // 属性反扁平化
        User user = BeanUtils.unflattenFromProperties(flat, User.class);
```

---

## 类型转换模块

### 功能说明

提供统一的类型转换机制，支持基本类型、日期、集合、泛型等多种类型的自动转换。

### 适用场景

- 配置值类型转换
- 请求参数绑定
- 数据库字段映射
- 通用数据处理

### 使用示例

```java
import com.chua.common.support.converter.Converter;

// 基本类型转换
Integer num = Converter.convertIfNecessary("123", Integer.class);
Long longVal = Converter.convertIfNecessary("9999999999", Long.class);
Double doubleVal = Converter.convertIfNecessary("3.14", Double.class);
Boolean boolVal = Converter.convertIfNecessary("true", Boolean.class);

// 日期转换
Date date = Converter.convertIfNecessary("2024-01-01", Date.class);
LocalDateTime dateTime = Converter.convertIfNecessary("2024-01-01 12:00:00", LocalDateTime.class);

// 集合转换
List<String> list = Converter.convertIfNecessary("a,b,c", List.class);

// 枚举转换
Status status = Converter.convertIfNecessary("ACTIVE", Status.class);

// Map 转 Bean
User user = Converter.convertIfNecessary(map, User.class);

// 带默认值转换
Integer value = Converter.convertIfNecessary(null, Integer.class, 0);
```

---

## 加密解密模块

### 功能说明

提供多种加密算法实现，包括对称加密(AES/DES/SM4)、非对称加密(RSA/ECC/SM2)、摘要算法(MD5/SHA/SM3)、编码(Base64/Base32/Hex)等。

### 适用场景

- 敏感数据加密存储
- 数据传输加密
- 数字签名与验证
- 密钥交换

### 使用示例

```java
import com.chua.common.support.crypto.*;

// AES 加解密
Codec aes = CodecBuilder.newBuilder()
    .type(CryptoType.AES)
    .key("1234567890123456")
    .build();
String encrypted = aes.encodeHex("明文数据");
String decrypted = aes.decodeHex(encrypted);

// RSA 加解密
CodecKeyPair rsa = CodecBuilder.newBuilder()
    .type(CryptoType.RSA)
    .keySize(2048)
    .buildKeyPair();
String publicKey = rsa.getPublicKeyBase64();
String privateKey = rsa.getPrivateKeyBase64();

byte[] encrypted = rsa.encrypt("数据".getBytes());
byte[] decrypted = rsa.decrypt(encrypted);

// 数字签名
byte[] signature = rsa.sign("数据".getBytes());
boolean valid = rsa.verify("数据".getBytes(), signature);

// Base64 编解码
String base64 = Base64Codec.encode("hello".getBytes());
byte[] decoded = Base64Codec.decode(base64);

// MD5
String md5 = Md5Codec.md5Hex("hello");

// Hex 编解码
String hex = Hex.encodeHexString(bytes);
byte[] bytes = Hex.decodeHex(hex);
```

---

## SPI 服务发现模块

### 功能说明

增强版的 SPI 服务发现机制，支持按名称/别名获取、优先级排序、条件过滤、依赖注入等功能。

### 适用场景

- 插件化架构
- 策略模式实现
- 扩展点机制
- 依赖解耦

### 使用示例

```java
import com.chua.common.support.core.spi.ServiceProvider;

// 获取服务提供者
ServiceProvider<MyService> provider = ServiceProvider.of(MyService.class);

// 获取默认实现
MyService service = provider.getDefault();

// 按名称获取
MyService service = provider.getExtension("impl1");

// 获取所有实现
List<MyService> services = provider.list();

// 获取所有实现名称
Set<String> names = provider.names();

// 条件过滤
MyService service = provider.getExtension(impl -> impl.supports(context));

// 遍历所有实现
provider.forEach((name, service) -> {
    System.out.println(name + ": " + service);
});
```

#### SPI 注解使用

```java
// 定义 SPI 接口
@Spi("default")
public interface MyService {
    void execute();
}

// 实现类
@Extension("impl1")
@SpiOrder(1)
public class MyServiceImpl1 implements MyService {
    @Override
    public void execute() { }
}

@Extension("impl2")
@SpiOrder(2)
public class MyServiceImpl2 implements MyService {
    @Override
    public void execute() { }
}
```

---

## 集合扩展模块

### 功能说明

提供多种增强集合实现，包括多值 Map、双向链表、大小写不敏感 Map、位图等。

### 适用场景

- 复杂数据结构需求
- 性能优化场景
- 特殊集合操作

### 使用示例

```java
import com.chua.common.support.collection.*;

// 多值 Map（一个键对应多个值）
MultiValueMap<String, String> multiMap = new MultiLinkedValueMap<>();
multiMap.add("key", "value1");
multiMap.add("key", "value2");
List<String> values = multiMap.get("key");  // ["value1", "value2"]

// 大小写不敏感 Map
Map<String, Object> caseInsensitiveMap = new CaseInsensitiveMap<>();
caseInsensitiveMap.put("Key", "value");
caseInsensitiveMap.get("key");   // "value"
caseInsensitiveMap.get("KEY");   // "value"

// 并发引用 Map（软引用，内存不足时自动回收）
Map<String, Object> refMap = new ConcurrentReferenceHashMap<>();

// 双向链表
DoubleLinkedList<String> list = new DoubleLinkedList<>();
list.addFirst("a");
list.addLast("b");

// 位图
Bitmap bitmap = new Bitmap(1000);
bitmap.set(5);
bitmap.set(100);
boolean exists = bitmap.get(5);  // true

// 键值对
KeyValue<String, Integer> kv = new KeyValue<>("name", 123);
```

---

## 文件系统模块

### 功能说明

提供统一的文件系统抽象，支持本地文件、压缩包、远程文件等多种来源，支持流式检索、打包解包、链式过滤等操作。

### 适用场景

- 文件批量处理
- 压缩包操作
- 文件搜索与过滤
- 跨文件系统操作

### 使用示例

```java
import com.chua.common.support.lang.file.*;

// 文件夹文件系统
FolderFileSystem fs = new FolderFileSystem(new File("/data/project"));

// 流式检索：递归查找所有 .java 文件，按扩展名排序，取前100条
List<String> names = fs.createSearchStream()
    .maxDepth(Integer.MAX_VALUE)
    .filesOnly()
    .filterByExtension("java")
    .sortByExtension().thenBySizeDesc()
    .page(0, 100)
    .stream()
    .map(SearchFileEntry::getPath)
    .collect(Collectors.toList());

// 进入子目录获取对应 FileSystem
FileSystem sub = fs.getFileSystem("src/main/resources");

// Pack：流式打包为 zip
FileSystem zipFs = PackFileStream.zip("demo.zip")
    .addFile("README.md", Files.readAllBytes(Path.of("README.md")))
    .addDirectory(new File("./assets"), "")
    .build();

// Unpack：过滤解包到目录
new ZipUnpackFileStream(new File("demo.zip"))
    .include("**/*.md")
    .exclude("**/test/**")
    .filesOnly()
    .toDirectory("./out");

// GIF 文件系统：按帧访问
MultiPartFileSystem gif = new GifFileSystem(new File("a.gif"));
gif.extractFile("frames/frame-0001.png", "./out");

// 链接过滤并输出为压缩包
FileSystem linked = gif.link()
    .include("frames/**")
    .toZip("gif-frames.zip");
```

---

## 缓存下载器模块

### 功能说明

高性能文件下载器，支持智能缓存、哈希验证、多线程下载、断点续传、多平台适配、自动解压等功能。

### 适用场景

- 大文件下载
- 软件分发与更新
- 依赖资源下载
- 跨平台部署

### 使用示例

```java
import com.chua.common.support.io.download.*;

// 基本使用
CachedDownloader downloader = Downloader.createDefault();
DownloadFile file = downloader.downloadTo("https://example.com/file.zip", "./downloads/file.zip");

// 链式构建器配置
CachedDownloader downloader = Downloader.builder()
    .threads(4)                              // 4个下载线程
    .buffer(8 * 1024 * 1024)                 // 8MB缓冲区
    .cacheDirectory("./my-cache")            // 自定义缓存目录
    .cacheExpiration(Duration.ofHours(12))   // 12小时过期
    .maxCacheSize(1024L * 1024L * 1024L)     // 1GB最大缓存
    .enableHashValidation(true)              // 启用哈希验证
    .build();

// 多平台下载
FileInfo fileInfo = CachedDownloaderBuilder.create()
    .windows("https://example.com/tool-windows-x64.exe")
    .linux("https://example.com/tool-linux-x64")
    .macos("https://example.com/tool-macos-x64")
    .macosArm("https://example.com/tool-macos-arm64")
    .buildFileInfo("cross-platform-tool", "1.0.0", "跨平台工具");

// 自动检测当前平台并下载
DownloadFile result = downloader.downloadTo(fileInfo, "./downloads");

// 压缩包自动解压
CachedDownloader extractorDownloader = CachedDownloaderBuilder.create()
    .enableAutoExtraction(true)
    .extractionDirectory("./extracted")
    .keepOriginalAfterExtraction(false)
    .build();
```

### 配置参数

| 参数                   | 类型     | 默认值    | 说明             |
| ---------------------- | -------- | --------- | ---------------- |
| `cacheDirectory`       | String   | "./cache" | 缓存目录路径     |
| `cacheExpirationTime`  | Duration | 24 小时   | 缓存过期时间     |
| `maxCacheSize`         | Long     | 512MB     | 最大缓存大小     |
| `enableHashValidation` | Boolean  | false     | 是否启用哈希验证 |
| `threads`              | int      | 1         | 下载线程数       |
| `enableAutoExtraction` | Boolean  | false     | 是否启用自动解压 |

---

## 进度条模块

### 功能说明

提供丰富的终端进度条样式，支持多种视觉效果，包括 ASCII、Unicode、渐变色等。

### 适用场景

- 长时间任务进度展示
- 下载进度显示
- 批量处理进度反馈

### 使用示例

```java
import com.chua.common.support.printer.*;

// 创建进度条
ProgressBar pb = ProgressBarBuilder.newBuilder()
    .setTaskName("下载文件")
    .setInitialMax(100)
    .setStyle(ProgressBarStyle.SMOOTH)
    .build();

// 更新进度
for (int i = 0; i <= 100; i++) {
    pb.stepTo(i);
    Thread.sleep(50);
}
pb.close();

// 使用预定义样式
ProgressBarStyle.ASCII           // [=====     ] 50%
ProgressBarStyle.UNICODE_BLOCK   // |█████     | 50%
ProgressBarStyle.SMOOTH          // ▕█████▏ 50%
ProgressBarStyle.RAINBOW         // 彩虹渐变
ProgressBarStyle.PYTHON_DOWNLOAD // Python下载风格
ProgressBarStyle.PYTHON_LOADING  // Python加载风格

// 自定义样式
ProgressBarStyle customStyle = ProgressBarStyle.builder()
    .leftBracket("[")
    .rightBracket("]")
    .block('█')
    .space(' ')
    .build();
```

### 样式对比

| 样式名称       | 视觉效果   | 推荐场景         |
| -------------- | ---------- | ---------------- |
| ASCII          | 简单字符   | 终端兼容性要求高 |
| UNICODE_BLOCK  | 块状字符   | 现代终端         |
| SMOOTH         | 平滑边缘   | 现代 UI          |
| RAINBOW        | 彩虹渐变   | 炫酷效果         |
| PYTHON_LOADING | 粉红色线条 | Python 风格      |

---

## 统一返回结果模块

### 功能说明

提供标准化的 API 响应封装，包括成功/失败状态、数据载体、分页结果等。

### 适用场景

- RESTful API 响应统一
- 前后端数据交互
- 分页数据返回

### 使用示例

```java
import com.chua.common.support.lang.code.*;

// 成功返回
ReturnResult<User> success = ReturnResult.success(user);
ReturnResult<Void> ok = ReturnResult.ok();

// 失败返回
ReturnResult<Void> error = ReturnResult.error("操作失败");
ReturnResult<Void> error = ReturnResult.error(500, "服务器错误");

// 分页结果
PageResult<User> pageResult = PageResult.of(userList, total, page, size);
ReturnPageResult<User> pageResponse = ReturnPageResult.success(pageResult);

// 判断状态
if (result.isSuccess()) {
    User data = result.getData();
}

// 转换为字节数组（JSON）
byte[] bytes = result.toByteArray();

// 链式构建
ReturnResult<User> result = ReturnResultBuilder.newBuilder()
    .code(200)
    .msg("操作成功")
    .data(user)
    .build();
```

---

## 版本号工具

### 功能说明

语义化版本号解析与比较工具，支持预发布版本、构建元数据等。

### 适用场景

- 版本升级检查
- 依赖版本比较
- 脚本版本管理

### 使用示例

```java
import com.chua.common.support.lang.version.Version;

// 创建版本
Version v1 = Version.of("1.0.0");
Version v2 = Version.of("2.0.0-beta");
Version v3 = Version.of(1, 2, 3);

// 版本比较
v2.isHigherThan(v1);    // true
v1.isLowerThan(v2);     // true
v1.isEqual("1.0.0");    // true
v1.isAtLeast("0.9.0");  // true

// 获取版本组件
v1.getMajor();          // 1
v1.getMinor();          // 0
v1.getPatch();          // 0
v2.getSuffix();         // "-beta"

// 从 Maven JAR 文件名解析
Version v = Version.parseFromMaven("spring-core-5.3.21.jar");  // 5.3.21

// 版本排序
List<Version> versions = Arrays.asList(
    Version.of("1.0.0"),
    Version.of("2.0.0-beta"),
    Version.of("1.5.0")
);
Collections.sort(versions);  // [1.0.0, 1.5.0, 2.0.0-beta]
```

### 版本优先级

```
SNAPSHOT < ALPHA < BETA < RC < STABLE
1.0.0-snapshot < 1.0.0-alpha < 1.0.0-beta < 1.0.0-rc1 < 1.0.0
```

---

## 日期时间模块

### 功能说明

提供日期时间处理、格式化、解析、计算等功能。

### 适用场景

- 日期时间格式转换
- 时间计算与比较
- 时区处理

### 使用示例

```java
import com.chua.common.support.time.date.DateTime;
import com.chua.common.support.time.date.DateUtils;

// 当前时间
DateTime now = DateTime.now();

// 格式化
String formatted = now.format("yyyy-MM-dd HH:mm:ss");

// 解析
DateTime dt = DateTime.parse("2024-01-01 12:00:00");

// 时间计算
DateTime tomorrow = now.plusDays(1);
DateTime lastMonth = now.minusMonths(1);

// 时间比较
now.isBefore(tomorrow);  // true
now.isAfter(lastMonth);  // true

// 获取时间戳
long timestamp = now.getTime();

// 工具方法
DateUtils.format(new Date(), "yyyy-MM-dd");
DateUtils.parse("2024-01-01", "yyyy-MM-dd");
```

---

## 对象池模块

### 功能说明

提供通用对象池实现，支持连接池化管理。

### 适用场景

- 数据库连接池
- HTTP 连接复用
- 重量级对象复用

### 使用示例

```java
import com.chua.common.support.concurrent.pool.*;

// 创建连接池
ConnectionPool<MyConnection> pool = new DynamicConnectionPool<>(
    () -> new MyConnection(),  // 创建工厂
    10,                        // 最大连接数
    5                          // 最小连接数
);

// 获取连接
PooledConnection<MyConnection> pooled = pool.borrow();
try {
    MyConnection conn = pooled.get();
    // 使用连接
} finally {
    pooled.returnToPool();
}

// 使用 try-with-resources
try (PooledConnection<MyConnection> pooled = pool.borrow()) {
    MyConnection conn = pooled.get();
    // 使用连接
}
```

---

## ACME 证书模块

### 功能说明

支持 ACME 协议的 SSL 证书自动申请，包括 Let's Encrypt 等 CA 证书和基于 JDK 的自签名证书。

### 适用场景

- 自动化 HTTPS 证书申请
- 开发/测试环境自签名证书
- 证书自动续期

### 使用示例

```java
import com.chua.common.support.lang.acme.*;

// 一键申请 Let's Encrypt 证书
var adaptor = StandardAcmeServerAdaptor.builder().build();
adaptor.initAccountForAcme(
    "https://acme-v02.api.letsencrypt.org/directory",
    "admin@example.com",
    AcmeBackendType.ACME4J
);
String certPath = adaptor.applyAndReturnPathForAcme(
    List.of("example.com", "www.example.com"),
    AcmeValidationType.HTTP_01,
    "./acme-certs"
);

// JDK 自签名证书（开发环境）
JdkAcme acme = new JdkAcme();
AcmeCertificate cert = acme.requestCertificate(
    List.of("localhost", "127.0.0.1"),
    AcmeValidationType.HTTP_01
);
System.out.println(cert.getCertificateContent());  // PEM 证书
System.out.println(cert.getPrivateKey());          // PKCS#8 私钥
```

> **注意**：JDK 自签名证书需添加 JVM 参数：`--add-exports java.base/sun.security.x509=ALL-UNNAMED`

---

## AI 图像生成模块

### 功能说明

对接主流 AI 图像生成服务，支持 Stable Diffusion WebUI、即梦等平台。同时提供 `TextChat` 和 `ImageChat`
统一门面，按 SPI 自动封装聊天、文生图、图生图、文生音频、人脸、OCR、通用检测和图像理解等能力。

### 适用场景

- AI 文生图应用
- 图像创意生成
- 批量图片生成
- 统一 SPI 能力入口
- 文本、图片、多模态能力组合调用

### TextChat / ImageChat 统一门面

`TextChat` 面向文本输入，聚合 `ChatClient`、`ImageGenerationsClient`、`TextToImageGenerator`、
`TextToAudioGenerator`、`SpeechSynthesizer`。`ImageChat` 面向图片输入，聚合 `ImageToImageGenerator`、
`ImageGenerationsClient`、`FaceClient`、`OcrClient`、`Detector`、`ImageUnderstandingClient`。

#### TextChat 链式调用示例

```java
TextChat textChat = TextChat.builder()
    .provider("safetensors")
    .config(configuration)
    .model("facade-image")
    .size("1024*1024")
    .number(1)
    .languageCode("zh-CN")
    .voiceName("zf_xiaoxiao")
    .audioEncoding("wav")
    .build();

String answer = textChat.chatSync("你好");
ImageGenerationsAddTaskV1Response image = textChat.image("a small blue square");
ImageGenerationsAddTaskV1Response refObject = textChat.image((Object) referenceBytes, "make it warmer");
ImageGenerationsAddTaskV1Response refUrl = textChat.image(url, "make it warmer");
ImageGenerationsAddTaskV1Response refString = textChat.image("tmp/source.png", "make it warmer");
ImageGenerationsAddTaskV1Response refFile = textChat.image(file, "make it warmer");
ImageGenerationsAddTaskV1Response refPath = textChat.image(path, "make it warmer");
ImageGenerationsAddTaskV1Response refBytes = textChat.image(referenceBytes, "make it warmer");
ImageGenerationsAddTaskV1Response refBuffered = textChat.image(bufferedImage, "make it warmer");
ImageGenerationsAddTaskV1Response refStream = textChat.image(inputStream, "make it warmer");
ImageGenerationsSearchTaskV1Response search = textChat.image(
    ImageGenerationsSearchTaskV1Request.builder().taskId(image.getTaskId()).build()
);
BufferedImage bufferedImage = textChat.imageSync("a simple icon");
byte[] audio = textChat.audio("你好，这是一次语音合成测试。");
```

#### ImageChat 链式调用示例

```java
ImageChat imageChat = ImageChat.builder()
    .provider("safetensors")
    .config(configuration)
    .model("facade-image2image")
    .size("1024*1024")
    .number(1)
    .build();

ImageGenerationsAddTaskV1Response imageObject = imageChat.image((Object) referenceBytes, "slightly more contrast");
ImageGenerationsAddTaskV1Response imageUrl = imageChat.image(url, "slightly more contrast");
ImageGenerationsAddTaskV1Response imageString = imageChat.image("tmp/source.png", "slightly more contrast");
ImageGenerationsAddTaskV1Response imageFile = imageChat.image(file, "slightly more contrast");
ImageGenerationsAddTaskV1Response imagePath = imageChat.image(path, "slightly more contrast");
ImageGenerationsAddTaskV1Response imageBytes = imageChat.image(referenceBytes, "slightly more contrast");
ImageGenerationsAddTaskV1Response imageBuffered = imageChat.image(bufferedImage, "slightly more contrast");
ImageGenerationsAddTaskV1Response imageStream = imageChat.image(inputStream, "slightly more contrast");
ImageGenerationsSearchTaskV1Response search = imageChat.image(
    ImageGenerationsSearchTaskV1Request.builder().taskId(imageString.getTaskId()).build()
);
FaceClient.FaceDetectionResult face = imageChat.face("tmp/lena.jpg");
FaceClient.FeatureResult feature = imageChat.faceFeature("tmp/lena.jpg");
double score = imageChat.faceCompare("tmp/lena.jpg", "tmp/lena.jpg");
String ocrText = imageChat.ocr("tmp/ocr.png");
OcrClient.OcrResult ocrDetail = imageChat.ocrDetail("tmp/ocr.png");
PredictResultObject<PredictResult> detect = imageChat.detect("tmp/detect.png");
PredictResultObject<PredictResult> dect = imageChat.dect("tmp/detect.png");
String summary = imageChat.understand(imageBytes, "描述图片");
```

#### 创建方法

| 类型 | 方法 | 说明 |
| --- | --- | --- |
| `TextChat` / `ImageChat` | `auto()` | 使用默认 SPI 配置自动创建门面 |
| `TextChat` / `ImageChat` | `auto(DetectionConfiguration configuration)` | 使用检测配置自动创建门面 |
| `TextChat` / `ImageChat` | `create(DetectionConfiguration configuration)` | `auto(configuration)` 的语义别名 |
| `TextChat` / `ImageChat` | `create(String provider, Object config)` | 指定 SPI provider 和配置对象创建 |
| `TextChat` / `ImageChat` | `create(String provider, String apiKey)` | 使用 provider 和 apiKey 创建 |
| `TextChat` / `ImageChat` | `create(String provider, String apiKey, String baseUrl)` | 使用 provider、apiKey、baseUrl 创建 |
| `TextChat` / `ImageChat` | `of(DetectionConfiguration configuration)` | `create(configuration)` 的语义别名 |
| `TextChat` / `ImageChat` | `of(String provider, String apiKey, String model)` | 指定 provider、apiKey、model 创建 |
| `TextChat` / `ImageChat` | `of(String provider, String apiKey, String baseUrl, String model)` | 指定 provider、apiKey、baseUrl、model 创建 |
| `TextChat` / `ImageChat` | `builder()` | 进入链式构建模式 |

#### TextChat Builder 链式方法

| 方法 | 说明 |
| --- | --- |
| `provider(String provider)` | 指定 SPI provider，例如 `safetensors`、`openai`、`zhipu` 等 |
| `config(Object config)` | 指定配置对象，通常为 `DetectionConfiguration` 或 provider 自定义配置 |
| `model(String model)` | 指定模型名称，传递给底层 SPI 实现 |
| `size(String size)` | 指定图片尺寸，例如 `1024*1024` |
| `number(int number)` | 指定图片生成数量 |
| `languageCode(String languageCode)` | 指定音频语言，例如 `zh-CN` |
| `voiceName(String voiceName)` | 指定音色名称 |
| `audioEncoding(String audioEncoding)` | 指定音频编码，例如 `wav`、`mp3` |
| `build()` | 创建 `TextChat` 实例 |

#### ImageChat Builder 链式方法

| 方法 | 说明 |
| --- | --- |
| `provider(String provider)` | 指定 SPI provider，例如 `safetensors` |
| `config(Object config)` | 指定配置对象，通常为 `DetectionConfiguration` 或 provider 自定义配置 |
| `model(String model)` | 指定图生图或图像处理模型名称 |
| `size(String size)` | 指定输出图片尺寸，例如 `1024*1024` |
| `number(int number)` | 指定图片生成数量 |
| `build()` | 创建 `ImageChat` 实例 |

#### TextChat 能力方法

| 方法 | 说明 | 底层 SPI |
| --- | --- | --- |
| `chatSync(String prompt)` | 同步文本聊天，返回模型文本响应 | `ChatClient` |
| `image(String prompt)` | 文生图，提交任务或直接返回同步生成结果 | `ImageGenerationsClient` / `TextToImageGenerator` |
| `image(Object refImage, String prompt)` | 通用参考图入口；保留对象参数，便于框架层动态传参 | `ImageGenerationsClient` / provider 图生图实现 |
| `image(URL refImage, String prompt)` | 使用 URL 参考图执行文生图/图生图 | `ImageGenerationsClient` / provider 图生图实现 |
| `image(String refImage, String prompt)` | 使用路径、URL 或 base64 字符串参考图执行文生图/图生图 | `ImageGenerationsClient` / provider 图生图实现 |
| `image(File refImage, String prompt)` | 使用文件参考图执行文生图/图生图 | `ImageGenerationsClient` / provider 图生图实现 |
| `image(Path refImage, String prompt)` | 使用路径参考图执行文生图/图生图 | `ImageGenerationsClient` / provider 图生图实现 |
| `image(byte[] refImage, String prompt)` | 使用图片字节参考图执行文生图/图生图 | `ImageGenerationsClient` / provider 图生图实现 |
| `image(BufferedImage refImage, String prompt)` | 使用内存图片参考图执行文生图/图生图 | `ImageGenerationsClient` / provider 图生图实现 |
| `image(InputStream refImage, String prompt)` | 使用图片输入流参考图执行文生图/图生图 | `ImageGenerationsClient` / provider 图生图实现 |
| `image(ImageGenerationsAddTaskV1Request request)` | 使用完整任务请求生成图片 | `ImageGenerationsClient` |
| `image(ImageGenerationsSearchTaskV1Request request)` | 查询图片生成任务，优先读取门面任务缓存 | `ImageGenerationsClient` |
| `imageSync(String prompt)` | 同步文生图，直接返回 `BufferedImage` | `TextToImageGenerator` |
| `audio(String prompt)` | 文生音频，使用默认语言、音色和编码 | `TextToAudioGenerator` / `SpeechSynthesizer` |
| `audio(String prompt, String languageCode, String voiceName, String audioEncoding)` | 文生音频，显式指定语言、音色和编码 | `TextToAudioGenerator` / `SpeechSynthesizer` |

#### ImageChat 能力方法

| 方法 | 说明 | 底层 SPI |
| --- | --- | --- |
| `image(Object image, String prompt)` | 通用图生图入口；保留对象参数，便于框架层动态传参 | `ImageToImageGenerator` / `ImageGenerationsClient` |
| `image(URL image, String prompt)` | 使用 URL 参考图执行图生图 | `ImageToImageGenerator` / `ImageGenerationsClient` |
| `image(String image, String prompt)` | 使用路径、URL 或 base64 字符串参考图执行图生图 | `ImageToImageGenerator` / `ImageGenerationsClient` |
| `image(File image, String prompt)` | 使用文件参考图执行图生图 | `ImageToImageGenerator` / `ImageGenerationsClient` |
| `image(Path image, String prompt)` | 使用路径参考图执行图生图 | `ImageToImageGenerator` / `ImageGenerationsClient` |
| `image(byte[] image, String prompt)` | 使用图片字节参考图执行图生图 | `ImageToImageGenerator` / `ImageGenerationsClient` |
| `image(BufferedImage image, String prompt)` | 使用内存图片参考图执行图生图 | `ImageToImageGenerator` / `ImageGenerationsClient` |
| `image(InputStream image, String prompt)` | 使用图片输入流参考图执行图生图 | `ImageToImageGenerator` / `ImageGenerationsClient` |
| `image(ImageGenerationsAddTaskV1Request request)` | 使用完整任务请求执行图生图 | `ImageGenerationsClient` |
| `image(ImageGenerationsSearchTaskV1Request request)` | 查询图生图任务，优先读取门面任务缓存 | `ImageGenerationsClient` |
| `face(Object image)` | 人脸检测，返回人脸数量和位置 | `FaceClient` |
| `faceFeature(Object image)` | 提取人脸特征向量 | `FaceClient` |
| `faceCompare(Object image1, Object image2)` | 比对两张图片的人脸相似度 | `FaceClient` |
| `ocr(Object image)` | OCR 识别，返回文本 | `OcrClient` |
| `ocrDetail(Object image)` | OCR 详细识别，返回文本、文本块和坐标 | `OcrClient` |
| `detect(Object image)` | 通用目标检测 | `Detector` |
| `dect(Object image)` | `detect(image)` 的兼容拼写入口 | `Detector` |

#### safetensors 真实测试记录

| 能力 | 调用 | 结果 |
| --- | --- | --- |
| 参考图文生图 | `TextChat.image(byte[], prompt)` | 成功，输出 `tmp/textchat-ref-real-image.png`，约 303KB |
| 通用检测 | `ImageChat.detect()` | 成功，返回 1 个目标 |
| 通用检测兼容入口 | `ImageChat.dect()` | 成功，返回 1 个目标 |
| 人脸检测 | `ImageChat.face()` | 成功，返回 1 张脸 |
| 人脸特征 | `ImageChat.faceFeature()` | 成功，特征维度 64 |
| 人脸比对 | `ImageChat.faceCompare()` | 成功，同图相似度 1.0 |
| OCR 详细结果 | `ImageChat.ocrDetail()` | 成功返回文本区域；低资源环境未安装完整 OCR 文本识别引擎，文本长度为 0 |
| 图像理解 | `ImageChat.understand()` | 成功返回图像摘要 |
| 文生音频 | `TextChat.audio()` | 成功，输出 `tmp/textchat-real-audio.wav`，约 522KB |
| 文生图任务查询 | `TextChat.image(searchTask)` | 成功，状态 `SUCCESS` |
| 图生图任务查询 | `ImageChat.image(searchTask)` | 成功，状态 `SUCCESS` |

### Stable Diffusion (WebUI)

```java
import com.chua.common.support.ai.image.*;

// 配置
VincentDiagramSetting setting = new VincentDiagramSetting();
setting.setHost("http://127.0.0.1:7860");

// 创建客户端
StableDiffusionImageGenerationsClient client = new StableDiffusionImageGenerationsClient(setting);

// 生成图片
ImageGenerationRequest request = ImageGenerationRequest.builder()
    .prompt("a beautiful sunset over the ocean")
    .negativePrompt("blurry, low quality")
    .size(512)
    .seed(42)
    .build();

ImageGenerationResponse response = client.generate(request);
List<String> images = response.getImages();  // base64 dataURL
```

### 即梦 API

```java
// 配置
VincentDiagramSetting setting = new VincentDiagramSetting();
setting.setHost("https://jimeng-api.example.com");
setting.setAppKey("your-session-id");

// 创建客户端
JimengImageGenerationsClient client = new JimengImageGenerationsClient(setting);

// 生成图片
ImageGenerationRequest request = ImageGenerationRequest.builder()
    .model("jimeng-2.1")
    .prompt("一只可爱的猫咪")
    .size("1024x1024")
    .build();

ImageGenerationResponse response = client.generate(request);
```

---

## 协议服务器模块

### 功能说明

提供多种高性能协议服务器实现，包括 HTTP、WebSocket 等，支持连接管理、线程池配置、TCP 参数优化等功能。

### 性能配置参数

通过 `ServerSetting` 可以配置以下性能相关参数：

| 参数                     | 类型    | 默认值        | 说明                       |
| ------------------------ | ------- | ------------- | -------------------------- |
| `workerThreads`          | int     | CPU核心数*2   | 工作线程数                 |
| `bossThreads`            | int     | CPU核心数     | Boss线程数（Netty）        |
| `maxConnections`         | int     | 1000          | 最大连接数                 |
| `backlog`                | int     | 128           | 连接队列大小               |
| `tcpNoDelay`             | boolean | true          | 禁用Nagle算法，降低延迟    |
| `reuseAddress`           | boolean | true          | 允许地址重用               |
| `receiveBufferSize`      | int     | 65536         | 接收缓冲区大小（字节）     |
| `sendBufferSize`         | int     | 65536         | 发送缓冲区大小（字节）     |
| `connectionTimeoutMillis`| long    | 30000         | 连接超时时间（毫秒）       |
| `idleTimeout`            | int     | 5000          | 空闲超时时间（毫秒）       |

### 使用示例

```java
import com.chua.common.support.protocol.ServerSetting;
import com.chua.common.support.protocol.server.impl.HttpProtocolServer;
import com.chua.common.support.protocol.server.impl.WebSocketProtocolServer;

// 高性能配置示例
ServerSetting setting = ServerSetting.builder()
    .host("0.0.0.0")
    .port(8080)
    // 线程配置
    .workerThreads(32)                    // 32个工作线程
    .bossThreads(8)                       // 8个Boss线程
    // 连接配置
    .maxConnections(10000)                // 最大1万连接
    .backlog(1024)                        // 连接队列1024
    // TCP参数优化
    .tcpNoDelay(true)                     // 禁用Nagle算法
    .reuseAddress(true)                   // 允许地址重用
    .receiveBufferSize(131072)            // 128KB接收缓冲区
    .sendBufferSize(131072)               // 128KB发送缓冲区
    // 超时配置
    .connectionTimeoutMillis(30000)       // 30秒连接超时
    .idleTimeout(60000)                   // 60秒空闲超时
    .build();

// HTTP服务器
HttpProtocolServer httpServer = new HttpProtocolServer(setting);
httpServer.start();

// WebSocket服务器
WebSocketProtocolServer wsServer = new WebSocketProtocolServer(setting);
wsServer.start();

// 获取服务器状态
System.out.println("活跃连接: " + httpServer.getActiveConnections());
System.out.println("总请求数: " + httpServer.getTotalRequests());
System.out.println("线程池状态: " + httpServer.getThreadPoolStatus());
```

### 支持的协议服务器

| 服务器类                     | 协议              | 说明                         |
| ---------------------------- | ----------------- | ---------------------------- |
| `HttpProtocolServer`         | HTTP/HTTPS        | 基于JDK HttpServer的HTTP服务 |
| `WebSocketProtocolServer`    | WebSocket         | 基于JDK HttpServer的WS服务   |
| `WebSocketSyncProtocolServer`| WebSocket Sync    | WebSocket同步数据协议服务    |
| `HttpStreamingSyncServer`    | HTTP Streaming Sync | HTTP流式同步数据服务        |

### HTTP Streaming Sync

#### 接口说明

| 接口 | 方法 | 默认路径 | 说明 |
| --- | --- | --- | --- |
| 流式订阅 | GET | `/stream` | SSE 长连接订阅同步消息 |
| 发布消息 | POST | `/sync` | 发布 `SyncMessage` JSON 数据 |

#### 消息字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `topic` | String | 主题 |
| `data` | Object | 消息体 |
| `clientId` | String | 客户端标识 |

#### 客户端配置参数

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `streamPath` | String | `/stream` | SSE 订阅路径 |
| `syncPath` | String | `/sync` | 发布路径 |
| `connectTimeoutMillis` | long | 10000 | 连接超时时间 |
| `readTimeoutMillis` | long | 60000 | 读取超时时间 |

#### 使用示例

```java
import com.chua.common.support.network.protocol.ClientSetting;
import com.chua.common.support.network.protocol.ServerSetting;
import com.chua.common.support.network.protocol.sync.http.HttpStreamingSyncClient;
import com.chua.common.support.network.protocol.sync.http.HttpStreamingSyncServer;

ServerSetting serverSetting = ServerSetting.builder()
    .host("0.0.0.0")
    .port(8080)
    .build();

HttpStreamingSyncServer server = new HttpStreamingSyncServer(serverSetting);
server.start();

ClientSetting clientSetting = ClientSetting.builder()
    .host("127.0.0.1")
    .port(8080)
    .option("streamPath", "/stream")
    .option("syncPath", "/sync")
    .connectTimeoutMillis(10000)
    .readTimeoutMillis(60000)
    .build();

HttpStreamingSyncClient client = new HttpStreamingSyncClient(clientSetting);
client.connect();
client.subscribe("device.status");
client.publish("device.status", "ok");
```

---

## 死信队列模块

### 功能说明

提供通用的死信队列功能，支持多种存储方式，包括内存、JDBC 数据库、文件系统等。用于保存无法正常处理的消息，支持持久化、TTL、重试、回调等高级功能。

### 适用场景

- 消息处理失败后的兜底存储
- 异步任务的错误重试机制
- 数据处理流程的容错设计
- 工作流状态转移的异常记录
- 消息队列消费失败处理

### 核心类说明

#### 死信队列实现

| 类名 | 存储方式 | 说明 |
| --- | --- | --- |
| `DeadLetterQueue<T>` | - | 死信队列接口，定义基本操作 |
| `DefaultDeadLetterQueue<T>` | 内存 | 默认内存实现，支持可选持久化策略 |
| `JdbcDeadLetterQueue<T>` | 数据库 | 基于 JdbcEngine 的数据库实现，自动建表 |
| `FileDeadLetterQueue<T>` | 文件 | 基于文件系统的实现，每条消息独立文件 |

#### 持久化策略

| 类名 | 说明 |
| --- | --- |
| `PersistenceStrategy<T>` | 持久化策略接口 |
| `AbstractPersistenceStrategy<T>` | 抽象基类，提供后台线程管理和过期处理 |
| `PropertiesPersistenceStrategy<T>` | 基于 Properties 文件的持久化 |
| `DataSourcePersistenceStrategy<T>` | 基于 DataSource 的持久化 |

#### 辅助类

| 类名 | 说明 |
| --- | --- |
| `DeadLetterMessage<T>` | 死信消息包装类，包含标识、内容、原因、时间戳、重试次数 |
| `IdentifierExtractor<T>` | 消息唯一标识提取器（函数式接口） |
| `DeadLetterCallback<T>` | TTL 到期回调处理器 |
| `PersistenceConfig<T>` | 持久化配置类 |
| `PersistenceMode` | 持久化模式枚举 |

### 使用示例

#### 1. 基于 JdbcEngine 的死信队列

直接使用 JdbcEngine 进行数据库操作，消息实时持久化到 `sys_dead_letter_message` 表。

```java
import com.chua.common.support.task.queue.JdbcDeadLetterQueue;
import com.chua.common.support.task.queue.reject.DiscardOldestRejectedStrategy;
import javax.sql.DataSource;

// 创建 JDBC 死信队列
DataSource dataSource = ... // 获取数据源
JdbcDeadLetterQueue<Order> queue = new JdbcDeadLetterQueue<>(dataSource, Order.class);

// 配置队列
queue.withIdentifierExtractor(order -> order.getOrderId())  // 设置标识提取器（必需）
     .withMessageType(Order.class)                          // 设置消息类型
     .withMaxCapacity(10000)                                 // 设置最大容量
     .withRejectedHandler(new DiscardOldestRejectedStrategy<>());  // 设置拒绝策略

// 启动队列（自动创建表）
queue.start();

// 放入死信
Order failedOrder = new Order("ORD-001", "处理失败的订单");
queue.put(failedOrder, "支付超时");

// 获取死信（FIFO）
Order msg = queue.poll();

// 根据标识删除死信
queue.remove("ORD-001");

// 清空队列
queue.clear();

// 停止队列
queue.stop();
```

#### 2. 基于文件的死信队列

每条消息保存为独立的 JSON 文件，文件名为 `{identifier}.json`。

```java
import com.chua.common.support.task.queue.FileDeadLetterQueue;
import com.chua.common.support.task.queue.reject.RejectRejectedStrategy;

// 创建文件死信队列
FileDeadLetterQueue<Task> queue = new FileDeadLetterQueue<>("./data/dead-letters", Task.class);

// 配置队列
queue.withIdentifierExtractor(task -> task.getTaskId())
     .withMaxCapacity(5000)
     .withRejectedHandler(new RejectRejectedStrategy<>());  // 满时抛异常

// 启动队列（自动创建目录，加载已有文件）
queue.start();

// 放入死信（保存为 ./data/dead-letters/dead-letters/TASK-001.json）
Task failedTask = new Task("TASK-001", "执行失败的任务");
queue.put(failedTask, "资源不足");

// 获取死信
Task task = queue.poll();

// 停止队列
queue.stop();
```

#### 3. 内存死信队列（带持久化）

使用内存队列配合持久化策略，支持实时或定时持久化。

```java
import com.chua.common.support.task.queue.DefaultDeadLetterQueue;
import com.chua.common.support.task.queue.strategy.*;

// 创建内存死信队列
DefaultDeadLetterQueue<Message> queue = new DefaultDeadLetterQueue<>();

// 配置持久化
queue.withIdentifierExtractor(msg -> msg.getMsgId())
     .withMaxCapacity(1000)
     .withPersistence(
         new PropertiesPersistenceStrategy<>("./data"),
         PersistenceConfig.<Message>builder()
             .mode(PersistenceMode.REALTIME)           // 实时持久化
             .ttlMillis(3600000L)                       // 1小时TTL
             .maxRetryTimes(3)                          // 最多重试3次
             .callback(new MyDeadLetterCallback())      // TTL到期回调
             .build()
     );

// 启动队列
queue.start();

// ... 使用队列 ...

// 停止队列（自动持久化）
queue.stop();
```

#### 4. 使用 DataSource 持久化策略

```java
import com.chua.common.support.task.queue.DefaultDeadLetterQueue;
import com.chua.common.support.task.queue.strategy.*;

// 创建内存队列 + DataSource 持久化
DefaultDeadLetterQueue<Event> queue = new DefaultDeadLetterQueue<>();

queue.withIdentifierExtractor(event -> event.getEventId())
     .withPersistence(
         new DataSourcePersistenceStrategy<>(dataSource),
         PersistenceConfig.<Event>builder()
             .mode(PersistenceMode.SCHEDULED)           // 定时持久化
             .scheduledIntervalMillis(30000L)           // 每30秒持久化一次
             .ttlMillis(86400000L)                      // 24小时TTL
             .build()
     );

queue.start();
```

#### 5. TTL 回调处理

当消息在死信队列中存放超过指定时间后，自动触发回调。

```java
import com.chua.common.support.task.queue.DeadLetterCallback;

// 实现回调处理器
DeadLetterCallback<Order> callback = new DeadLetterCallback<Order>() {
    @Override
    public boolean onExpired(Order order, String identifier, String reason, 
                             int retryCount, long timestamp) {
        // 尝试重新处理订单
        try {
            orderService.reprocess(order);
            return true;  // 处理成功，消息将被删除
        } catch (Exception e) {
            return false; // 处理失败，消息保留，重试次数+1
        }
    }

    @Override
    public void onMaxRetryExceeded(Order order, String identifier, String reason,
                                   int maxRetryTimes, long timestamp) {
        // 达到最大重试次数，需要人工处理
        alertService.sendAlert("订单处理失败，需人工介入: " + identifier);
    }
};

// 配置使用回调
PersistenceConfig<Order> config = PersistenceConfig.<Order>builder()
    .mode(PersistenceMode.REALTIME)
    .ttlMillis(60000L)      // 1分钟后触发回调
    .maxRetryTimes(3)       // 最多重试3次
    .callback(callback)
    .build();
```

### 配置参数

#### DeadLetterQueue 配置方法

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `withIdentifierExtractor()` | `IdentifierExtractor<T>` | 设置消息唯一标识提取器（**必需**） |
| `withMaxCapacity()` | `long` | 设置最大容量，0 表示无限制 |
| `withRejectedHandler()` | `RejectedStrategy<T>` | 设置容量满时的拒绝策略 |
| `withPersistence()` | `PersistenceStrategy<T>, PersistenceConfig<T>` | 设置持久化策略和配置 |
| `withMessageType()` | `Class<T>` | 设置消息类型用于反序列化（JDBC/File 实现） |

#### PersistenceConfig 配置项

| 属性 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `mode` | `PersistenceMode` | `NONE` | 持久化模式 |
| `ttlMillis` | `Long` | `0` | 消息生存时间（毫秒），0 表示永不过期 |
| `maxRetryTimes` | `Integer` | `0` | 最大重试次数，0 表示不限制 |
| `callback` | `DeadLetterCallback<T>` | `null` | TTL 到期回调处理器 |
| `scheduledIntervalMillis` | `Long` | `30000` | 定时持久化间隔（毫秒） |

#### PersistenceMode 持久化模式

| 模式 | 说明 | 适用场景 |
| --- | --- | --- |
| `NONE` | 无持久化，数据仅在内存 | 临时数据，允许丢失 |
| `REALTIME` | 实时持久化，每次操作立即保存 | 数据安全性要求高 |
| `SCHEDULED` | 定时持久化，后台线程定期保存 | 性能优先，可容忍少量丢失 |

### 拒绝策略

当队列达到最大容量时，由拒绝策略决定如何处理新消息。

| 策略类 | 名称 | 行为 |
| --- | --- | --- |
| `UnlimitedRejectedStrategy<T>` | 无限制 | 忽略容量限制，始终添加（默认） |
| `DiscardRejectedStrategy<T>` | 丢弃新消息 | 直接丢弃新消息，记录警告日志 |
| `DiscardOldestRejectedStrategy<T>` | 丢弃最旧消息 | 删除队列中最旧的消息，添加新消息 |
| `RejectRejectedStrategy<T>` | 拒绝 | 抛出 `RuntimeException` 异常 |

### 数据库表结构

`JdbcDeadLetterQueue` 和 `DataSourcePersistenceStrategy` 使用以下表结构：

```sql
CREATE TABLE sys_dead_letter_message (
    sys_dead_letter_message_identifier VARCHAR(255) PRIMARY KEY,  -- 消息唯一标识
    sys_dead_letter_message_message_json TEXT,                     -- 消息 JSON 内容
    sys_dead_letter_message_reason VARCHAR(500),                   -- 失败原因
    sys_dead_letter_message_timestamp BIGINT,                      -- 消息产生时间戳
    sys_dead_letter_message_retry_count INT DEFAULT 0,             -- 重试次数
    sys_dead_letter_message_created_at TIMESTAMP,                  -- 创建时间
    sys_dead_letter_message_updated_at TIMESTAMP                   -- 更新时间
);
```

> **注意**：表会在队列启动时自动创建（如果不存在）。

### 文件存储结构

`FileDeadLetterQueue` 的文件存储结构：

```
{baseDirectory}/
└── dead-letters/
    ├── ORDER_001.json
    ├── ORDER_002.json
    └── TASK_ABC.json
```

每个 JSON 文件内容格式：

```json
{
  "identifier": "ORDER_001",
  "message": { ... },
  "reason": "支付超时",
  "timestamp": 1702123456789,
  "retryCount": 0
}
```

### 生命周期

```
创建队列 → 配置参数 → start() → 使用(put/poll/remove/clear) → stop()
```

- `start()`: 初始化资源，创建表/目录，加载已有数据
- `stop()`: 持久化数据，释放资源，关闭后台线程

---

## ObjectContext IoC容器模块

### 功能说明

轻量级IoC容器，提供Bean管理、自动装配、插件管理、事件处理等功能，支持SPI驱动的注册器机制。

### 适用场景

- 轻量级应用依赖注入
- 插件化架构
- 事件驱动架构
- 动态Bean管理

### 核心特性

- **多注册器支持**: 默认、注解、Spring、SPI、插件、映射等6种注册器
- **SPI驱动**: 基于SPI机制自动发现和加载注册器
- **生命周期管理**: 支持Bean的初始化、销毁等生命周期
- **事件发布**: 支持事件发布和监听机制
- **映射管理**: 支持HTTP映射定义和管理

### 使用示例

```java
import com.chua.common.support.objects.ConfigureObjectContext;
import com.chua.common.support.objects.impl.DefaultConfigureObjectContext;

// 创建上下文
ConfigureObjectContext context = new DefaultConfigureObjectContext();
context.initialize();

// 注册Bean
context.registerBean("myService", new MyService());

// 获取Bean
MyService service = context.getBean("myService", MyService.class);

// 按类型获取
MyService service2 = context.getBeanOfType(MyService.class);
```

### 详细文档

- [架构文档](./docs/architecture/ObjectContext.md)
- [初始化流程](./docs/flow/ObjectContext.md)

---

## Task 任务调度模块

### 功能说明

提供任务调度、执行、重试、限流、熔断等功能，支持多种调度策略和执行器。

### 适用场景

- 定时任务调度
- 异步任务执行
- 任务重试机制
- 限流和熔断

### 核心特性

- **多种调度策略**: 支持Cron、固定延迟、固定频率等
- **任务重试**: 支持可配置的重试策略
- **限流熔断**: 支持限流和熔断器
- **任务监控**: 支持任务执行监控和统计

### 使用示例

```java
import com.chua.common.support.task.scheduler.TimeScheduler;

// 创建调度器
TimeScheduler scheduler = new JavaTimeScheduler();

// 注册定时任务
scheduler.register("task1", "0 0 12 * * ?", () -> {
    System.out.println("执行任务");
});

// 启动调度器
scheduler.start();
```

### 详细文档

- [架构文档](./docs/architecture/Task.md)
- [执行流程](./docs/flow/Task.md)

---

## Orchestrator 任务编排模块

### 功能说明

提供基于依赖图的任务编排能力，支持同步/异步执行、并行分叉与汇聚策略、超时控制、事件监听等能力。

### 核心特性

- **依赖图校验**：自动校验依赖关系并提供循环检测能力
- **并行汇聚**：支持 ALL/ANY/COUNT 多种汇聚策略
- **超时控制**：并行节点支持超时控制，超时未完成的子节点将标记为超时
- **事件监听**：支持编排过程事件监听与日志记录
- **依赖失败自动完成**：任务节点支持依赖失败时自动完成策略
- **循环执行**：循环节点支持多次迭代执行

### 并行汇聚参数

- **joinStrategy**：汇聚策略，可选 ALL/ANY/COUNT
- **joinCount**：COUNT 策略下的最少完成数量
- **timeout**：并行节点超时时间（默认 5 分钟）
- **fork 节点**：由并行节点统一调度执行，不作为入口节点单独调度

### 使用示例

```java
import com.chua.common.support.orchestrator.Orchestrator;
import com.chua.common.support.orchestrator.builder.FlowNodeBuilder;
import com.chua.common.support.orchestrator.node.ParallelFlowNode;
import com.chua.common.support.orchestrator.node.impl.DefaultParallelFlowNode;

import java.time.Duration;

var taskA = FlowNodeBuilder.task("taskA", "准备A")
    .run(ctx -> ctx.setVariable("a", "ok"))
    .build();
var taskB = FlowNodeBuilder.task("taskB", "准备B")
    .run(ctx -> ctx.setVariable("b", "ok"))
    .build();
var taskC = FlowNodeBuilder.task("taskC", "汇总")
    .run(ctx -> {
        var a = ctx.getVariable("a", String.class);
        var b = ctx.getVariable("b", String.class);
    })
    .build();

DefaultParallelFlowNode parallel = new DefaultParallelFlowNode("parallel", "并行汇聚");
parallel.addForkNode("taskA");
parallel.addForkNode("taskB");
parallel.setJoinStrategy(ParallelFlowNode.JoinStrategy.COUNT);
parallel.setJoinCount(1);
parallel.setTimeout(Duration.ofSeconds(30));
parallel.setNext("taskC");

var orchestrator = Orchestrator.builder("demo-flow")
    .addNodes(parallel, taskA, taskB, taskC)
    .addDependency("taskA", "parallel")
    .addDependency("taskB", "parallel")
    .sync()
    .build();

var result = orchestrator.execute();
```

### 循环节点示例

```java
import com.chua.common.support.orchestrator.Orchestrator;
import com.chua.common.support.orchestrator.builder.FlowNodeBuilder;

var syncTask = FlowNodeBuilder.task("sync-task", "同步数据")
    .run(ctx -> ctx.setVariable("sync_complete", true))
    .build();

var loop = FlowNodeBuilder.loop("loop-1", "数据同步循环")
    .body("sync-task")
    .until(ctx -> ctx.getVariable("sync_complete", Boolean.class))
    .maxIterations(10)
    .then("end")
    .build();

var end = FlowNodeBuilder.task("end", "结束")
    .run(ctx -> ctx.setVariable("done", true))
    .build();

var orchestrator = Orchestrator.builder("loop-flow")
    .addNodes(loop, syncTask, end)
    .addDependency("sync-task", "loop-1")
    .sync()
    .build();

var result = orchestrator.execute();
```

---

## Sync 数据同步模块

> 数据同步功能已统一迁移至 **`utils-support-datasync-starter`** 模块，使用全新的 Agent/Distributor 架构。
> 旧版 SyncFlow (Input/Output/Sink) 体系已废弃，common-starter 中不再包含 sync 模块。

### 新版架构 (datasync-starter)

- **Agent**: 负责数据采集与推送的独立单元
- **Distributor**: 负责数据分发与负载均衡
- **SyncDataPipeline**: 编排 Agent 与 Distributor 的执行流程
- **配置驱动**: 支持 YAML/Properties 等外部配置加载

### 快速指引

- 如需使用数据同步功能，请引入 `utils-support-datasync-starter` 依赖
- 详细文档见对应模块下的 README

---

## Data 数据查询模块

### 功能说明

提供数据库ORM、查询构建器、多数据源支持、分库分表等功能。

### 适用场景

- 数据库操作
- 复杂查询构建
- 多数据源管理
- 分库分表

### 核心特性

- **ORM支持**: 类似MyBatis-Plus的ORM功能
- **查询构建器**: 链式查询构建
- **多数据源**: 支持多数据源切换
- **分库分表**: 支持分库分表策略

### 使用示例

```java
import com.chua.common.support.data.query.Engine;

// 创建查询引擎
Engine engine = Engine.create()
    .addDataSource("db1", dataSource1)
    .addDataSource("db2", dataSource2)
    .setDefaultDataSource("db1");

// 执行查询
List<User> users = engine.query(User.class)
    .eq("age", 25)
    .list();
```

### 详细文档

- [架构文档](./docs/architecture/Data.md)

---

## Lang 语言特性模块

### 功能说明

提供表达式解析、脚本执行、Lambda元数据、代理等功能。

### 适用场景

- 动态表达式计算
- 脚本执行
- 动态代理
- Lambda表达式处理

### 核心特性

- **表达式解析**: 支持EL表达式、SQL表达式等
- **脚本执行**: 支持多种脚本语言
- **Lambda元数据**: 支持Lambda表达式的元数据获取
- **代理支持**: 支持动态代理和AOP

### 使用示例

```java
import com.chua.common.support.lang.expression.parser.ExpressionParser;

// 创建表达式解析器
ExpressionParser parser = ExpressionParser.create("el");

// 设置变量
parser.setVariable("x", 10);
parser.setVariable("y", 20);

// 解析表达式
Object result = parser.parseExpression("x + y");
```

### 详细文档

- [架构文档](./docs/architecture/Lang.md)

---

## 文档中心

详细的架构和流程文档请参考 [文档中心](./docs/README.md)

---

## 依赖要求

- **Java**: 8+
- **Lombok**: 编译时依赖
- **Jackson**: JSON 处理（可选）
- **BouncyCastle**: 国密算法支持（可选）
- **Apache Commons Compress**: TAR/GZ/XZ 解压支持（可选）

---

## 许可证

本项目采用 Apache License 2.0 许可证。

---

## 更新日志

详见 [CHANGELOG.md](CHANGELOG.md)
