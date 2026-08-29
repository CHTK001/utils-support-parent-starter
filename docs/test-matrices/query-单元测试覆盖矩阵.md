# HttpDatalakeQueryEngineExample 单元测试覆盖矩阵

## 版本信息
- 类名：HttpDatalakeQueryEngineExample
- 模块：utils-support-example-starter
- 作者：CH
- 更新日期：2026-07-29

## 能力点覆盖矩阵

| 能力 ID | 测试方法           | 前置条件              | 断言                                  | 通过条件                          |
|:-------|:------------------|:---------------------|:-------------------------------------|:----------------------------------|
| QE-01  | testConstruct     | 默认 baseUrl          | engine 非空且 defaultName == "datalake" | name 匹配                         |

## 执行记录

| 日期       | type     | 结果     | 备注                                |
|:-----------|:---------|:---------|:------------------------------------|
| 2026-07-29 | construct| 预期通过 | 仅本地构造, 不实际发请求              |

## 注意事项

- 默认 baseUrl 为 `http://localhost:8700`，构造时不连服务端
- `close()` 关闭底层 HttpClient
- Lambda 链式操作（query/update/delete）不支持, 需用 `getExecutor()` 走 SQL
- 与 datalake-starter 的 ApiServer 通过 HTTP /query 路由交互