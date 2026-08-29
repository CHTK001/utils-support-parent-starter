# SSH Starter 单元测试覆盖矩阵

> 本文档记录 `utils-support-ssh-starter` 模块的测试覆盖情况。

---

## 测试类

### SshClientTest（本地验证，无需 SSH 服务器）

| 测试项 | 覆盖项 | 状态 |
|--------|--------|------|
| Builder 参数校验 | host/username 为空抛 IllegalArgumentException | ✅ |
| 链式 API 结构 | exec().command() / shell() / terminal().pty().width().height() / forward().local() | ✅ |
| TunnelDefinition 构造 | local/remote/dynamic 三种隧道类型 | ✅ |
| ForwardOperation 模式 | 正向隧道 / 反向隧道 / 动态隧道(SOCKS5) / 绑定地址 | ✅ |
| SftpClient Builder | 参数校验 + 构建 | ✅ |
| ExecResult record | 正常/错误两种场景 | ✅ |

**运行方式：**
```bash
mvn test -pl utils-support-network-parent/utils-support-ssh-starter -Dtest=SshClientTest
```

---

## 覆盖范围

| 操作 | SshClient | SftpClient | SftpPolledDirectory |
|------|-----------|-----------|-------------------|
| 连接/断开 | ✅ | ✅ | ✅ |
| exec 命令执行 | ✅ | — | — |
| shell 交互式 | ✅ | — | — |
| terminal PTY | ✅ | — | — |
| 正向隧道 | ✅ | — | — |
| 反向隧道 | ✅ | — | — |
| 动态隧道(SOCKS5) | ✅ | — | — |
| upload | — | ✅ | — |
| download | — | ✅ | — |
| ls/mkdir/rm/rename/stat | — | ✅ | — |
| 目录轮询 | — | — | ✅ |
