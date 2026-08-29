# DatalakeServerExample 单元测试覆盖矩阵

## 版本信息
- 类名：DatalakeServerExample
- 模块：utils-support-example-starter
- 作者：CH
- 更新日期：2026-07-29

## 能力点覆盖矩阵

| 能力 ID | 测试方法               | 前置条件                  | 断言                                  | 通过条件                          |
|:-------|:----------------------|:-------------------------|:-------------------------------------|:----------------------------------|
| DL-01  | testBasicLifecycle    | 默认 Builder              | build() → start() → stop() 不抛异常   | 无异常退出                        |

## 执行记录

| 日期       | type     | 结果     | 备注                                          |
|:-----------|:---------|:---------|:----------------------------------------------|
| 2026-07-29 | basic    | 预期通过 | 默认端口 8700, 可通过 apiServer 注入覆盖        |

## 注意事项

- 默认 ApiServer 使用 `JdkHttpServer` + 端口 8700
- 真实部署时可通过 `dataSyncServer(DataSyncServer)` 注入 DataSync 调度器
- `stop()` 会自动关闭 DispatcherProvider 与 OffsetFlow