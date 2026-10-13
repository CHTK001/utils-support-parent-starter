package com.chua.pdf.support.export;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.export.ExportException;
import com.chua.common.support.export.ExportFormats;
import com.chua.common.support.export.TemplateContext;
import com.chua.common.support.export.spi.TemplateFiller;
import com.chua.common.support.export.support.Placeholders;
import com.chua.common.support.spi.annotations.Spi;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDNonTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PDF AcroForm 模板填充器 —— 接口二在 PDF 侧的实现。
 *
 * <p>PDF 的"模板替换"以 <b>表单域（AcroForm）</b> 为准：模板中表单域的全名即标签名，
 * 由 {@code variables} 按名填充。支持：</p>
 * <ul>
 *     <li>文本域 {@link PDTextField}：写入文本</li>
 *     <li>复选框 {@link PDCheckBox}：{@code true}/{@code "Yes"}/{@code "On"}/{@code "1"} → 勾选</li>
 *     <li>单选框 {@link PDRadioButton}：取值匹配选项名称</li>
 *     <li>下拉框 {@link PDComboBox}：取值匹配选项，无匹配则追加后选中</li>
 * </ul>
 *
 * <p>变量查找顺序：表单域全名 → 域短名 → 变量表（忽略大小写）。
 * 文本域的默认值中也可写占位符，会被自动替换，语法与 Excel / Word 侧一致
 * （{@code {{key}}}、{@code {{a.b.c}}} 嵌套路径、{@code {{items.0}}} 集合下标）。</p>
 *
 * <p><b>没有表单域的普通 PDF 无法作为模板</b>——此时会抛出
 * {@link ExportException} 并提示先制作 AcroForm 模板。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = {"pdfbox", "pdf-form"}, order = 0)
public class PdfAcroFormTemplateFiller implements TemplateFiller {

    @Override
    /**
     * 支持的格式
     * @return 格式 对象
     */
    public Set<String> formats() {
        return Set.of(ExportFormats.PDF);
    }

    @Override
    /**
     * 引擎名
     * @return 引擎名 对象
     */
    public String engine() {
        return "pdfbox";
    }

    @Override
    /**
     * 填充模板
     * @param context  模板上下文
     * @param template 模板输入流
     * @param out      输出流
     * @throws IOException 读写异常
     */
    public void fill(TemplateContext context, InputStream template, OutputStream out) throws IOException {
        try (RandomAccessReadBuffer buffer = new RandomAccessReadBuffer(template);
             PDDocument document = Loader.loadPDF(buffer)) {
            List<PDField> fields = acroForm(document);
            if (fields.isEmpty()) {
                throw new ExportException("PDF 模板不含 AcroForm 表单域，无法执行模板替换；"
                        + "请在模板中定义文本域 / 复选框，或改用其他格式模板");
            }
            List<String> filled = new ArrayList<>();
            for (PDField field : fields) {
                if (apply(context, field)) {
                    filled.add(field.getFullyQualifiedName());
                }
            }
            refresh(document);
            if (filled.isEmpty()) {
                throw new ExportException("模板表单域与变量完全不匹配。域: " + names(fields) + "，变量: " + context.variables().keySet());
            }
            document.save(out);
        }
    }

    /**
     * 提取全部叶子域；模板无表单时返回空列表。
     *
     * @param document 文档
     * @return 叶子域列表
     */
    private List<PDField> acroForm(PDDocument document) {
        if (document.getDocumentCatalog().getAcroForm() == null) {
            return Wraps.list();
        }
        List<PDField> flat = Wraps.list();
        for (PDField root : document.getDocumentCatalog().getAcroForm().getFields()) {
            collect(root, flat);
        }
        return flat;
    }

    /**
     * 递归收集叶子域。
     *
     * @param field 当前域
     * @param sink  收集容器
     */
    private void collect(PDField field, List<PDField> sink) {
        List<PDField> children = field instanceof PDNonTerminalField form ? form.getChildren() : null;
        if (children == null || children.isEmpty()) {
            sink.add(field);
            return;
        }
        for (PDField child : children) {
            collect(child, sink);
        }
    }

    /**
     * 刷新表单外观（部分 PDFBox 版本可能失败，忽略）。
     *
     * @param document 文档
     */
    private void refresh(PDDocument document) {
        try {
            document.getDocumentCatalog().getAcroForm().refreshAppearances();
        } catch (Exception e) {
            // 外观刷新失败不影响字段取值
        }
    }

