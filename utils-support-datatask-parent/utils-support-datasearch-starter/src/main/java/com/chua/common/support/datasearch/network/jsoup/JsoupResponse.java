package com.chua.common.support.datasearch.network.jsoup;

import com.chua.common.support.utils.BeanUtils;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.reflection.ReflectUtils;
import lombok.Builder;
import lombok.Data;
import lombok.Singular;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Jsoup 响应封装
 *
 * @author CH
 * @since 4.0.0.42
 */
public class JsoupResponse {

    final Document document;

    /** Mappings */
    private final Mappings mappings;

    /**
     * 创建 JsoupResponse 实例
     * @param html html
     * @param Mappings Mappings
     */
    public JsoupResponse(String html, Mappings mappings) {
        this.document = Jsoup.parse(html);
        this.mappings = mappings;
    }

    /** 获取Document */
    public Document getDocument() {
        return document;
    }

    /** 获取Mappings */
    public Mappings getMappings() {
        return mappings;
    }

    /** 查看 */
    public JsoupResponse peek(Consumer<Document> consumer) {
        consumer.accept(document);
        return this;
    }

    /** Map */
    public <R> R map(Function<Document, R> mapper) {
        return mapper.apply(document);
    }

    /** View */
    public View view() {
        return new View(document);
    }

    /** Eval */
    public <T> List<T> eval(Class<T> targetClass) {
        String parentXpath = mappings.getParentXpath();
        List<MappingsPath> mapping = mappings.getMapping();
        if (StringUtils.isNotEmpty(parentXpath)) {
            return createResult(targetClass, parentXpath, mapping);
        }
        Map<String, Object> item = createItem(document, mapping);
        return Collections.singletonList(createResult(targetClass, (Map<String, Object>) item));
    }

    /** 创建Result */
    private <T> List<T> createResult(Class<T> targetClass, String parentXpath, List<MappingsPath> mapping) {
        List<T> result = new ArrayList<>();
        Elements elements = document.selectXpath(parentXpath);
        for (Element element : elements) {
            Map<String, Object> item = createItem(element, mapping);
            T t = createResult(targetClass, item);
            result.add(t);
        }
        return result;
    }

    /** 创建Result */
    private <T> T createResult(Class<T> targetClass, Map<String, Object> item) {
        T result;
        try {
            result = ReflectUtils.instantiate(targetClass);
        } catch (Exception e) {
            throw new RuntimeException("创建实例失败: " + targetClass.getName(), e);
        }
        BeanUtils.copyProperties(item, result);
        return result;
    }

    /** 创建Item */
    private Map<String, Object> createItem(Element element, List<MappingsPath> mapping) {
        Map<String, Object> item = new HashMap<>();
        for (MappingsPath mappingsPath : mapping) {
            if (mappingsPath.getType() == PathType.XPATH) {
                item.put(mappingsPath.getField(), getValue(element.selectXpath(mappingsPath.getPath()), mappingsPath));
                continue;
            }
            if (mappingsPath.getType() == PathType.CSS) {
                item.put(mappingsPath.getField(), getValue(element.select(mappingsPath.getPath()), mappingsPath));
                continue;
            }
            if (mappingsPath.getType() == PathType.XPATH_FUNCTION) {
                item.put(mappingsPath.getField(), mappingsPath.getFunction().apply(element));
            }
        }
        return item;
    }

    /** 获取Value */
    private Object getValue(Elements element, MappingsPath mappingsPath) {
        if (mappingsPath.isFirst()) {
            return getItemValue(element.first(), mappingsPath);
        }
        if (mappingsPath.isLast()) {
            return getItemValue(element.last(), mappingsPath);
        }
        String attribute = mappingsPath.getAttribute();
        if (StringUtils.isNotEmpty(attribute)) {
            return element.attr(attribute);
        }
        if (element instanceof Collection<?> list && list.size() > 1) {
            return list.stream().map(it -> getItemValue((Element) it, mappingsPath)).toList();
        }
        return element.text();
    }

    /** 获取ItemValue */
    private String getItemValue(Element element, MappingsPath mappingsPath) {
        if (null == element) {
            return null;
        }
        String attribute = mappingsPath.getAttribute();
        if (StringUtils.isNotEmpty(attribute)) {
            return element.attr(attribute);
        }
        return element.text();
    }

