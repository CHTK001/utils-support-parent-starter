# utils-support-ssh-starter

SSH/SFTP 协议集成，基于 Apache MINA SSHD

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-ssh-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `FileHandler` | Shell 方法注解，用于声明式 SSH Shell 命令。 |
| `SftpClient` | SFTP 链式客户端，基于 Apache MINA SSHD 3.x。 |
| `SshClient` | SSH 链式客户端，基于 Apache MINA SSHD 3.x。 |
| `SftpPolledDirectory` | SFTP 目录轮询实现，基于 Apache MINA SSHD。 |
| `BuiltinShellCommands` | 内置 Shell 命令实现，包含常用的 Unix-like 命令。 |
| `ShellMethodAddressParser` | Shell 方法解析器，扫描 注解生成命令路由映射。 |
| `SshCommandRequest` | SSH 命令请求， 实现。 使用 将用户输入的 Shell 命令解析为路径（命令名）和参数。 |
| `SshCommandResponse` | SSH 命令响应， 实现。 将命令输出写入 SSH 客户端的输出流。 |
| `SshMultiProgress` | SSH 多任务并排进度条，封装 自动适配 SSH 输出流。 |
| `SshProgress` | SSH 进度条简易工具类，封装 自动适配 SSH 输出流。 |
| ... | 共 12 个类 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-ssh-starter
├── utils-support-common-starter
```