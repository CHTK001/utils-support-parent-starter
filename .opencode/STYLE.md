# Code Style Rules (Applied Globally)

## 注释规则

1. **全部中文注释** — 所有 Javadoc、行内注释必须使用中文
2. **属性多行注释** — 每个 `private/protected` 字段使用 `/** 说明 */` 多行注释
3. **注释在代码上方** — 单行注释必须在代码上面，不允许写在行尾

## 类注释

4. **@author CH** — 所有类、内部类、接口添加 `@author CH @since x.x.x`

## 控制流

5. **大括号必须** — `if/else/while/for/catch` 即使只有一行也必须加 `{}`

## 代码格式

6. **不压缩一行** — 方法声明、方法体必须展开多行，不允许 `public type method() { return x; }`

## 简化

7. **使用 lombok 和 record** — `@Data/@Slf4j/@Builder` 代替手动 getter/setter/logger
8. **package 上方** — package 声明上方不要有注释
9. **record 注释位置** — record 的注释写在类上，不写在字段上

## 编译

10. **修复问题** — 编译错误必须修复，不允许留编译不过的代码
