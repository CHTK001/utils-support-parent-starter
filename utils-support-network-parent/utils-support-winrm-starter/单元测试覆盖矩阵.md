# utils-support-winrm-starter 单元测试覆盖矩阵

## 版本信息
- 模块：utils-support-winrm-starter
- 作者：CH
- 更新日期：2026/07/27
- 版本：4.0.0.42

## 功能概述
本模块提供 WinRM（Windows Remote Management）协议集成，支持通过 SOAP over HTTP 执行远程 Windows 命令。基于标准的 WS-Management 协议实现与 Windows 主机的远程交互。

## SPI 实现覆盖

| SPI 类型 | 实现类 | 基础能力 | 状态 |
|---------|--------|---------|------|
| WinRM 命令执行 | WinRmServerCommandExecutorSpi | 命令执行 | ✅ 待实现 |
| WinRM 文件操作 | WinRmServerHostProtocolSpi | 文件上传/下载 | ✅ 待实现 |
| WinRM 指标采集 | WinRmServerMetricsCollectorSpi | 性能监控 | ✅ 待实现 |
| WinRM 生命周期 | WinRmServerHostLifecycleSpi | 配置校验 | ✅ 已存在 |

## WinRMClient 类能力覆盖

| 方法/特性 | 描述 | 实现状态 |
|-----------|------|---------|
| builder() | 链式构建器 | ✅ |
| connect() | 连接 WinRM 服务 | ✅ |
| disconnect() | 断开连接 | ✅ |
| exec() | 获取命令执行操作 | ✅ |
| ExecOperation.command() | 设置命令 | ✅ |
| ExecOperation.execute() | 执行命令 | ✅ |
| ExecOperation.executeAndGetOutput() | 获取输出结果 | ✅ |
| close() / AutoCloseable | 资源自动管理 | ✅ |

## WinRmExecClient 类能力覆盖

| 方法/特性 | 描述 | 实现状态 |
|-----------|------|---------|
| Constructor(ClientSetting) | 构造方法 | ✅ |
| connect() | 连接 WinRM 服务 | ✅ |
| executeCommand(String) | 执行远程命令 | ✅ |
| closeQuietly() | 安全关闭 | ✅ |

## WinRmFileClient 类能力覆盖

| 方法/特性 | 描述 | 实现状态 |
|-----------|------|---------|
| read(String) | 读取远程文件内容 | ✅ |
| upload(String, String) | 上传文件到远程主机 | ✅ |
| download(String, String) | 从远程主机下载文件 | ✅ |
| exists(String) | 检查文件是否存在 | ✅ |
| delete(String) | 删除远程文件 | ✅ |
| ls(String) | 列出目录内容 | ✅ |
| isDirectory(String) | 检查是否为目录 | ✅ |
| mkdir(String) | 创建远程目录 | ✅ |
| rename(String, String) | 重命名/移动文件 | ✅ |

## SOAP 消息实现

| 操作 | SOAP Action | URI | 状态 |
|------|-------------|-----|------|
| Allocate Shell | `.../WSMAN-ShellCommand/Allocate` | ResourceURI | ✅ |
| Execute Command | `.../WSMAN-ShellCommand/Execute` | shell ID | ✅ |
| Free Shell | `.../WSMAN-ShellCommand/Free` | shell ID | ✅ |

## 认证支持
- Basic Auth（Base64编码） ✅
- NTLM/Kerberos ⏳ （需扩展 okhttp-ntlm 或实现）

## 依赖关系
- 父模块：utils-support-network-parent
- 依赖：
  - com.chua:utils-support-common-starter
  - com.squareup.okhttp3:okhttp
  - com.fasterxml.jackson.dataformat:jackson-dataformat-xml

## 测试计划
1. 单元连接测试：验证 connect() 方法正常分配 Shell
2. 命令执行测试：验证简单命令（如 dir、echo）能正确返回输出
3. 文件传输测试：验证 upload/download 函数正确处理二进制文本
4. 异常处理测试：验证连接失败、命令错误等场景的异常抛出
5. 资源释放测试：确保关闭后 Shell 被正确清理

## 示例代码

```java
// 使用 WinRMClient（链式风格）
WinRMClient winrm = WinRMClient.builder()
    .host("192.168.1.100")
    .port(5985)
    .username("Administrator")
    .password("pass")
    .build();

String output = winrm.exec().command("systeminfo").executeAndGetOutput();
System.out.println(output);

// 或使用 WinRmExecClient（SPI 风格）
ClientSetting setting = ClientSetting.builder()
    .host("192.168.1.100")
    .port(5985)
    .username("Administrator")
    .password("pass")
    .build();

WinRmExecClient client = new WinRmExecClient(setting);
client.connect();
try {
    WinRMCommandResult result = client.executeCommand("ipconfig");
    System.out.println(result.stdout());
} finally {
    client.closeQuietly();
}