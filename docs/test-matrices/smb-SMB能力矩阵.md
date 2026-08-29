# SMB 单元测试覆盖矩阵

## 版本信息
- 模块：utils-support-example-starter (smb)
- SmbServer：utils-support-smb-starter + utils-support-native-smb (Rust FFI)
- SmbClient：smbj 0.14.0 (Java JNA)
- 作者：CH
- 更新日期：2026-07-29

## 平台支持矩阵

| 平台组合 | 服务器 | 客户端 | 状态 |
|:--------|:------|:------|:----|
| Win Server ↔ Win Client | rust_smb_server.dll (Java 25 FFM) | smbj 0.14.0 | ✅ TC-S01..S03 通过 |
| Linux Server ↔ Win Client | 待构建 (librust_smb_server.so) | smbj 0.14.0 | ❌ 172.16.0.40 主机故障 |
| Win Server ↔ Linux Client | rust_smb_server.dll | smbj 0.14.0 (Linux jar) | ❌ 172.16.0.40 主机故障 |

## SPI 实现覆盖矩阵

| 组件 | 实现技术 | 平台 | Java API |
|:-----|:--------|:-----|:--------|
| SmbServer | Rust + smb-server crate + Java 25 FFM | Win/Linux/macOS | `SmbServer.builder().build().start().stop()` |
| SmbClient | smbj 0.14.0 (JNA) | Win/Linux/macOS | `SmbClient.create(url).connect().login().openShare().listFiles()` |
| RustSmbServerBridge | Java 25 FFM (Panama) | Win/Linux/macOS | `loadLibrary().start(addr, port, share, root, user, pass)` |

## 测试场景覆盖矩阵

| 场景ID | 测试方法 | 入参 | 断言 | 状态 |
|:------|:--------|:-----|:-----|:----|
| TC-S01 | server start/stop | host=127.0.0.1, port=1445, share=testshare | 服务器启动 + 优雅停止 | ✅ |
| TC-S02 | client.create(url) | smb://127.0.0.1:1445/testshare | 客户端对象创建 | ✅ |
| TC-S03 | client.connect() | 上述 URL | TCP 连接成功 | ✅ |
| TC-S04 | client.login() | 匿名 (无 user) | NTLMSSP 认证 | ❌ STATUS_ACCOUNT_DISABLED |
| TC-S04-alt | client.login() | admin/admin | NTLMSSP 认证 | ❌ STATUS_LOGON_FAILURE |
| TC-S05 | client.openShare() | share=testshare | share 连接成功 | ⚠ 依赖 TC-S04 |
| TC-S06 | client.listFiles("/") | share=testshare | 列出 share 内文件 | ⚠ 依赖 TC-S04 |
| TC-S07 | client.upload/download | 文件流 | 文件读写 | ⚠ 依赖 TC-S04 |
| TC-S08 | client.mkdir/delete | path | 目录操作 | ⚠ 依赖 TC-S04 |

## 已实现的 Rust 修复（lib.rs）

在 `src/lib.rs` 中加入：当 `user` 为空时切换 share 至 `public_read_only()` 模式，绕过 `AuthenticatedOnly` 的硬限制。这样可以让 smb-server 接受匿名 NTLMSSP 协商（虽然最终认证仍然失败）。

```rust
let mut share = Share::new(&share_name_clone, backend);
if user_clone.is_empty() {
    // 无用户配置时启用公共模式（匿名/访客访问）
    share = share.public_read_only();
} else {
    share = share.user(&user_clone, Access::ReadWrite);
}
```

## 已知问题

### TC-S04 认证失败（NTLMSSP）
**现象 1**：使用 `admin/admin` 登录返回 `STATUS_LOGON_FAILURE (0xc000006d)`
**现象 2**：使用匿名登录（无 user）返回 `STATUS_ACCOUNT_DISABLED (0xc0000072)`
**根因**：`smb-server` crate 0.4.0 的 NTLMSSP 实现：
- 用户名比对使用的是 `UserCreds` (NT hash) 而非明文
- smbj 0.14.0 客户端在匿名失败时回退到 `guest` 凭据
- smb-server 既不支持 `guest` 也不支持无密码登录