    /** Eval */
    public Map<String, Object> eval(MappingsPath mappingsPath) {
        return createItem(document, Collections.singletonList(mappingsPath));
    }

    public enum PathType {
        XPATH,
        XPATH_FUNCTION,
        CSS
    }

    @Data
    @Builder
    public static class Mappings {
        /** 父级xpath */
        private String parentXpath;
        @Singular("addMapping")
        /** Mapping */
        private List<MappingsPath> mapping;
    }

    @Data
    public static class MappingsPath {
        /** 路径 */
        private String path;
        /** Attribute */
        private String attribute;
        /** 最后 */
        private boolean last;
        /** 首个 */
        private boolean first;
        /** function */
        private Function<Element, String> function;
        /** 类型 */
        private PathType type = PathType.XPATH;
        /** Field */
        private String field;

        /** Builder */
        public static MappingsPathBuilder builder() {
            return new MappingsPathBuilder();
        }

        public static class MappingsPathBuilder {
            /** 路径 */
            private String path;
            /** Attribute */
            private String attribute;
            /** 最后 */
            private boolean last;
            /** 首个 */
            private boolean first;
            /** function */
            private Function<Element, String> function;
            /** 类型 */
            private PathType type = PathType.XPATH;
            /** Field */
            private String field;

            /** Path */
            public MappingsPathBuilder path(String path) { this.path = path; return this; }
            /** Attribute */
            public MappingsPathBuilder attribute(String attribute) { this.attribute = attribute; return this; }
            /** First */
            public MappingsPathBuilder first(boolean first) { this.first = first; return this; }
            /** Last */
            public MappingsPathBuilder last(boolean last) { this.last = last; return this; }
            /** Function */
            public MappingsPathBuilder function(Function<Element, String> function) { this.function = function; return this; }
            /** Type */
            public MappingsPathBuilder type(PathType type) { this.type = type; return this; }
            /** Field */
            public MappingsPathBuilder field(String field) { this.field = field; return this; }
            /** Href */
            public MappingsPathBuilder href() { this.attribute("href"); return this; }
            /** Src */
            public MappingsPathBuilder src() { this.attribute("src"); return this; }
            /** 是否First */
            public MappingsPathBuilder isFirst() { this.first(true); return this; }
            /** 是否Last */
            public MappingsPathBuilder isLast() { this.last(true); return this; }
            /** Css */
            public MappingsPathBuilder css() { this.type(PathType.CSS); return this; }
            /** Function */
            public MappingsPathBuilder function() { this.type(PathType.XPATH_FUNCTION); return this; }

            /** 构建 */
            public MappingsPath build() {
                MappingsPath mappingsPath = new MappingsPath();
                mappingsPath.path = this.path;
                mappingsPath.attribute = this.attribute;
                mappingsPath.last = this.last;
                mappingsPath.first = this.first;
                mappingsPath.function = this.function;
                mappingsPath.type = this.type;
                mappingsPath.field = this.field;
                return mappingsPath;
            }
        }
    }

    public static final class View {
        /** Elements */
        private final Elements elements;

        /**
         * 创建 View 实例
         * @param elements elements
         */
        private View(Elements elements) { this.elements = elements; }
        /**
         * 创建 View 实例
         * @param document document
         */
        private View(Document document) { this.elements = document.getAllElements(); }

        /** Css */
        public View css(String selector) { return new View(elements.select(selector)); }
        /** Xpath */
        public View xpath(String xpath) {
            Elements result = new Elements();
            for (Element element : elements) {
                result.addAll(element.selectXpath(xpath));
            }
            return new View(result);
        }
        /** 查看 */
        public View peek(Consumer<Elements> consumer) { consumer.accept(elements); return this; }
        /** Map */
        public <R> R map(Function<Elements, R> mapper) { return mapper.apply(elements); }
        /** MapList */
        public <R> List<R> mapList(Function<Element, R> mapper) { return elements.stream().map(mapper).toList(); }
        /** First */
        public <R> R first(Function<Element, R> mapper) { Element first = elements.first(); return first == null ? null : mapper.apply(first); }
        /** Last */
        public <R> R last(Function<Element, R> mapper) { Element last = elements.last(); return last == null ? null : mapper.apply(last); }
        /** 获取Elements */
        public Elements getElements() { return elements; }
    }
}