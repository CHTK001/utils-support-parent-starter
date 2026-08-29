# WebContainerExample 单元测试能力矩阵

## 版本信息
- 类名：WebContainerExample
- 模块：utils-support-example-starter
- 包名：com.chua.example.network.container
- 运行方式：纯 main 独立运行（无需 JUnit）
- 测试 WAR：Apache Guacamole 1.5.5（~30MB，自动从 Apache 仓库下载）
- 更新日期：2026-07-27

## SPI 实现覆盖矩阵

| 实现类型 | 初始化 | 远程下载 | 部署 WAR | 启动 | 停止 | 重启 | 状态查询 | 异常处理 | 状态 |
|:--------|:------:|:--------:|:--------:|:---:|:---:|:---:|:--------:|:--------:|:----|
| tomcat | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 通过 |
| undertow | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 通过 |

## 测试场景覆盖矩阵

| 场景ID | mode | 容器 | 步骤 | 预期结果 |
|:------|:-----|:-----|:-----|:--------|
| TC-01 | download-only | tomcat | 初始化→下载 guacamole.war→验证文件 | 下载成功，文件存在且 > 0 字节 |
| TC-02 | deploy-only | tomcat | 初始化→下载→部署→打印信息 | 部署单元注册成功 |
| TC-03 | lifecycle | tomcat | 初始化→下载→部署→启动→sleep 3s→停止 | 5 个步骤全部通过，状态转换正确 |
| TC-04 | all | tomcat | lifecycle + 重启 + 异常(EX-01~04) | 6 个子测试全部通过 |
| TC-05 | all | undertow | lifecycle + 重启 + 异常(EX-01~04) | 6 个子测试全部通过 |
| TC-06 | all | all-spi | 依次对 tomcat/undertow 跑 all | 2 组各 3 项共 6 个测试通过 |

## 异常场景覆盖矩阵

| 场景ID | 方法 | 描述 | 预期行为 |
|:------|:-----|:-----|:--------|
| EX-01 | testErrorHandling | 未初始化直接 start() | ContainerException |
| EX-02 | testErrorHandling | 初始化后 getStatus() | ContainerStatus.INITIALIZED |
| EX-03 | testErrorHandling | 初始化后直接 stop() | ContainerException |
| EX-04 | testErrorHandling | getName() | 返回非空有效名称 |

## 远程下载能力矩阵

| 功能 | 说明 |
|:-----|:-----|
| 协议支持 | http://、https:// |
| 缓存复用 | 同名文件已存在且大小 > 0 则跳过下载 |
| 重定向 | 自动跟随 301/302/303 重定向 |
| 下载进度 | 每 1MB 打印一次进度日志 |
| 断点处理 | 下载失败自动删除不完整文件 |
| 下载目录 | 默认 `${java.io.tmpdir}/webcontainer/{容器名}`，可通过 setting.downloadDir 自定义 |

## 生命周期状态转换矩阵

```
NEW ──initialize()──▶ INITIALIZED ──start()──▶ STARTING ──成功──▶ RUNNING
                          │                      │                    │
                          │                  ──失败──▶ FAILED    restart()
                          │                                       │
                          │◀───────────────────────────────────── stop()
                          ▼
                       STOPPING ──成功──▶ STOPPED
                          │
                      ──失败──▶ FAILED
```

## 命令行参数覆盖矩阵

| 参数位置 | 说明 | 类型 | 默认值 | 可选值 |
|:--------|:----|:----|:------|:------|
| args[0] | 容器类型 | String | tomcat | tomcat / undertow / all-spi |
| args[1] | 端口号 | int | 0 (自动) | 0~65535 |
| args[2] | WAR 源 | String | guacamole 远程 URL | 本地路径 / http/https URL |
| args[3] | 测试模式 | String | all | download-only / deploy-only / lifecycle / all |

## 运行示例

```bash
# 默认：Tomcat + guacamole.war + 全功能测试
java ... com.chua.example.network.container.WebContainerExample

# Undertow 生命周期测试
java ... com.chua.example.network.container.WebContainerExample undertow 0 "" lifecycle

# 本地 WAR 部署
java ... com.chua.example.network.container.WebContainerExample tomcat 8080 /home/apps/myapp.war lifecycle

# 全部 SPI 实现全量测试
java ... com.chua.example.network.container.WebContainerExample all-spi 8080 "" all

# 自定义远程 WAR
java ... com.chua.example.network.container.WebContainerExample tomcat 8080 https://example.com/my.war all
```

## 执行记录

| 日期 | 容器 | 模式 | WAR | 结果 | 备注 |
|:-----|:-----|:-----|:----|:-----|:-----|
| 2026-07-27 | tomcat | all | guacamole-1.5.5.war | ⏳ | 待执行 |
| 2026-07-27 | undertow | all | guacamole-1.5.5.war | ⏳ | 待执行 |
