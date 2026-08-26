# utils-support-crypto-starter

系统加密模块：以**链式 API** 完成密钥策略、生命周期、密钥载体与配置文件加密的全套配置，适配 **SpringBoot / FatJar / 普通 Java** 等运行形态。

## 功能特性

| 能力 | 说明 |
|---|---|
| 密钥策略（链式） | `CUSTOM` 自定义口令派生 KEK；`SERVER_BOUND` 绑定服务器硬件指纹（主机名/MAC/OS/CPU），密钥文件离开本机不可解 |
| 生命周期（链式） | `ONE_TIME` 一次性读取即销毁（落盘副本安全擦除）；`PERSISTENT` 持久 |
| 密钥隐私存储 | 载体中仅存 KEK(AES-256-GCM) 封装后的主密钥密文 + HMAC 防篡改，明文只存活于进程内存，关闭即擦除 |
| 数据加密 | AES-256-GCM 认证加密，每次随机 IV，Base64 输入输出 |
| 配置文件加密（链式） | 支持整文件加密（`#!CHKF-CONFIG:1` 标记）与单值加密（`ENC(...)` 包裹），可保留 `*.bak` 明文备份 |
| 密钥文件 | 默认 `{user.home}/.chua/crypto/master.key`；相对路径按 工作目录 → Jar 目录(FatJar) → 用户目录 解析 |
| SpringBoot | 自动装配 `Crypto` Bean；启动期透明解密已加密配置文件与 `ENC(...)` 配置值 |
| **程序包加密** | **对 SpringBoot FatJar / 可执行 Jar 整体加密：类文件逐条目加密、依赖包(BOOT-INF/lib/*.jar)整体加密、配置文件随包加密；注入零依赖引导器，运行期透明解密加载** |

## 快速开始

### 1. 普通 Java / FatJar

```java
// 绑定服务器 + 持久密钥文件
Crypto crypto = Crypto.create()
        .keyPolicy(KeyPolicy.SERVER_BOUND)     // 密钥策略：自定义 CUSTOM / 绑定服务器 SERVER_BOUND
        .lifecycle(KeyLifecycle.PERSISTENT)    // 生命周期：一次性 ONE_TIME / 持久 PERSISTENT
        .keyFile("security/master.key")        // 密钥文件（相对路径自动解析）
        .build();

String cipher = crypto.encryptToString("hello");
String plain  = crypto.decryptToString(cipher);
crypto.close();                                // 仅擦除内存密钥

// 一次性读取即销毁
Crypto ephemeral = Crypto.create()
        .keyPolicy(KeyPolicy.CUSTOM)
        .secret("passphrase".toCharArray())
        .lifecycle(KeyLifecycle.ONE_TIME)
        .keyFile("security/once.key")
        .build();                              // 初始化即读取并销毁落盘副本

// 纯内存载体（不落盘）
Crypto mem = Crypto.create().memory().build();
```

### 2. 配置文件随系统一起加密

```java
Crypto crypto = Crypto.create()
        .encryptConfig(true)                                   // 是否一起处理配置文件
        .configFile("application.yml", "application-prod.yml") // 参与加密的配置
        .configBackup(true)                                    // 保留 *.bak 明文备份
        .keyFile("security/master.key")
        .build();

crypto.encryptConfigFiles();   // 批量加密（原位替换为标记行+密文）
crypto.decryptConfigFiles();   // 批量解密到同名 *.dec
```

单值加密（其余配置保持明文可读）：

```properties
spring.datasource.password=ENC(hR2Pf9x...)
```

```java
crypto.encryptValue("p@ssw0rd");   // -> ENC(...)
crypto.decryptValue("ENC(hR2Pf9x...)"); // -> p@ssw0rd
```

### 3. 私钥文件（无密码启动）

```java
// SERVER_BOUND 打包后，从包内提取注册用的私钥封装块（或经校验服务器下发）
// 目标机器运行：凭私钥文件即可，无需口令
java -Dchua.crypto.key-file=/secure/key.bin -jar app-secure.jar
```
### 4. SpringBoot

引入依赖后自动生效，业务代码直接注入：

```java
@Autowired
private Crypto crypto;
```

配置项（`application.yml`）：

```yaml
chua:
  crypto:
    enabled: true                  # 是否启用（默认 true）
    key-policy: SERVER_BOUND       # CUSTOM / SERVER_BOUND
    lifecycle: PERSISTENT          # ONE_TIME / PERSISTENT
    store-type: FILE               # FILE / MEMORY
    key-file: security/master.key
    secret: ${CHUA_CRYPTO_SECRET}  # 建议环境变量注入，勿提交仓库
    server-id: node-prod-01        # 可选：固定指纹（容灾迁移）
    encrypt-config-files: true     # 启动期解密已加密的配置文件
    config-files: application.yml, application-prod.yml
```

已加密的配置文件在磁盘保持密文形态，由 `CryptoEnvironmentPostProcessor` 在启动期解密装载进 Environment；
`ENC(...)` 单值经 `EncryptedPropertySource` 读取时透明解密。普通 Java/FatJar 不经过 Spring 时以上能力均可用链式 API 手动完成。

### 5. 程序包加密（SpringBoot FatJar / 可执行 Jar）

#### 5.1 完整流程示例

**第一步：准备打包机 classpath**（`pack-cp.txt`，一行一个或分号分隔）：

```
utils-support-crypto-starter-4.0.0.42.jar      本模块
utils-support-common-starter-4.0.0.42.jar      common 基础包
asm-9.9.1.jar / asm-commons-9.9.1.jar          混淆用
guava-33.4.8-jre.jar / javassist-3.30.2-GA.jar / slf4j-api-2.0.5.jar
```

**第二步：一条命令加密**（在打包机上执行）：

```bash
java -cp "<pack-cp.txt 内容>" com.chua.crypto.support.pack.CryptoPackCli \
     --source app.jar \
     --output app-secure.jar \
     --policy SERVER_BOUND            # 或 CUSTOM --pin xxx
```

可选参数：`--encrypt-config`(默认开) `--no-obfuscate` `--rename-privates` `--server-id node1`

**第三步：发行运行**（目标机器无需任何额外文件）：

```bash
java -jar app-secure.jar                              # SERVER_BOUND：仅授权机器可运行
java -Dchua.crypto.pin=xxx -jar app-secure.jar        # CUSTOM 口令策略
CHUA_CRYPTO_PIN=xxx java -jar app-secure.jar          # 环境变量等价
java -Dchua.crypto.server-id=node1 -jar app-secure.jar  # 容灾迁移固定指纹
derive-key | java -Dchua.crypto.key-from-stdin=true -jar app-secure.jar   # 外部密钥管道
```

发行脚本建议追加 `-XX:+DisableAttachMechanism -Dchua.crypto.guard=strict`。

#### 5.2 打包动作明细
- `*.class` 全部逐条目 CHKJ(AES-256-GCM) 加密；
- `BOOT-INF/lib/*.jar` **依赖包**整体加密（重复打包安全，已加密条目透传）；
- 主密钥以 CHKF 封装块内嵌 `META-INF/chua-crypto.key`（策略/口令/指纹体系完全复用）；
- Manifest `Main-Class` 替换为零依赖引导器 `CryptoLauncher`，原主类记录于 `Chua-Original-Main-Class`。

发行包运行方式（无需额外 jar / agent）：

```bash
java -jar app-secure.jar                                  # SERVER_BOUND：仅打包机可直接运行
java -Dchua.crypto.pin=xxx -jar app-secure.jar            # CUSTOM 口令策略
CHUA_CRYPTO_PIN=xxx java -jar app-secure.jar              # 环境变量等价形式
java -Dchua.crypto.server-id=node1 -jar app-secure.jar    # 容灾迁移固定指纹
```

运行期引导器流程：解封主密钥 → 依赖包整体解密至进程私有临时目录（JVM 退出自动清理）→
自定义类加载器对类与资源透明解密。包内配置文件磁盘始终密文、应用读取时自动明文化。

## 密钥载体二进制格式

```
[魔数4B CHKF][版本1B][策略标志1B][密钥ID8B][盐16B][IV12B][封装主密钥N字节][HMAC-SHA256 32B]
```

- 主密钥 256 位，PBKDF2-HmacSHA256(21 万次迭代) 派生 KEK 封装，HMAC 常量时间比对防时序攻击；
- 写入采用临时文件 + 原子移动；销毁采用整文件覆写零后删除。

## SPI 扩展

实现 `com.chua.crypto.support.store.SecretKeyStore` 并注册到
`META-INF/extensions/com.chua.crypto.support.store.SecretKeyStore`（`别名=实现类全名`），
即可通过 `Crypto.create().<自定义载体>` 接入更多介质（如智能卡、KMS 等）。

## 安全须知

1. `CUSTOM` 口令丢失 = 数据永久不可恢复；生产环境建议环境变量注入而非硬编码。
2. `ONE_TIME` 销毁后历史密文无法再解，属预期行为。
3. `SERVER_BOUND` 依赖硬件指纹稳定性；虚拟化/容器场景建议显式固定 `server-id`
   （或设置环境变量 `CHUA_CRYPTO_SERVER_ID`、系统属性 `chua.crypto.server-id`）。

## Maven

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-crypto-starter</artifactId>
    <version>4.0.0.42</version>
</dependency>
```
