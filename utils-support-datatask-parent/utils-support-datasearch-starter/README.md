# utils-support-datasearch-starter

数据搜索模块，提供视频/音乐资源搜索、WebDAV云存储、JDBC元数据、数据同步输入等能力

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-datasearch-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `AgentEditorProvider` | datasearch 的 MCP 提供器 + Skills 提供器 + Agent Editor 配置读写。 (SPI: `datasearch`) |
| `Converter` | Converter |
| `Splitter` | Splitter |
| `MusicComment` | 音乐评论信息 |
| `MusicOverview` | 音乐概览信息 |
| `MusicPlaylistCategory` | 音乐播放列表分类 |
| `MusicPlaylistCategoryCatalog` | 音乐播放列表分类目录 |
| `MusicPlaylistCategoryGroup` | 音乐播放列表分类组 |
| `MusicPlaylistCategoryResult` | 音乐播放列表分类结果 |
| `MusicPlaylistDetail` | 音乐播放列表详情 |
| `SmsCodeProvider` | 接码（虚拟号码接收短信）SPI：获取全部号码 / 随机号码 / 手动获取短信，集成 `PolledDirectory` 回调监听。实现：`jiemahao`（接码号，短信页受 Cloudflare Turnstile 保护，需可信网络）、`freephonenum`（FreePhoneNum，无验证码，HTTP 直连即可读取真实短信） |
| ... | 共 86 个类 |

---

## 回归自检

三个离线自检已接入 `verify-fixtures` profile，CI 一次跑完（不发起网络请求）：

```bash
mvn -o verify -Pverify-fixtures
```

| execution | 校验内容 |
|---|---|
| `verify-video-spi` | 视频 SPI 注册表：21 个 `ResourceProvider`、1 个 `SubtitleSearchProvider`、注册名大写可反查、`VideoProviderRegistry` 封禁名单大小写不敏感 |
| `verify-music-spi` | 音乐 SPI 注册表：`tx`/`netease` 两个音源、编码与名称非空、注册名大写可反查 |
| `verify-scnet-usage` | SCNet 用量解析夹具：双目录扫描、compaction 事件、缓存/推理拆分、耗时、去重与全零剔除 |

单个示例仍可手动执行，例如 `mvn -o test-compile exec:java@run-video-spi-test`（music / scnet 同理）。
profiles 默认关闭，常规 `mvn verify` 不会触发这些示例。

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-datasearch-starter
├── utils-support-common-starter
```