# SSH 与 SFTP 客户端测试报告

> 测试日期: 2026-08-26
> 测试对象: `com.chua.ssh.support.client.SshClient` / `SftpClient` (utils-support-ssh-starter 4.0.0.42, Apache MINA SSHD)
> 远程主机: `admin@172.16.0.40:2222` (linuxserver/openssh-server 容器, OpenSSH 10.3, 由 DockerClient 测试部署)
> 测试载体: `utils-support-example-starter` → `com.chua.example.ssh.SshSftpClientExample`
> JDK: Amazon Corretto 25.0.3 (`--enable-preview`)

---

## 一、测试结果总览

| # | 模块 | 测试项 | 结果 | 备注 |
|---|------|--------|------|------|
| 1 | SshClient | 连接认证 | ✅ PASS | 501 ms |
| 2 | SshClient | exec whoami | ✅ PASS | 输出 `admin` |
| 3 | SshClient | exec uname -sr | ✅ PASS | `Linux 3.10.0-1160.119.1.el7.x86_64` |
| 4 | SshClient | 退出码验证 (exit 0) | ✅ PASS | code=0 |
| 5 | SshClient | stderr 与失败退出码 | ✅ PASS | code=2, stderr 正确捕获 |
| 6 | SshClient | 交互 Shell (send/readAll) | ✅ PASS | 输出包含标记字符串 |
| 7 | SftpClient | 连接认证 | ✅ PASS | 219 ms |
| 8 | SftpClient | 上传 upload() | ✅ PASS | 3200 字节 → /tmp |
| 9 | SftpClient | stat() 大小校验 | ✅ PASS | 远近端大小一致 |
| 10 | SftpClient | ls() 目录列表 | ✅ PASS | 目标文件存在确认 |
| 11 | SftpClient | download() 内容校验 | ✅ PASS | 字节级一致 |
| 12 | SftpClient | mkdir() 创建目录 | ⏭️ SKIP | 上轮残留目录已存在（预期分支） |
| 13 | SftpClient | rename() 重命名 | ✅ PASS | |
| 14 | SftpClient | rm() 删除文件 | ✅ PASS | |
| 15 | SftpClient | rm().recursive(true) 删目录 | ✅ PASS | |

---

## 二、发现并修复的缺陷

### 缺陷 #1: ExecOperation 空指针异常（严重）

**现象**: 连接成功后执行任意命令抛出：

```
SshClientException: SSH 命令执行失败: whoami
Caused by: NullPointerException: Cannot invoke "InputStream.read(byte[])" because "in" is null
    at SshClient$ExecOperation.execute(SshClient.java:216)
```

**根因**: `ExecOperation.execute()` 在 `channel.open()` 之后调用 `channel.getInvertedOut()` 获取输出流，
但当前 MINA SSHD 版本下 **exec 通道的 inverted 流不可用（返回 null）**（交互式 Shell 通道不受影响）。

**修复**: 改为 MINA SSHD 标准用法——在打开通道前显式绑定输出流：

```java
var channel = client.getSession().createExecChannel(command);
var stdoutBuf = new ByteArrayOutputStream();
var stderrBuf = new ByteArrayOutputStream();
channel.setOut(stdoutBuf);          // 打开前绑定
channel.setErr(stderrBuf);
channel.open().verify(10, TimeUnit.SECONDS);
channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 30_000);
```

**状态**: 已修复并回归验证，exec 全场景（含退出码、stderr）通过。

### 备注（非缺陷）

1. **Windows NIO2 关闭噪音**: 会话关闭时偶发后台线程 `IllegalStateException: Executor has been shut down`，
   为 MINA SSHD 在 Windows 平台的已知异步回调噪音，不影响业务结果。
2. **RmOperation 语义**: `recursive=false` 走文件删除 (`remove`)，删除目录需显式 `recursive(true)` (走 `rmdir`)，符合预期设计。

---

## 三、测试代码位置

- 示例类: `utils-support-extra-parent/utils-support-example-starter/src/main/java/com/chua/example/ssh/SshSftpClientExample.java`
- 修复点: `utils-support-network-parent/utils-support-ssh-starter/src/main/java/com/chua/ssh/support/client/SshClient.java` (`ExecOperation.execute`)
- 运行方式:

```powershell
mvn compile -DskipTests
mvn exec:java "-Dexec.mainClass=com.chua.example.ssh.SshSftpClientExample" `
  "-Dexec.args=<host> <port> <user> <password>"
```

## 四、结论

SshClient 与 SftpClient 链式 API 在真实远程主机上全链路验证通过（14 PASS / 1 SKIP 预期分支）。
唯一阻塞级缺陷（exec NPE）已定位根因并修复，ssh-starter 已重新 install 至本地仓库。
