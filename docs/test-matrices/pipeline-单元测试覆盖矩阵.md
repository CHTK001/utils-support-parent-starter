# PipelineEngineExample 单元测试覆盖矩阵

## 版本信息
- 类名：PipelineEngineExample
- 模块：utils-support-example-starter
- 作者：CH
- 更新日期：2026-07-29

## 能力点覆盖矩阵

| 能力 ID | 测试方法             | 前置条件                  | 断言                                  | 通过条件                          |
|:-------|:--------------------|:-------------------------|:-------------------------------------|:----------------------------------|
| PE-01  | testBasicExecute    | memory dispatcher 启动   | engine.execute 不抛异常              | 无异常退出                        |
| PE-02  | testDslExecute       | DSL 中含 sink type=log    | PipelineManager.savePipeline + execute | 无异常退出                        |

## 执行记录

| 日期       | type     | 结果     | 备注                                       |
|:-----------|:---------|:---------|:-------------------------------------------|
| 2026-07-29 | all      | 预期通过 | 内存 dispatcher, 不依赖外部 Chronicle        |

## 注意事项

- 示例使用 `MemoryDispatcherProvider`（来自 datasource-starter）
- 真实部署时 `DatalakeServerBuilder` 会自动配置 Chronicle DispatcherProvider
- DSL 通过 `Json.toJson(PipelineConfig)` 序列化后由 `PipelineManager` 持久化