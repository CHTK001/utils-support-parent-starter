# NgrokClientExample 单元测试覆盖矩阵

## 版本信息
- 类名：NgrokClientExample
- 模块：utils-support-example-starter
- 作者：CH
- 更新日期：2026-07-28

## 能力点覆盖矩阵

| 能力 ID | 测试方法           | 入参 | 前置条件                 | 断言                                          | 通过条件                          |
|:-------|:------------------|:-----|:------------------------|:---------------------------------------------|:----------------------------------|
| NC-01  | dryRun           | 无   | 不设置 NGROK_AUTHTOKEN   | 日志输出 API 装配预览，进程退出码 0          | 日志含 `NgrokClient.create`       |
| NC-02  | connect          | connect | 有效 token              | Session 已建立，打印 id/metadata             | client.getSession() != null       |
| NC-03  | listen           | listen  | 有效 token              | HTTP 隧道已建立（listen 模式）                | listener.getUrl() 不为空           |
| NC-04  | listen-domain    | listen  | NGROK_DOMAIN 已设置     | 隧道域名等于指定值                            | listener.getUrl().contains(domain) |
| NC-05  | forward          | forward | 有效 token + 目标 URL    | 转发隧道已建立，URL 列表非空                  | client.getUrls() 不为空            |
| NC-06  | forward-domain   | forward | NGROK_DOMAIN 已设置     | 转发隧道域名等于指定值                        | url.contains(domain)              |
| NC-07  | tcp              | tcp     | NGROK_TCP_ADDR 已设置   | TCP 隧道已建立，remoteAddress 匹配            | tcp url.contains(addr)            |
| NC-08  | tcp-missing      | tcp     | 未设置 NGROK_TCP_ADDR   | 进程退出码非 0，日志含错误提示                | 退出码 != 0                       |
| NC-09  | invalid-type     | foo     | 有效 token              | 进程退出码非 0                                | 退出码 != 0                       |
| NC-10  | invalid-token    | forward | 错误 token              | 抛出 RuntimeException 或进程退出码非 0        | 退出码 != 0                       |
| NC-11  | custom-target    | forward | -Dngrok.target=...      | 转发日志输出指定目标 URL                      | 日志含指定 URL                    |
| NC-12  | custom-metadata  | forward | -Dngrok.metadata=...    | Session metadata 等于指定值                   | getMetadata().equals(meta)        |

## 执行记录

| 日期       | type     | token | 域名                 | 目标                      | 结果     | 备注                |
|:-----------|:---------|:------|:---------------------|:--------------------------|:---------|:--------------------|
| 2026-07-28 | connect  | -     | -                    | -                         | 预期通过 | 未设 token，dryRun  |
| 2026-07-28 | forward  | 实际  | example.ngrok-free.app | http://127.0.0.1:8080    | 待验证   | 真实环境需 token    |

## 注意事项

- 测试依赖外网（需能连接 `connect.ngrok-agent.com:443`），CI 环境可跳过
- token 通过环境变量 `NGROK_AUTHTOKEN` 传入，禁止硬编码
- TCP 模式必须先在 ngrok 面板 reserve 一个 TCP 地址，并通过 `NGROK_TCP_ADDR` 指定
