package com.chua.ast.support.processor;



import com.chua.ast.support.annotation.DefaultValue;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.Set;

/**
 * {@link DefaultValue} 注解的 AST 处理器
 * <p>
 * 在编译期扫描标注了 {@code @DefaultValue} 注解的方法参数，通过 javac 树 API
 * 在方法体开头插入 空 检查 + 默认值赋值的代码。
 * </p>
 * <p>
 * 转换示例：
 * <pre>{@code
 * // 转换前：public void foo(@DefaultValue("default") String name) { ... }
 * // 转换后：
 * public void foo(String name) {
 *     if (name == null) { name = "default"; }
 *     ...original body...
 * }
 *
 * // 数组类型：public void foo(@DefaultValue({"a","b"}) String[] names) { ... }
 * // 转换后：
 * public void foo(String[] names) {
 *     if (names == null) { names = new String[]{"a", "b"}; }
 *     ...original body...
 * }
 *
 * // 枚举类型：public void foo(@DefaultValue("HIGH") LogLevel level) { ... }
 * // 转换后：
 * public void foo(LogLevel level) {
 *     if (level == null) { level = LogLevel.HIGH; }
 *     ...original body...
 * }
 * }</pre> * }
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0
 */
@SupportedAnnotationTypes("com.chua.ast.support.annotation.DefaultValue")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class DefaultValueAstProcessor extends AbstractProcessor {

    /** 抽象语法树工具 */
    private com.sun.source.util.Trees trees;
    /** 消息器 */
    private Messager messager;
    /** 注解处理环境 */
    private ProcessingEnvironment pe;

    @Override
    /** 初始化 */
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.pe = processingEnv;
        this.messager = processingEnv.getMessager();
        try {
            this.trees = com.sun.source.util.Trees.instance(processingEnv);
        } catch (Exception e) {
            messager.printMessage(Diagnostic.Kind.WARNING,
                    "无法获取 Trees 实例: " + e.getMessage());
        }
    }

    @Override
    /** 处理 */
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver() || trees == null) { return false; }

        for (Element element : roundEnv.getElementsAnnotatedWith(DefaultValue.class)) {
            if (!(element instanceof VariableElement paramElement)) { continue; }
            Element enclosing = paramElement.getEnclosingElement();
            if (!(enclosing instanceof ExecutableElement methodElement)) { continue; }

            com.sun.source.tree.Tree methodTree = trees.getTree(methodElement);
            if (!(methodTree instanceof com.sun.source.tree.MethodTree)) { continue; }

            DefaultValue annotation = paramElement.getAnnotation(DefaultValue.class);
            if (annotation == null) { continue; }

            String[] defaultValues = annotation.value();
            if (defaultValues.length == 0) { continue; }

            String paramName = paramElement.getSimpleName().toString();
            TypeMirror paramType = paramElement.asType();

            try {
                applyAstTransform((com.sun.source.tree.MethodTree) methodTree,
                        paramName, defaultValues, paramType, paramElement);
                messager.printMessage(Diagnostic.Kind.NOTE,
                        "已为参数 " + paramName + " 设置默认值 " + String.join(", ", defaultValues),
                        element);
            } catch (Exception e) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                        "DefaultValue 转换失败：参数 " + paramName + "，错误：" + e.getMessage(), element);
            }
        }
        return false;
    }

    /**
     * 应用 默认值 编译期转换，在方法体开头插入默认值赋值代码
     *
     * @param methodTree 方法树节点
     * @param paramName 参数名称
     * @param defaultValues 默认值数组
     * @param paramType 参数类型
     * @param paramElement 参数元素
     */
    private void applyAstTransform(com.sun.source.tree.MethodTree methodTree,
            String paramName,
            String[] defaultValues,
            TypeMirror paramType,
            VariableElement paramElement) throws Exception {

        var jcMethod = AstUtils.asJcMethodDecl(methodTree);
        if (jcMethod == null || jcMethod.body == null) { return; }

        var maker = AstUtils.getTreeMaker(pe);
        var names = AstUtils.getNames(pe);

        var paramIdent = AstUtils.makeIdent(maker, names, paramName);

        // 根据参数类型选择不同的默认值赋值策略
        if (paramType.getKind() == TypeKind.ARRAY) {
            // 数组类型：if (param == null) { param = new ElementType[]{v1, v2, ...}; }
            var assignStmt = buildArrayAssignment(maker, names, paramIdent, defaultValues, (ArrayType) paramType);
            var nullLit = AstUtils.makeNullLiteral(maker);
            var condition = AstUtils.makeEq(maker, paramIdent, nullLit);
            var thenBlock = maker.Block(0, com.sun.tools.javac.util.List.of(assignStmt));
            var ifStmt = maker.If(condition, thenBlock, null);
            AstUtils.prependToMethodBody(jcMethod, ifStmt);
        } else if (isEnumType(paramType)) {
            // 枚举类型：if (param == null) { param = EnumType.VALUE; }
            var assignStmt = buildEnumAssignment(maker, names, paramIdent, defaultValues[0], paramType);
            var nullLit = AstUtils.makeNullLiteral(maker);
            var condition = AstUtils.makeEq(maker, paramIdent, nullLit);
            var thenBlock = maker.Block(0, com.sun.tools.javac.util.List.of(assignStmt));
            var ifStmt = maker.If(condition, thenBlock, null);
            AstUtils.prependToMethodBody(jcMethod, ifStmt);
        } else if (paramType.getKind().isPrimitive()) {
 // 基本类型无法为 空，直接赋值默认值
            var defaultExpr = AstUtils.makeLiteral(maker, defaultValues[0], paramType);
            var assignStmt = AstUtils.makeAssign(maker, paramIdent, defaultExpr);
            AstUtils.prependToMethodBody(jcMethod, assignStmt);
        } else if (isStringType(paramType)) {
 // 字符串 类型：直接使用字符串值，不走类型转换
            var defaultExpr = maker.Literal(com.sun.tools.javac.code.TypeTag.CLASS, defaultValues[0]);
            var assignStmt = AstUtils.makeAssign(maker, paramIdent, defaultExpr);
            var nullLit = AstUtils.makeNullLiteral(maker);
            var condition = AstUtils.makeEq(maker, paramIdent, nullLit);
            var thenBlock = maker.Block(0, com.sun.tools.javac.util.List.of(assignStmt));
            var ifStmt = maker.If(condition, thenBlock, null);
            AstUtils.prependToMethodBody(jcMethod, ifStmt);
        } else {
            // 其他引用类型：if (param == null) { param = defaultValue; }
            var defaultExpr = AstUtils.makeLiteral(maker, defaultValues[0], paramType);
            var assignStmt = AstUtils.makeAssign(maker, paramIdent, defaultExpr);
            var nullLit = AstUtils.makeNullLiteral(maker);
            var condition = AstUtils.makeEq(maker, paramIdent, nullLit);
            var thenBlock = maker.Block(0, com.sun.tools.javac.util.List.of(assignStmt));
            var ifStmt = maker.If(condition, thenBlock, null);
            AstUtils.prependToMethodBody(jcMethod, ifStmt);
        }
    }

    /**
     * 判断类型是否为字符串类型
     *
     * @param type 类型镜像
     * @return 如果是字符串类型返回 true，否则返回 false
     */
    private boolean isStringType(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) { return false; }
        String typeName = type.toString();
        return "java.lang.String".equals(typeName) || "java.lang.CharSequence".equals(typeName);
    }

    /**
     * 判断类型是否为枚举类型
     *
     * @param type 类型镜像
     * @return 如果是枚举类型返回 true，否则返回 false
     */
    private boolean isEnumType(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) { return false; }
        try {
            return ((javax.lang.model.type.DeclaredType) type).asElement().getKind() == ElementKind.ENUM;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 构建数组赋值语句：{@code param = new ElementType[]{v1, v2, ...};}
     *
     * @param maker 树maker 实例
     * @param names 名称 实例
     * @param paramIdent 参数标识符
     * @param defaultValues 默认值数组
     * @param arrayType 数组类型
     * @return 赋值表达式语句
     */
    private com.sun.tools.javac.tree.JCTree.JCExpressionStatement buildArrayAssignment(com.sun.tools.javac.tree.TreeMaker maker,
            com.sun.tools.javac.util.Names names,
            com.sun.tools.javac.tree.JCTree.JCIdent paramIdent,
            String[] defaultValues,
            ArrayType arrayType) {

        // 获取数组元素类型
        TypeMirror componentType = arrayType.getComponentType();
        String componentTypeName = componentType.toString();

 // 构造数组元素类型表达式：新 组件类型[]{v1, v2, ...}
 // 对于 字符串 和 charsequence 使用简单名称
        com.sun.tools.javac.tree.JCTree.JCExpression componentTypeExpr;
        if ("java.lang.String".equals(componentTypeName) || "java.lang.CharSequence".equals(componentTypeName)) {
            componentTypeExpr = maker.Ident(names.fromString("String"));
        } else {
 // 其他类型使用全限定名构建 选择 表达式
            componentTypeExpr = buildQualifiedIdent(maker, names, componentTypeName);
        }

        // 构造数组元素字面量
        com.sun.tools.javac.tree.JCTree.JCExpression[] elements =
                new com.sun.tools.javac.tree.JCTree.JCExpression[defaultValues.length];
        for (int i = 0; i < defaultValues.length; i++) {
            elements[i] = AstUtils.makeLiteral(maker, defaultValues[i], componentType);
        }

 // 新 组件类型[]{v1, v2, ...}
        var newArrayExpr = maker.NewArray(componentTypeExpr,
                com.sun.tools.javac.util.List.nil(),
                com.sun.tools.javac.util.List.from(elements));

        return AstUtils.makeAssign(maker, paramIdent, newArrayExpr);
    }

    /**
     * 构建枚举赋值语句：{@code param = EnumType.VALUE;}
     * <p>
     * 支持全限定枚举类型名，例如 Java.lang.Thread.状态 会自动解析为 选择(选择(Ident("Java"), "lang"), "Thread") 后 选择 "状态"。
     * </p>
     *
     * @param maker 树maker 实例
     * @param names 名称 实例
     * @param paramIdent 参数标识符
     * @param enumValue 枚举常量名称
     * @param paramType 参数类型
     * @return 赋值表达式语句
     */
    private com.sun.tools.javac.tree.JCTree.JCExpressionStatement buildEnumAssignment(com.sun.tools.javac.tree.TreeMaker maker,
            com.sun.tools.javac.util.Names names,
            com.sun.tools.javac.tree.JCTree.JCIdent paramIdent,
            String enumValue,
            TypeMirror paramType) {

 // 解析全限定枚举类型名，例如 Java.lang.Thread.状态
        String enumTypeQualifiedName = paramType.toString();
        var typeIdent = buildQualifiedIdent(maker, names, enumTypeQualifiedName);
        var valueIdent = maker.Select(typeIdent, names.fromString(enumValue));

        return AstUtils.makeAssign(maker, paramIdent, valueIdent);
    }

    /**
     * 构建全限定名标识符表达式：Java.lang.字符串 将生成为 选择(选择(Ident("Java"), "lang"), "字符串")
     *
     * @param maker 树maker 实例
     * @param names 名称 实例
     * @param qualifiedName 全限定类名
     * @return 全限定名标识符表达式
     */
    private com.sun.tools.javac.tree.JCTree.JCExpression buildQualifiedIdent(com.sun.tools.javac.tree.TreeMaker maker,
            com.sun.tools.javac.util.Names names,
            String qualifiedName) {

        String[] parts = qualifiedName.split("\\.");
        if (parts.length == 0) { return maker.Ident(names.fromString(qualifiedName)); }

        com.sun.tools.javac.tree.JCTree.JCExpression expr = maker.Ident(names.fromString(parts[0]));
        for (int i = 1; i < parts.length; i++) {
            expr = maker.Select(expr, names.fromString(parts[i]));
        }
        return expr;
    }
}
