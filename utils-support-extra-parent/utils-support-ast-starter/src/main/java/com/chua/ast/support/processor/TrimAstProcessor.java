package com.chua.ast.support.processor;

import com.chua.ast.support.annotation.Trim;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.Set;

/**
 * {@link Trim} 注解的 AST 处理器
 * <p>
 * 在编译期扫描标注了 {@code @Trim} 注解的 字符串 类型方法参数，
 * 在方法体开头插入 {@code param = param.trim();} 赋值语句。
 * </p>
 *
 * @author CH
 * @since 4.0.0
 */
@SupportedAnnotationTypes("com.chua.ast.support.annotation.Trim")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class TrimAstProcessor extends AbstractProcessor {

    /**
     * 抽象语法树工具
    */
    private com.sun.source.util.Trees trees;
    /**
     * 消息器
    */
    private Messager messager;
    /**
     * 注解处理环境
    */
    private ProcessingEnvironment pe;

    @Override
    /**
     * 初始化
    */
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
    /**
     * 处理
    */
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver() || trees == null) { return false; }

        for (Element element : roundEnv.getElementsAnnotatedWith(Trim.class)) {
            if (!(element instanceof VariableElement paramElement)) { continue; }

            Element enclosing = paramElement.getEnclosingElement();
            if (!(enclosing instanceof ExecutableElement methodElement)) { continue; }

            TypeMirror paramType = paramElement.asType();
            if (!isStringType(paramType)) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                        "@Trim 仅支持 String 类型参数：" + paramElement.getSimpleName(),
                        element);
                continue;
            }

            String paramName = paramElement.getSimpleName().toString();

            com.sun.source.tree.Tree methodTree = trees.getTree(methodElement);
            if (!(methodTree instanceof com.sun.source.tree.MethodTree)) { continue; }

            try {
                applyTrimTransform((com.sun.source.tree.MethodTree) methodTree, paramName);
                messager.printMessage(Diagnostic.Kind.NOTE,
                        "已为参数 " + paramName + " 应用 @Trim 转换", element);
            } catch (Exception e) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                        "@Trim 转换失败：参数 " + paramName + "，错误：" + e.getMessage(), element);
            }
        }
        return false;
    }

    /**
     * 是否字符串类型
     *
     * @param type 类型
     * @return 是否字符串类型的结果
     */
    private boolean isStringType(TypeMirror type) {
        if (type.getKind() != javax.lang.model.type.TypeKind.DECLARED) { return false; }
        String typeName = type.toString();
        return "java.lang.String".equals(typeName) || "java.lang.CharSequence".equals(typeName);
    }

    /**
     * 应用去空格转换
     * @param methodTree 方法树
     * @param paramName 参数名称
     */
    private void applyTrimTransform(com.sun.source.tree.MethodTree methodTree,
            String paramName) throws Exception {

        var jcMethod = AstUtils.asJcMethodDecl(methodTree);
        if (jcMethod == null || jcMethod.body == null) { return; }

        var maker = AstUtils.getTreeMaker(pe);
        var names = AstUtils.getNames(pe);

        var paramIdent = AstUtils.makeIdent(maker, names, paramName);
        var trimCall = maker.Apply(com.sun.tools.javac.util.List.nil(),
                maker.Select(paramIdent, names.fromString("trim")),
                com.sun.tools.javac.util.List.nil());

        var assignStmt = AstUtils.makeAssign(maker, paramIdent, trimCall);
        AstUtils.prependToMethodBody(jcMethod, assignStmt);
    }
}
