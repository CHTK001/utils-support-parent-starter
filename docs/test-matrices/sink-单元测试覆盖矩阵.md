# DataSinkExample 单元测试覆盖矩阵

## 版本信息
- 类名：DataSinkExample
- 模块：utils-support-example-starter
- 作者：CH
- 更新日期：2026-07-29

## 能力点覆盖矩阵

| 能力 ID | 测试方法           | 前置条件                  | 断言                                  | 通过条件                          |
|:-------|:------------------|:-------------------------|:-------------------------------------|:----------------------------------|
| DS-01  | testAccessSinks   | SPI 注册 Log/Statistic/RealTime | AccessSink 数量 >= 3               | count >= 3                        |
| DS-02  | testStoreSinks    | SPI 注册 JdbcSink         | 存在 type="jdbc" 的 DataSink        | anyMatch == true                   |
| DS-03  | testWrite         | 全部 sink 启动完成         | 给所有 sink 写入 envelope 全部返回 true | allOk == true                      |
| DS-04  | sampleData        | 默认                       | 返回的 Map 包含 id/name/ts 字段        | fields present                     |

## 执行记录

| 日期       | type     | 结果     | 备注                              |
|:-----------|:---------|:---------|:----------------------------------|
| 2026-07-29 | all      | 预期通过 | 通过 SPI 加载 datalake-sink-starter |

## 注意事项

- 依赖 `META-INF/extensions/com.chua.datalake.support.spi.sink.DataSink` 文件
- AccessSink 是 DataSink 的标记接口，实现类为 LogSink/StatisticSink/RealTimeSink
- JdbcSink 是存储型，getDataSource() 默认为 null，可二次扩展