package com.chua.ast.support.processor;

import com.chua.ast.support.annotation.PadTruncate;
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
 * {@link PadTruncate} 注解的 AST 处理器
 *
 * @since 4.0.0.42
 */
@SupportedAnnotationTypes("com.chua.ast.support.annotation.PadTruncate")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class PadTruncateAstProcessor extends AbstractAstProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver() || !isTreeApiAvailable()) {
            return false;
        }

        for (Element element : roundEnv.getElementsAnnotatedWith(PadTruncate.class)) {
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

        TypeMirror paramType = paramElement.asType();
        if (!isStringType(paramType)) {
            note("@PadTruncate 仅支持 String 类型参数：" + paramElement.getSimpleName(), paramElement);
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

        PadTruncate padTruncate = paramElement.getAnnotation(PadTruncate.class);
        if (padTruncate == null) {
            return;
        }

        String paramName = paramElement.getSimpleName().toString();
        int minLength = padTruncate.start();
        int maxLength = padTruncate.end();

        JCTree.JCStatement padStatement = buildPadTruncateStatement(maker, names, paramName, minLength, maxLength);
        if (padStatement != null) {
            prependStatement(body, padStatement);
            note("已为参数 " + paramName + " 插入 PadTruncate 转换", paramElement);
        }
    }

    private boolean isStringType(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        String typeName = type.toString();
        return "java.lang.String".equals(typeName) || "java.lang.CharSequence".equals(typeName);
    }

    private JCTree.JCStatement buildPadTruncateStatement(TreeMaker maker, Names names,
                                                          String paramName, int minLength, int maxLength) {
        JCTree.JCIdent paramIdent = maker.Ident(names.fromString(paramName));

        JCTree.JCLiteral nullLiteral = maker.Literal(com.sun.tools.javac.code.TypeTag.BOT, null);
        JCTree.JCBinary notNullCheck = maker.Binary(JCTree.Tag.NE, paramIdent, nullLiteral);

        com.sun.tools.javac.util.ListBuffer<JCTree.JCStatement> bodyStats = new com.sun.tools.javac.util.ListBuffer<>();

        if (maxLength < Integer.MAX_VALUE) {
            JCTree.JCExpression lengthCall = maker.Apply(
                    com.sun.tools.javac.util.List.nil(),
                    maker.Select(paramIdent, names.fromString("length")),
                    com.sun.tools.javac.util.List.nil());

            JCTree.JCLiteral maxLit = maker.Literal(com.sun.tools.javac.code.TypeTag.INT, maxLength);
            JCTree.JCBinary exceedCheck = maker.Binary(JCTree.Tag.GT, lengthCall, maxLit);

            JCTree.JCExpression substringCall = maker.Apply(
                    com.sun.tools.javac.util.List.nil(),
                    maker.Select(paramIdent, names.fromString("substring")),
                    com.sun.tools.javac.util.List.of(maker.Literal(com.sun.tools.javac.code.TypeTag.INT, 0), maxLit));

            JCTree.JCAssign assign = maker.Assign(paramIdent, substringCall);
            JCTree.JCExpressionStatement assignStmt = maker.Exec(assign);

            bodyStats.append(maker.If(exceedCheck, assignStmt, null));
        }

        if (minLength > 0) {
            JCTree.JCExpression lengthCall = maker.Apply(
                    com.sun.tools.javac.util.List.nil(),
                    maker.Select(paramIdent, names.fromString("length")),
                    com.sun.tools.javac.util.List.nil());

            JCTree.JCLiteral minLit = maker.Literal(com.sun.tools.javac.code.TypeTag.INT, minLength);
            JCTree.JCBinary lackCheck = maker.Binary(JCTree.Tag.LT, lengthCall, minLit);

            JCTree.JCExpression whileLengthCall = maker.Apply(
                    com.sun.tools.javac.util.List.nil(),
                    maker.Select(paramIdent, names.fromString("length")),
                    com.sun.tools.javac.util.List.nil());
            JCTree.JCBinary whileCondition = maker.Binary(JCTree.Tag.LT, whileLengthCall, minLit);

            JCTree.JCLiteral spaceLit = maker.Literal(com.sun.tools.javac.code.TypeTag.CLASS, " ");
            JCTree.JCBinary concatExpr = maker.Binary(JCTree.Tag.PLUS, spaceLit, paramIdent);
            JCTree.JCAssign whileAssign = maker.Assign(paramIdent, concatExpr);
            JCTree.JCExpressionStatement whileAssignStmt = maker.Exec(whileAssign);

            JCTree.JCWhileLoop whileLoop = maker.WhileLoop(whileCondition, whileAssignStmt);

            bodyStats.append(maker.If(lackCheck, whileLoop, null));
        }

        if (bodyStats.isEmpty()) {
            return null;
        }

        JCTree.JCBlock ifBody = maker.Block(0, bodyStats.toList());
        return maker.If(notNullCheck, ifBody, null);
    }

    private void prependStatement(JCTree.JCBlock body, JCTree.JCStatement stmt) {
        body.stats = body.stats.prepend(stmt);
    }
}
