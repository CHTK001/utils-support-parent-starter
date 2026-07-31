package com.chua.ast.support.processor;

import com.chua.ast.support.annotation.CleanNull;
import com.chua.ast.support.internal.AbstractAstProcessor;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Names;

import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeKind;
import java.util.Set;

/**
 * {@link CleanNull} 注解的 AST 处理器
 *
 * @author CH
 * @since 4.0.0.42
 */
@SupportedAnnotationTypes("com.chua.ast.support.annotation.CleanNull")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class CleanNullAstProcessor extends AbstractAstProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver() || !isTreeApiAvailable()) {
            return false;
        }

        for (Element element : roundEnv.getElementsAnnotatedWith(CleanNull.class)) {
            if (element.getKind() != ElementKind.PARAMETER) {
                continue;
            }
            processParameter((VariableElement) element);
        }

        return false;
    }

    private void processParameter(VariableElement paramElement) {
        Element enclosing = paramElement.getEnclosingElement();
        if (!(enclosing instanceof ExecutableElement methodElement)) {
            return;
        }

        com.sun.source.tree.Tree tree = trees.getTree(methodElement);
        if (!(tree instanceof JCTree.JCMethodDecl jcMethod)) {
            return;
        }

        JCTree.JCBlock body = jcMethod.body;
        if (body == null) {
            return;
        }

        TreeMaker maker = getTreeMaker();
        if (maker == null) {
            return;
        }
        Names names = getNames(maker);
        if (names == null) {
            return;
        }

        CleanNull cleanNull = paramElement.getAnnotation(CleanNull.class);
        if (cleanNull == null) {
            return;
        }
        String[] keywords = cleanNull.value();
        if (keywords == null || keywords.length == 0) {
            return;
        }

        String paramName = paramElement.getSimpleName().toString();
        boolean isString = isStringType(paramElement);

        JCTree.JCStatement cleanStatement = buildCleanStatement(maker, names, paramName, keywords, isString);
        if (cleanStatement != null) {
            prependStatement(body, cleanStatement);
            note("已为参数 " + paramName + " 插入 CleanNull 清洗", paramElement);
        }
    }

    private boolean isStringType(VariableElement element) {
        TypeMirror type = element.asType();
        if (type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        String typeName = type.toString();
        return "java.lang.String".equals(typeName) || "java.lang.CharSequence".equals(typeName);
    }

    private JCTree.JCStatement buildCleanStatement(TreeMaker maker, Names names,
                                                     String paramName, String[] keywords, boolean isString) {
        JCTree.JCIdent paramIdent = maker.Ident(names.fromString(paramName));

        // 只有引用类型需要 null 检查，基本类型不需要
        JCTree.JCExpression condition = null;
        for (String keyword : keywords) {
            if (keyword == null || keyword.isEmpty()) {
                continue;
            }
            // 对于基本类型，直接比较字面量
            JCTree.JCExpression keywordExpr;
            if (isString) {
                keywordExpr = maker.Literal(com.sun.tools.javac.code.TypeTag.CLASS, keyword);
            } else {
                // 基本类型：尝试解析为数值
                try {
                    int intValue = Integer.parseInt(keyword);
                    keywordExpr = maker.Literal(com.sun.tools.javac.code.TypeTag.INT, intValue);
                } catch (NumberFormatException e) {
                    continue; // 无法解析的关键词跳过
                }
            }

            JCTree.JCBinary equalsCheck = maker.Binary(JCTree.Tag.EQ, paramIdent, keywordExpr);

            if (condition == null) {
                condition = equalsCheck;
            } else {
                condition = maker.Binary(JCTree.Tag.OR, condition, equalsCheck);
            }
        }

        if (condition == null) {
            return null;
        }

        JCTree.JCExpression assignValue;
        if (isString) {
            assignValue = maker.Literal(com.sun.tools.javac.code.TypeTag.BOT, null);
        } else {
            assignValue = maker.Literal(com.sun.tools.javac.code.TypeTag.INT, 0);
        }

        JCTree.JCAssign assign = maker.Assign(paramIdent, assignValue);
        JCTree.JCExpressionStatement assignStmt = maker.Exec(assign);

        return maker.If(condition, assignStmt, null);
    }

    private void prependStatement(JCTree.JCBlock body, JCTree.JCStatement stmt) {
        body.stats = body.stats.prepend(stmt);
    }
}
