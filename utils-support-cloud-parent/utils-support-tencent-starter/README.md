# Utils Support Tencent Starter

微信开放平台集成模块，提供小程序/公众号/APP 的 unionid-openid 映射管理与转换能力。

---

## 功能概览

| 功能 | 说明 |
|------|------|
| unionid-openid 映射管理 | 存储同一微信开放平台下所有应用的用户映射关系 |
| 通过 unionid 查询所有 openid | 根据 unionid 查找用户在各平台的 openid |
| 通过 openid 反查 unionid | 根据任意平台的 openid 查找用户信息 |
| 小程序 openid → 公众号 openid | 基于已存储映射或调用微信 API 进行转换 |
| 平台类型标记 | 每条映射记录标识来源平台（小程序/公众号/APP/企业微信） |
| 持久化存储 | 基于 FileEngine 自动持久化到 JSON 文件 |

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-tencent-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 2. 配置

```yaml
plugin:
  tencent:
    open-platform:
      enable: true
      component-app-id: wx1234567890abcdef
      component-app-secret: your_component_app_secret
      default-mini-app-id: wx_mini_appid
      storage-path: data/wechat-openid-mapping.json
```

### 3. 注入使用

```java
@Autowired
private WechatOpenPlatformService wechatOpenPlatformService;
```

---

## 配置说明

### 配置前缀

```
plugin.tencent.open-platform
```

### 字段详解

