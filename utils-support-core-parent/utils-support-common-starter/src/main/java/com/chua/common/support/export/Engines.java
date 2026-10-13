package com.chua.common.support.export;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.export.spi.DocumentGenerator;
import com.chua.common.support.export.spi.TemplateFiller;
import com.chua.common.support.spi.ServiceProvider;

import java.util.List;
import java.util.Set;

/**
 * 引擎查找 —— 把"格式"与"引擎别名"解析为具体的 SPI 实现。
 *
 * <p>查找顺序：显式 {@code engine} → 格式标识 → 首个已注册实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class Engines {

    private Engines() {
    }

    /**
     * 查找文档生成器。
     *
     * @param format 格式标识，如 {@code xlsx}
     * @param engine 显式引擎别名，可为 {@code null}
     * @return 文档生成器
     * @throws ExportException 找不到实现时抛出
     */
    public static DocumentGenerator generator(String format, String engine) {
        DocumentGenerator found = lookupGenerator(format, engine);
        if (found == null) {
            throw new ExportException("未找到格式 [" + format + "] 的文档生成器，可用格式: " + generatorFormats() + hint(format));
        }
        return found;
    }

    /**
     * 尝试查找文档生成器。
     *
     * @param format 格式标识
     * @param engine 显式引擎别名
     * @return 文档生成器，可能为 {@code null}
     */
    public static DocumentGenerator lookupGenerator(String format, String engine) {
        if (engine != null && !engine.isBlank()) {
            DocumentGenerator explicit = ServiceProvider.of(DocumentGenerator.class).getExtension(engine);
            if (explicit != null) {
                return explicit;
            }
        }
        DocumentGenerator byFormat = ServiceProvider.of(DocumentGenerator.class).getExtension(format);
        if (byFormat != null) {
            return byFormat;
        }
        List<DocumentGenerator> all = ServiceProvider.of(DocumentGenerator.class).collect();
        if (all.isEmpty()) {
            return null;
        }
        String target = ExportFormats.normalize(format);
        for (DocumentGenerator candidate : all) {
            if (candidate.formats().contains(target)) {
                return candidate;
            }
        }
        // 刻意不做"取第一个引擎"的兜底：格式对不上时宁可报错，
        // 也不要把 xlsx 的内容静默写进 .xls / .doc 里
        return null;
    }

    /**
     * 查找模板填充器。
     *
     * @param format 格式标识
     * @param engine 显式引擎别名
     * @return 模板填充器
     * @throws ExportException 找不到实现时抛出
     */
    public static TemplateFiller filler(String format, String engine) {
        TemplateFiller found = lookupFiller(format, engine);
        if (found == null) {
            throw new ExportException("未找到格式 [" + format + "] 的模板填充器，可用格式: " + fillerFormats() + hint(format));
        }
        return found;
    }

    /**
     * 旧版二进制格式的替代提示。
     *
     * @param format 格式标识
     * @return 提示后缀，无需提示时返回空串
     */
    private static String hint(String format) {
        String replacement = ExportFormats.replacementOf(format);
        return replacement == null ? "" : "；[" + format + "] 为旧版二进制格式，请改用 " + replacement;
    }

    /**
     * 尝试查找模板填充器。
     *
     * @param format 格式标识
     * @param engine 显式引擎别名
     * @return 模板填充器，可能为 {@code null}
     */
    public static TemplateFiller lookupFiller(String format, String engine) {
        if (engine != null && !engine.isBlank()) {
            TemplateFiller explicit = ServiceProvider.of(TemplateFiller.class).getExtension(engine);
            if (explicit != null) {
                return explicit;
            }
        }
        TemplateFiller byFormat = ServiceProvider.of(TemplateFiller.class).getExtension(format);
        if (byFormat != null) {
            return byFormat;
        }
        List<TemplateFiller> all = ServiceProvider.of(TemplateFiller.class).collect();
        String target = ExportFormats.normalize(format);
        for (TemplateFiller candidate : all) {
            if (candidate.formats().contains(target)) {
                return candidate;
            }
        }
        // 同 lookupGenerator：格式对不上时宁可报错，也不静默换成别的格式
        return null;
    }

    /**
     * 获取所有已注册生成器支持的格式。
     *
     * @return 格式集合
     */
    public static Set<String> generatorFormats() {
        Set<String> formats = Wraps.set();
        ServiceProvider.of(DocumentGenerator.class).collect(item -> formats.addAll(item.formats()));
        return formats;
    }

    /**
     * 获取所有已注册填充器支持的格式。
     *
     * @return 格式集合
     */
    public static Set<String> fillerFormats() {
        Set<String> formats = Wraps.set();
        ServiceProvider.of(TemplateFiller.class).collect(item -> formats.addAll(item.formats()));
        return formats;
    }

    /**
     * 取已注册填充器声明的任一格式，用于模板与输出都没有扩展名时的兜底。
     *
     * <p>注册了多个填充器时结果不确定，此时应由调用方显式
     * {@code TemplateExportBuilder#format(String)} 指定。</p>
     *
     * @return 格式标识，无可用填充器时返回 {@code null}
     */
    public static String anyFillerFormat() {
        for (TemplateFiller filler : ServiceProvider.of(TemplateFiller.class).collect()) {
            for (String declared : filler.formats()) {
                return declared;
            }
        }
        return null;
    }

    /**
     * 打印当前可用的导出引擎，便于排障。
     *
     * @return 可用引擎摘要
     */
    public static String describe() {
        return "DocumentGenerator formats=" + generatorFormats()
                + ", TemplateFiller formats=" + fillerFormats();
    }
}