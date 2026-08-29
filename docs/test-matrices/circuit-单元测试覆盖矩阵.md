# CircuitBreakerExample 单元测试覆盖矩阵

## 版本信息
- 类名：CircuitBreakerExample
- 模块：utils-support-example-starter
- 作者：CH
- 更新日期：2026-07-28

## SPI 覆盖矩阵

| 表达式类型 | 解析器实现 | 场景 | 状态 |
|:----------|:-----------|:----|:----|
| expr | DefaultExpressionParser | 业务规则拦截 | 通过 |
| expr | DefaultExpressionParser | 动态路由 | 通过 |
| expr | DefaultExpressionParser | 特性开关 | 通过 |
| expr | DefaultExpressionParser | AND 短路 | 通过 |
| expr | DefaultExpressionParser | OR 短路 | 通过 |
| expr | DefaultExpressionParser | NOT 取反 | 通过 |
| expr | DefaultExpressionParser | 函数调用 | 通过 |
| sql | SqlExpressionParser | SQL WHERE 条件 | 通过 |
| lucene | LuceneExpressionParser | Lucene 查询 | 通过 |
| cypher | CypherExpressionParser | 图遍历条件 | 通过 |

## 测试场景覆盖矩阵

| 场景ID | 测试方法 | 表达式 | 断言 | 通过条件 |
|:-------|:--------|:-------|:-----|:--------|
| TC-01 | caseBusinessRule | age > 18 AND status == 'active' | true | ctx.age=25, status=active |
| TC-02 | caseRouting | region == 'cn-north' AND version >= '2.0' | true | ctx.region=cn-north, version=2.5 |
| TC-03 | caseFeatureFlag | feature.newUi == true AND userId IN (1001,1002,1003) | true | ctx.userId=1002 |
| TC-04 | caseAndShortCircuit | counter == 0 AND expensiveCheck == true | false | AND 短路，左 false 直接断路 |
| TC-05 | caseOrShortCircuit | isAdmin == true OR expensiveCheck == true | true | OR 短路，左 true 直接通过 |
| TC-06 | caseNot | NOT isBlocked | true | ctx.isBlocked=false |
| TC-07 | caseFunction | UPPER(role) == 'ADMIN' | true | ctx.role=admin |
| TC-08 | caseSqlExpression | status = 'active' AND score > 80 | true | sql 解析器 |
| TC-09 | caseLuceneExpression | status:active AND score:[80 TO *] | true | lucene 解析器 |
| TC-10 | caseCypherExpression | (age > 18 AND status = 'active') | true | cypher 解析器 |

## 短路优化验证

| 场景 | 表达式 | 期望 | 验证要点 |
|:-----|:-------|:-----|:--------|
| AND 短路 | counter == 0 AND expensiveCheck == true | false | 左侧 false 不评估右侧 |
| OR 短路 | isAdmin == true OR expensiveCheck == true | true | 左侧 true 不评估右侧 |
| NOT 反转 | NOT isBlocked | 取反 | 子节点结果反转 |

## 执行记录

| 日期 | 命令 | 结果 | 备注 |
|:-----|:-----|:-----|:-----|
| 2026-07-28 | java CircuitBreakerExample --test | 通过 | 全部 10 个场景 |
| 2026-07-28 | java CircuitBreakerExample -c rule | 通过 | 单场景演示 |