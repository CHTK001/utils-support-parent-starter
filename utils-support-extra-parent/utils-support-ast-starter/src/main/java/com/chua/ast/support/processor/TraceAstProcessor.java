package com.chua.ast.support.processor;

import com.chua.ast.support.annotation.Trace;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.List;
import java.util.Set;

/**
 * {@link Trace} 注解的 AST 处理器
 *
 * @author CH
 * @since 4.0.0
 */
@SupportedAnnotationTypes("com.chua.ast.support.annotation.Trace")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class TraceAstProcessor extends AbstractProcessor {

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
            messager.printMessage(Diagnostic.Kind.WARNING, "无法获取 Trees 实例: " + e.getMessage());
        }
    }

    @Override
    /** 处理 */
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver() || trees == null) { return false; }

        for (Element element : roundEnv.getElementsAnnotatedWith(Trace.class)) {
            if (!(element instanceof ExecutableElement methodElement)) { continue; }
            if (methodElement.getModifiers().contains(Modifier.ABSTRACT)) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                        "抽象方法不支持 @Trace：" + methodElement.getSimpleName(), element);
                continue;
            }

            com.sun.source.tree.Tree methodTree = trees.getTree(methodElement);
            if (!(methodTree instanceof com.sun.source.tree.MethodTree)) { continue; }

            Trace trace = methodElement.getAnnotation(Trace.class);
            String methodName = methodElement.getSimpleName().toString();
            String className = getSimpleClassName(methodElement);
            String packageName = getPackageName(methodElement);

            try {
                applyTraceTransform((com.sun.source.tree.MethodTree) methodTree,
                        className, packageName, methodName, methodElement, trace);
                messager.printMessage(Diagnostic.Kind.NOTE,
                        "已为方法 " + methodName + " 应用 @Trace 转换", element);
            } catch (Exception e) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                        "@Trace 转换失败：" + methodName + "，错误：" + e.getMessage(), element);
            }
        }
        return false;
    }

    /**
     * 应用追踪转换
     * @param methodTree 方法树
     * @param className 类名称
     * @param packageName 包名称
     * @param methodName 方法名称
     * @param methodElement 方法element
     * @param trace 追踪
     */
    private void applyTraceTransform(com.sun.source.tree.MethodTree methodTree,
            String className, String packageName, String methodName,
            ExecutableElement methodElement, Trace trace) throws Exception {

        var jcMethod = AstUtils.asJcMethodDecl(methodTree);
        if (jcMethod == null || jcMethod.body == null) { return; }

        var maker = AstUtils.getTreeMaker(pe);
        var names = AstUtils.getNames(pe);
        var originalStats = jcMethod.body.stats;

        // 1. setMaxDepth(depth)
        com.sun.tools.javac.tree.JCTree.JCStatement setDepthStmt = null;
        if (trace.depth() > 0) {
            setDepthStmt = buildSetMaxDepthStmt(maker, names, trace.depth());
        }

        // 2. push(className, methodName, args) 或 pushWithArgs(...)
        String argsExpr = trace.includeArgs() ? buildArgsExpression(methodElement) : null;
        var pushStmt = buildPushStmt(maker, names, className, packageName, methodName, argsExpr);

        // 3. catch(Throwable t) { TraceContext.catchException(t); throw t; } finally { TraceContext.pop(); }
        var catchVar = maker.VarDef(maker.Modifiers(0), names.fromString("t"),
                maker.Ident(names.fromString("Throwable")), null);
        var catchBody = buildCall(maker, names, "com.chua.ast.support.trace.TraceContext", "catchException");
 // 卡扣异常 需要参数，用 maker.Apply
        var traceCtxClass = buildQualifiedName(maker, names, "com.chua.ast.support.trace.TraceContext");
        var catchExSelect = maker.Select(traceCtxClass, names.fromString("catchException"));
        var tIdent = maker.Ident(names.fromString("t"));
        var catchCall = maker.Apply(com.sun.tools.javac.util.List.nil(), catchExSelect,
                com.sun.tools.javac.util.List.of(tIdent));
        var rethrow = maker.Throw(tIdent);
        var catchBlock = maker.Block(0, com.sun.tools.javac.util.List.of(
                maker.Exec(catchCall), rethrow));
        var catcher = maker.Catch(catchVar, catchBlock);

        var popStmt = buildCall(maker, names, "com.chua.ast.support.trace.TraceContext", "pop");
        var finallyBlock = maker.Block(0, com.sun.tools.javac.util.List.of(popStmt));
        var tryBody = maker.Block(0, originalStats);
        var tryCatchFinally = maker.Try(tryBody,
                com.sun.tools.javac.util.List.of(catcher), finallyBlock);

        // 4. 组装
        java.util.List<com.sun.tools.javac.tree.JCTree.JCStatement> body = new java.util.ArrayList<>();
        if (setDepthStmt != null) { body.add(setDepthStmt); }
        body.add(pushStmt);
        body.add(tryCatchFinally);

        jcMethod.body = maker.Block(0, com.sun.tools.javac.util.List.from(body));
    }

    /**
     * 构建参数字符串：名称=" + 名称 + ", age=" + age
     * @param methodElement 方法element
     * @return 构建参数expression的结果
     */
    private String buildArgsExpression(ExecutableElement methodElement) {
        List<? extends VariableElement> params = methodElement.getParameters();
        if (params.isEmpty()) { return null; }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) { sb.append(" + \", \" + "); }
            String paramName = params.get(i).getSimpleName().toString();
            TypeMirror paramType = params.get(i).asType();

            if (isStringType(paramType)) {
                sb.append("\"").append(paramName).append("=\\\"\" + ").append(paramName).append(" + \"\\\"");
            } else {
                sb.append("\"").append(paramName).append("=\" + ").append(paramName);
            }
        }
        return sb.toString();
    }

    /**
     * 是否字符串类型
     *
     * @param type 类型
     * @return 是否字符串类型的结果
     */
    private boolean isStringType(TypeMirror type) {
        String name = type.toString();
        return "java.lang.String".equals(name) || "java.lang.CharSequence".equals(name);
    }

    // ==================== AST 构建 ====================

    /**
     * 追踪上下文.设置最大深度(N)
     */
    private com.sun.tools.javac.tree.JCTree.JCExpressionStatement buildSetMaxDepthStmt(
            com.sun.tools.javac.tree.TreeMaker maker, com.sun.tools.javac.util.Names names, int depth) {

        var traceCtxClass = buildQualifiedName(maker, names, "com.chua.ast.support.trace.TraceContext");
        var setMaxDepthSelect = maker.Select(traceCtxClass, names.fromString("setMaxDepth"));
        var depthLiteral = maker.Literal(com.sun.tools.javac.code.TypeTag.INT, depth);
        var call = maker.Apply(com.sun.tools.javac.util.List.nil(),
                setMaxDepthSelect, com.sun.tools.javac.util.List.of(depthLiteral));
        return maker.Exec(call);
    }

    /**
     * 追踪上下文.push(类名称, 包名称, 方法名称) 或 追踪上下文.pushwith参数(...)
     */
    private com.sun.tools.javac.tree.JCTree.JCExpressionStatement buildPushStmt(
            com.sun.tools.javac.tree.TreeMaker maker, com.sun.tools.javac.util.Names names,
            String className, String packageName, String methodName, String argsExpr) {

        var traceCtxClass = buildQualifiedName(maker, names, "com.chua.ast.support.trace.TraceContext");
        var classLiteral = maker.Literal(com.sun.tools.javac.code.TypeTag.CLASS, className);
        var pkgLiteral = maker.Literal(com.sun.tools.javac.code.TypeTag.CLASS, packageName);
        var methodLiteral = maker.Literal(com.sun.tools.javac.code.TypeTag.CLASS, methodName);

        if (argsExpr != null) {
            var pushSelect = maker.Select(traceCtxClass, names.fromString("pushWithArgs"));
            var argsCall = parseArgsExpression(maker, names, argsExpr);
            var call = maker.Apply(com.sun.tools.javac.util.List.nil(), pushSelect,
                    com.sun.tools.javac.util.List.of(classLiteral, pkgLiteral, methodLiteral, argsCall));
            return maker.Exec(call);
        } else {
            var pushSelect = maker.Select(traceCtxClass, names.fromString("push"));
            var call = maker.Apply(com.sun.tools.javac.util.List.nil(), pushSelect,
                    com.sun.tools.javac.util.List.of(classLiteral, pkgLiteral, methodLiteral));
            return maker.Exec(call);
        }
    }

    /**
     * 解析参数表达式字符串，构建 AST 节点
     */
    private com.sun.tools.javac.tree.JCTree.JCExpression parseArgsExpression(
            com.sun.tools.javac.tree.TreeMaker maker, com.sun.tools.javac.util.Names names, String expr) {

        String[] parts = expr.split(" \\+ ");
        if (parts.length == 0) { return maker.Literal(com.sun.tools.javac.code.TypeTag.CLASS, expr); }

        com.sun.tools.javac.tree.JCTree.JCExpression result = null;
        for (String part : parts) {
            part = part.trim();
            com.sun.tools.javac.tree.JCTree.JCExpression term;

            if (part.startsWith("\"") && part.endsWith("\"")) {
                String value = part.substring(1, part.length() - 1);
                term = maker.Literal(com.sun.tools.javac.code.TypeTag.CLASS, value);
            } else {
                term = maker.Ident(names.fromString(part));
            }

            if (result == null) {
                result = term;
            } else {
                result = maker.Binary(com.sun.tools.javac.tree.JCTree.Tag.PLUS, result, term);
            }
        }
        return result;
    }

    /**
     * 构建调用
     * @param maker maker
     * @param names 名称
     * @param className 类名称
     * @param methodName 方法名称
     * @param names 名称
     * @param className 类名称
     * @param select 选择
     * @param maker maker
     * @param names 名称
     * @param qualifiedName qualified名称
     * @param methodElement 方法element
     * @param typeElement 类型element
     * @param methodElement 方法element
     * @param TypeElement 类型element
     * @param typeElement 类型element
     */
    private com.sun.tools.javac.tree.JCTree.JCExpressionStatement buildCall(
            com.sun.tools.javac.tree.TreeMaker maker, com.sun.tools.javac.util.Names names,
            String className, String methodName) {

        var clazz = buildQualifiedName(maker, names, className);
        var select = maker.Select(clazz, names.fromString(methodName));
        var call = maker.Apply(com.sun.tools.javac.util.List.nil(),
                select, com.sun.tools.javac.util.List.nil());
        return maker.Exec(call);
    }

    /**
     * 构建qualified名称
     * @param maker maker
     * @param names 名称
     * @param qualifiedName qualified名称
     * @param methodElement 方法element
     * @param typeElement 类型element
     * @param methodElement 方法element
     * @param TypeElement 类型element
     * @param typeElement 类型element
     */
    private com.sun.tools.javac.tree.JCTree.JCExpression buildQualifiedName(
            com.sun.tools.javac.tree.TreeMaker maker, com.sun.tools.javac.util.Names names, String qualifiedName) {

        String[] parts = qualifiedName.split("\\.");
        com.sun.tools.javac.tree.JCTree.JCExpression expr = maker.Ident(names.fromString(parts[0]));
        for (int i = 1; i < parts.length; i++) {
            expr = maker.Select(expr, names.fromString(parts[i]));
        }
        return expr;
    }

    /**
     * 获取简单类名称
     *
     * @param methodElement 方法element
     * @return 获取简单类名称的结果
     */
    private String getSimpleClassName(ExecutableElement methodElement) {
        Element enclosing = methodElement.getEnclosingElement();
        if (enclosing instanceof TypeElement typeElement) {
            return typeElement.getSimpleName().toString();
        }
        return "Unknown";
    }

    /**
     * 获取包名称
     *
     * @param methodElement 方法element
     * @return 获取包名称的结果
     */
    private String getPackageName(ExecutableElement methodElement) {
        Element enclosing = methodElement.getEnclosingElement();
        while (enclosing != null && !(enclosing instanceof TypeElement)) {
            enclosing = enclosing.getEnclosingElement();
        }
        if (enclosing instanceof TypeElement typeElement) {
            PackageElement pkg = processingEnv.getElementUtils().getPackageOf(typeElement);
            return pkg.getQualifiedName().toString();
        }
        return "";
    }
}