**建议修复路径**：
1. 升级 `smb-server` crate 到支持 NTLMv2 + 完整认证的版本
2. 或使用 jcifs-ng 替代 smbj 作为客户端（兼容性更好）
3. 或在 NTLMSSP handler 中添加对 `guest` 标识的特殊处理

### SmbServerExample 注释已过时
代码注释："Windows 平台上 native DLL 不可用" - 实际 `rust_smb_server.dll` 已成功加载并工作
**位置**: `src/main/java/com/chua/example/smb/SmbServerExample.java:15`

### 跨平台测试受限
**现象**：172.16.0.40 (Docker 主机) 长时间构建 Rust SMB 库后失联，SSH (22) 和 Docker API (2375) 均不可达
**根因**：rust:latest 镜像首次编译 `smb-server` crate 需要从 crates.io 下载大量依赖（约 200+ crates），编译时占用 CPU/内存/磁盘，可能导致 OOM 或内核 panic
**建议**：
1. 限制 cargo 并行构建：`cargo build --release --jobs 1`
2. 使用预构建镜像（如 `rust:slim-bookworm`）减少编译压力
3. 分阶段构建：先 `cargo fetch` 预下载，再 `cargo build --release`
4. 使用 Docker BuildKit 多阶段构建，避免长时间运行的容器

**已完成的准备**：
- ✅ 创建 Linux 容器（seccomp: unconfined）
- ✅ 上传 Rust SMB 源码 tar 包到容器 `/src`
- ✅ 创建 `/src` 目录
- ❌ cargo build 启动后主机失联

## 执行记录

| 日期 | 场景 | 结果 | 备注 |
|:-----|:-----|:-----|:-----|
| 2026-07-29 | TC-S01..S03 (Win↔Win) | ✅ 通过 | rust_smb_server.dll 1.3MB 成功加载；TCP connect OK |
| 2026-07-29 | TC-S04 (Win auth) | ❌ FAIL | NTLMSSP 不兼容（STATUS_ACCOUNT_DISABLED） |
| 2026-07-29 | Lib.rs 改进 | ✅ | 引入 public_read_only 模式支持匿名访问 |
| 2026-07-29 | Win↔Linux 交叉 | ❌ 阻塞 | 172.16.0.40 Docker 主机失联 |
| 2026-07-29 | Linux↔Win 交叉 | ❌ 阻塞 | 同上 |

## 涉及文件

| 路径 | 用途 |
|:-----|:-----|
| `src/main/java/com/chua/example/smb/SmbServerExample.java` | 服务端示例（含已过时的 "Windows DLL 不可用" 注释） |
| `src/main/java/com/chua/example/smb/SmbClientExample.java` | 客户端示例 |
| `src/main/java/com/chua/example/smb/SmbServerClientIntegration.java` | 自定义 Win↔Win 集成测试 |
| `G:\work\utils-support-parent-starter\utils-support-network-parent\utils-support-smb-starter\src\main\java\com\chua\smb\server\SmbServer.java` | SmbServer 主体（继承 AbstractServer） |
| `G:\work\utils-support-parent-starter\utils-support-network-parent\utils-support-smb-starter\src\main\java\com\chua\smb\client\SmbClient.java` | SmbClient 主体（基于 smbj） |
| `G:\work\utils-support-parent-starter\utils-support-network-parent\utils-support-smb-starter\src\main\java\com\chua\smb\bridge\RustSmbServerBridge.java` | Java 25 FFM (Panama) 桥接 |
| `G:\work\utils-support-native-parent\utils-support-native-smb\src\main\rust\src\lib.rs` | Rust FFI 入口（已修改：支持 public_read_only 模式） |
| `G:\work\utils-support-native-parent\utils-support-native-smb\src\main\rust\smb-server-patched\` | smb-server crate 子模块（含 patched NTLMSSP handler） |
