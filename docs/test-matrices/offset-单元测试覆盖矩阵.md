# OffsetFlowExample 单元测试覆盖矩阵

## 版本信息
- 类名：OffsetFlowExample
- 模块：utils-support-example-starter
- 作者：CH
- 更新日期：2026-07-29

## 能力点覆盖矩阵

| 能力 ID | 测试方法      | 前置条件              | 断言                                  | 通过条件                          |
|:-------|:-------------|:---------------------|:-------------------------------------|:----------------------------------|
| OF-01  | testAdvance  | 默认 file provider    | advance 返回 1/2/3 连续递增           | v1=1, v2=2, v3=3                  |
| OF-02  | testReset    | 默认 file provider    | advance 两次后 reset=0，current=0    | current == 0                      |
| OF-03  | testPersist  | 默认 file provider    | 实例1 advance 后关闭，实例2 读取 = 3  | reload value == 3                  |
| OF-04  | testSpiLoad  | 默认 file provider    | ServiceProvider 解析 "file" provider | store != null                     |
| OF-05  | testTruncate | 默认 file provider    | truncate 后 advance = 1（重置）       | v == 1                            |

## 执行记录

| 日期       | type     | 结果     | 备注                       |
|:-----------|:---------|:---------|:---------------------------|
| 2026-07-29 | all      | 预期通过 | 全部基于文件持久化 tmp 目录  |

## 注意事项

- 临时目录基于 `java.io.tmpdir` + 时间戳，运行后需清理
- file provider 通过 META-INF/extensions SPI 自动注册
- 后续 Redis provider 通过 `redis-starter` 实现替换