    /**
     * 为单个域赋值。
     *
     * @param context 模板上下文
     * @param field   域
     * @return 是否成功赋值
     * @throws IOException 写出失败
     */
    private boolean apply(TemplateContext context, PDField field) throws IOException {
        if (!(field instanceof PDTerminalField terminal)) {
            return false;
        }
        String fullName = field.getFullyQualifiedName();
        String shortName = field.getPartialName();
        Object value = resolve(context, fullName, shortName);

        if (field instanceof PDCheckBox checkBox) {
            Object bool = value != null ? value : placeholder(context, terminal);
            if (bool == null) {
                return false;
            }
            if (truthy(bool)) {
                checkBox.check();
            } else {
                checkBox.unCheck();
            }
            return true;
        }
        if (field instanceof PDRadioButton radio) {
            Object text = value != null ? value : placeholder(context, terminal);
            if (text == null) {
                return false;
            }
            String option = matchOption(radio.getOnValues(), String.valueOf(text));
            if (option == null) {
                return false;
            }
            radio.setValue(option);
            return true;
        }
        String text = value != null ? String.valueOf(value) : placeholder(context, terminal);
        if (field instanceof PDComboBox comboBox) {
            if (text == null) {
                return false;
            }
            comboBox.setValue(comboBoxValue(comboBox, text));
            return true;
        }
        if (field instanceof PDTextField textField) {
            if (text == null) {
                return false;
            }
            textField.setValue(text);
            return true;
        }
        if (text == null) {
            return false;
        }
        field.setValue(text);
        return true;
    }

    /**
     * 从域的默认值中解析 {@code {{tag}}} 占位符。
     *
     * @param context  模板上下文
     * @param terminal 域
     * @return 解析结果，不含占位符或未匹配时返回 {@code null}
     */
    private String placeholder(TemplateContext context, PDTerminalField terminal) throws IOException {
        String current = terminal.getValueAsString();
        if (current == null || current.isEmpty()) {
            return null;
        }
        // 复用统一的模板引擎：同一套语法（标量 / a.b 嵌套 / 多占位符），不另写正则
        Placeholders engine = Placeholders.of(context);
        if (!engine.contains(current)) {
            return null;
        }
        Object resolved = engine.renderValue(current, null, 0, 0);
        return resolved == null ? null : String.valueOf(resolved);
    }

    /**
     * 解析变量值：表单域名 → 变量表（忽略大小写）。
     *
     * @param context   模板上下文
     * @param fullName  域全名
     * @param shortName 域短名
     * @return 值
     */
    private Object resolve(TemplateContext context, String fullName, String shortName) {
        Map<String, Object> variables = context.variables();
        if (variables.containsKey(fullName)) {
            return variables.get(fullName);
        }
        if (variables.containsKey(shortName)) {
            return variables.get(shortName);
        }
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(fullName) || entry.getKey().equalsIgnoreCase(shortName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 判断布尔真值。
     *
     * @param value 值
     * @return 是否为真
     */
    private static boolean truthy(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text)
                || "on".equalsIgnoreCase(text) || "y".equalsIgnoreCase(text) || "1".equals(text);
    }

    /**
     * 在候选项中精确 / 忽略大小写匹配。
     *
     * @param options 候选
     * @param value   值
     * @return 命中的选项
     */
    private String matchOption(java.util.Collection<String> options, String value) {
        if (options == null) {
            return null;
        }
        for (String option : options) {
            if (option.equals(value)) {
                return option;
            }
        }
        for (String option : options) {
            if (option.equalsIgnoreCase(value)) {
                return option;
            }
        }
        return null;
    }

    /**
     * 解析下拉框取值，不存在则追加。
     *
     * @param comboBox 下拉框
     * @param value    值
     * @return 选项值
     */
    private String comboBoxValue(PDComboBox comboBox, String value) {
        List<String> options = comboBox.getOptions();
        if (options != null && options.contains(value)) {
            return value;
        }
        List<String> extended = new ArrayList<>(options == null ? List.of() : options);
        extended.add(value);
        comboBox.setOptions(extended);
        return value;
    }

    /**
     * 收集域名称，用于错误提示。
     *
     * @param fields 域
     * @return 名称列表
     */
    private static List<String> names(List<PDField> fields) {
        Set<String> names = new LinkedHashSet<>();
        fields.forEach(field -> names.add(field.getFullyQualifiedName()));
        return new ArrayList<>(names);
    }
}