| 字段 | 类型 | 默认值 | 必填 | 说明 |
|------|------|--------|------|------|
| `enable` | `boolean` | `false` | 是 | 是否启用微信开放平台服务。设为 `true` 后自动注册 `WechatOpenPlatformService` 和 `OpenIdConvertService` Bean |
| `component-app-id` | `String` | `null` | 是 | 微信开放平台第三方平台的 AppID。在[微信开放平台](https://open.weixin.qq.com/)创建第三方平台后获取 |
| `component-app-secret` | `String` | `null` | 是 | 微信开放平台第三方平台的 AppSecret |
| `default-mini-app-id` | `String` | `null` | 否 | 默认小程序 AppID。配置后调用转换接口时可省略 `miniAppId` 参数 |
| `storage-path` | `String` | `wechat-openid-mapping.json` | 否 | 映射数据持久化文件路径。支持相对路径和绝对路径，文件格式为 JSON |

### 完整配置示例

```yaml
plugin:
  tencent:
    # 小程序配置（已有）
    mini-app:
      enable: true
      app-id: wx_mini_appid
      app-secret: wx_mini_secret

    # 微信支付配置（已有）
    wechat-pay:
      enable: true
      app-id: wx_pay_appid
      merchant-id: 1234567890
      # ... 其他支付配置

    # 微信开放平台配置（新增）
    open-platform:
      enable: true
      component-app-id: wx_open_platform_appid
      component-app-secret: open_platform_secret
      default-mini-app-id: wx_mini_appid
      storage-path: data/wechat-openid-mapping.json
```

---

## 使用方式

### 1. 保存/更新用户映射

当用户通过任意应用（小程序、公众号、APP）登录时，调用 `saveOrUpdate` 记录映射关系。

```java
// 用户通过小程序登录
wechatOpenPlatformService.saveOrUpdate(WechatPlatformUser.builder()
    .unionId("oU5xxx")                    // 用户的 unionid
    .appId("wx_mini_appid")               // 小程序 appId
    .openId("oMiniXxx")                   // 小程序 openid
    .platformType(PlatformType.MINI_APP)  // 平台类型
    .nickname("用户昵称")                  // 可选
    .avatar("https://...")                // 可选
    .build());

// 用户通过公众号关注/登录
wechatOpenPlatformService.saveOrUpdate(WechatPlatformUser.builder()
    .unionId("oU5xxx")                            // 同一个 unionid
    .appId("wx_mp_appid")                         // 公众号 appId
    .openId("oMpXxx")                             // 公众号 openid
    .platformType(PlatformType.OFFICIAL_ACCOUNT)
    .build());
```

### 2. 通过 unionid 查询所有 openid

```java
// 获取用户在所有平台的 openid 列表
List<WechatPlatformUser> allUsers = wechatOpenPlatformService
    .getAllOpenIdsByUnionId("oU5xxx");

for (WechatPlatformUser user : allUsers) {
    System.out.println(user.getPlatformType() + ": " + user.getOpenId());
}
// 输出:
// MINI_APP: oMiniXxx
// OFFICIAL_ACCOUNT: oMpXxx
```

### 3. 查询指定平台的 openid

```java
// 获取公众号 openid
String mpOpenId = wechatOpenPlatformService
    .getOfficialAccountOpenId("oU5xxx");

// 获取小程序 openid
String miniOpenId = wechatOpenPlatformService
    .getMiniAppOpenId("oU5xxx");

// 获取指定平台类型
WechatPlatformUser user = wechatOpenPlatformService
    .getOpenIdByUnionId("oU5xxx", PlatformType.OFFICIAL_ACCOUNT);
```

### 4. 小程序 openid 转公众号 openid

```java
// 方式一：基于本地映射（快速，无需网络请求）
String mpOpenId = wechatOpenPlatformService
    .convertToOfficialOpenId("oMiniXxx");

// 方式二：调用微信 API 获取 unionid 后转换（需要网络请求）
String mpOpenId = wechatOpenPlatformService
    .convertToOfficialOpenId("wx_mini_appid", "oMiniXxx");
```

### 5. 使用 OpenIdConvertService（SPI 方式）

```java
@Autowired
private OpenIdConvertService openIdConvertService;

// 直接调用微信开放平台 API 转换
String mpOpenId = openIdConvertService.convertToOfficialOpenId(
    "wx_mini_appid",    // 小程序 appId
    "wx_mp_appid",      // 公众号 appId
    "oMiniXxx"          // 小程序 openid
);
```

---

## 平台类型枚举

`WechatPlatformUser.PlatformType` 枚举值：

| 枚举值 | 说明 | 对应场景 |
|--------|------|----------|
| `MINI_APP` | 微信小程序 | 小程序登录、小程序支付 |
| `OFFICIAL_ACCOUNT` | 微信公众号 | 公众号 OAuth 登录、模板消息 |
| `OPEN_APP` | 微信开放平台 APP | 第三方 APP 登录 |
| `ENTERPRISE` | 企业微信 | 企业微信登录、企业微信消息 |

---

## 数据存储

### 存储格式

映射数据以 JSON 格式持久化到 `storage-path` 指定的文件：

```json
[["id","unionId","appId","openId","platformType","nickname","avatar"],
 [1,"oU5xxx","wx_mini_appid","oMiniXxx","MINI_APP","张三",null],
 [2,"oU5xxx","wx_mp_appid","oMpXxx","OFFICIAL_ACCOUNT","张三",null],
 [3,"oU6yyy","wx_mini_appid","oMiniYyy","MINI_APP","李四",null]]
```

### 存储机制

- **内存索引**：启动时从文件加载到内存，查询走内存索引（O(1) 复杂度）
- **自动持久化**：每次 `saveOrUpdate` 操作后自动写回文件
- **双索引**：同时维护 `unionId → List<User>` 和 `openId → User` 两个索引

---

## 前提条件

使用 openid 转换功能需要满足：

1. **小程序和公众号已绑定到同一个微信开放平台第三方平台**
   - 在[微信开放平台](https://open.weixin.qq.com/)创建第三方平台
   - 将小程序和公众号都关联到该第三方平台

2. **用户已在两端产生过行为**
   - 微信通过 unionid 机制关联同一用户在不同应用的身份
   - 用户需要在小程序和公众号都完成过授权登录

3. **配置第三方平台凭证**
   - `component-app-id`：第三方平台 AppID
   - `component-app-secret`：第三方平台 AppSecret

---

## API 参考

### WechatOpenPlatformService

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `getAllOpenIdsByUnionId(unionId)` | `List<WechatPlatformUser>` | 查询用户在所有平台的 openid |
| `getOpenIdByUnionId(unionId, platformType)` | `WechatPlatformUser` | 查询指定平台类型的用户信息 |
| `getOfficialAccountOpenId(unionId)` | `String` | 获取公众号 openid |
| `getMiniAppOpenId(unionId)` | `String` | 获取小程序 openid |
| `saveOrUpdate(user)` | `void` | 保存或更新用户映射 |
| `convertToOfficialOpenId(miniAppOpenId)` | `String` | 小程序 openid 转公众号 openid（本地映射） |
| `convertToOfficialOpenId(miniAppId, miniAppOpenId)` | `String` | 小程序 openid 转公众号 openid（含 API 调用） |

### OpenIdConvertService

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `convertToOfficialOpenId(miniAppId, officialAppId, miniAppOpenId)` | `String` | 调用微信 API 转换 openid |
| `convertToOfficialOpenId(officialAppId, miniAppOpenId)` | `String` | 使用默认小程序 appId 转换 |

### WechatPlatformUser

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `Long` | 主键 ID |
| `unionId` | `String` | 用户 unionid |
| `appId` | `String` | 应用 appId |
| `openId` | `String` | 用户在该应用下的 openid |
| `platformType` | `PlatformType` | 平台类型枚举 |
| `nickname` | `String` | 用户昵称 |
| `avatar` | `String` | 用户头像 URL |

---

## 依赖关系

```
utils-support-tencent-starter
├── utils-support-common-starter      # 通用工具
├── utils-support-datasource-starter  # FileEngine 存储引擎
├── utils-support-payment-starter     # 支付通道
├── utils-support-auth-starter        # 认证框架
├── weixin-java-open 4.7.3.B         # 微信开放平台 SDK
├── weixin-java-miniapp 4.7.3.B      # 微信小程序 SDK
├── weixin-java-mp 4.7.3.B           # 微信公众号 SDK
└── weixin-java-pay 4.7.3.B          # 微信支付 SDK
```

---

## 注意事项

1. **内存存储**：当前实现使用内存索引 + 文件持久化，适合中小规模应用。大规模场景建议替换为数据库存储
2. **并发安全**：索引操作使用 `ConcurrentHashMap`，文件写入由 FileEngine 保证
3. **文件格式**：FileEngine 默认保存为二维数组格式，加载时自动兼容对象数组格式
4. **Enum 序列化**：`PlatformType` 枚举使用 `@JsonValue`/`@JsonCreator` 注解，确保 JSON 序列化/反序列化正确
