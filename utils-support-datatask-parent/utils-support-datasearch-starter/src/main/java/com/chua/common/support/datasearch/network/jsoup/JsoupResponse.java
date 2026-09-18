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

    final Document document; // 文档

    /** Mappings */
    private final Mappings mappings;

    /**
    * 创建 jsoup响应 实例
    * @param html HTML
    * @param mappings Mappings
    * @param mappings mappings
    */
    public JsoupResponse(String html, Mappings mappings) {
        this.document = Jsoup.parse(html);
        this.mappings = mappings;
    }

    /**
    * 获取文档
    *
    * @return 获取文档的结果
    */
    public Document getDocument() {
        return document;
    }

    /**
    * 获取Mappings
    *
    * @return 获取mappings的结果
    */
    public Mappings getMappings() {
        return mappings;
    }

    /**
    * 查看
    *
    * @param consumer consumer
    * @return peek的结果
    */
    public JsoupResponse peek(Consumer<Document> consumer) {
        consumer.accept(document);
        return this;
    }

    /**
    * 映射
    *
    * @param mapper 映射器
    * @return 映射的结果
    */
    public <R> R map(Function<Document, R> mapper) {
        return mapper.apply(document);
    }

    /**
    * View
    *
    * @return view的结果
    */
    public View view() {
        return new View(document);
    }

    /**
    * Eval
    *
    * @param targetClass 目标类
    * @return eval的结果
    */
    public <T> List<T> eval(Class<T> targetClass) {
        String parentXpath = mappings.getParentXpath();
        List<MappingsPath> mapping = mappings.getMapping();
        if (StringUtils.isNotEmpty(parentXpath)) {
            return createResult(targetClass, parentXpath, mapping);
        }
        Map<String, Object> item = createItem(document, mapping);
        return Collections.singletonList(createResult(targetClass, (Map<String, Object>) item));
    }

    /**
    * 创建结果
    *
    * @param targetClass 目标类
    * @param parentXpath 父xpath
    * @param mapping mapping
    * @return 创建结果的结果
    */
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

    /**
    * 创建结果
    *
    * @param targetClass 目标类
    * @param item item
    * @return 创建结果的结果
    */
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

    /**
    * 创建Item
    *
    * @param element element
    * @param mapping mapping
    * @return 创建item的结果
    */
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

    /**
    * 获取值
    *
    * @param element element
    * @param mappingsPath mappings路径
    * @return 获取值的结果
    */
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

    /**
    * 获取item值
    *
    * @param element element
    * @param mappingsPath mappings路径
    * @return 获取item值的结果
    */
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

    /**
    * Eval
    *
    * @param mappingsPath mappings路径
    * @return eval的结果
    */
    public Map<String, Object> eval(MappingsPath mappingsPath) {
        return createItem(document, Collections.singletonList(mappingsPath));
    /**
    * 路径类型枚举。
    *
    * @author CH
    * @since 4.0.0
    */
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
        /** 字段 */
        private String field;

        /**
        * 构建器
        *
        * @return 构建器的结果
        * @author CH
        * @since 4.0.0
        */
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
            /** 字段 */
            private String field;

            /**
            * 路径
            *
            * @param path 路径
            * @return 路径的结果
            */
            public MappingsPathBuilder path(String path) {
                this.path = path;
                return this;
            }
            /**
            * Attribute
            *
            * @param attribute attribute
            * @return attribute的结果
            */
            public MappingsPathBuilder attribute(String attribute) {
                this.attribute = attribute;
                return this;
            }
            /**
            * 第一个
            *
            * @param first 第一个
            * @return 第一个的结果
            */
            public MappingsPathBuilder first(boolean first) {
                this.first = first;
                return this;
            }
            /**
            * 最后一个
            *
            * @param last 最后一个
            * @return 最后一个的结果
            */
            public MappingsPathBuilder last(boolean last) {
                this.last = last;
                return this;
            }
            /**
            * Function
            *
            * @param function function
            * @return function的结果
            */
            public MappingsPathBuilder function(Function<Element, String> function) {
                this.function = function;
                return this;
            }
            /**
            * 类型
            *
            * @param type 类型
            * @return 类型的结果
            */
            public MappingsPathBuilder type(PathType type) {
                this.type = type;
                return this;
            }
            /**
            * 字段
            *
            * @param field 字段
            * @return 字段的结果
            */
            public MappingsPathBuilder field(String field) {
                this.field = field;
                return this;
            }
            /**
            * Href
            *
            * @return href的结果
            */
            public MappingsPathBuilder href() {
                this.attribute("href");
                return this;
            }
            /**
            * Src
            *
            * @return src的结果
            */
            public MappingsPathBuilder src() {
                this.attribute("src");
                return this;
            }
            /**
            * 是否第一个
            *
            * @return 是否第一个的结果
            */
            public MappingsPathBuilder isFirst() {
                this.first(true);
                return this;
            }
            /**
            * 是否最后一个
            *
            * @return 是否最后一个的结果
            */
            public MappingsPathBuilder isLast() {
                this.last(true);
                return this;
            }
            /**
            * CSS
            *
            * @return css的结果
            */
            public MappingsPathBuilder css() {
                this.type(PathType.CSS);
                return this;
            }
            /**
            * Function
            *
            * @return function的结果
            */
            public MappingsPathBuilder function() {
                this.type(PathType.XPATH_FUNCTION);
                return this;
            }

            /**
            * 构建
            *
            * @return 构建的结果
            */
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
        * @return View的结果
        */
        private View(Elements elements) { this.elements = elements; }
        /**
        * 创建 View 实例
        * @param document 文档
        * @return View的结果
        */
        private View(Document document) { this.elements = document.getAllElements(); }

        /**
        * CSS
        *
        * @param selector selector
        * @return css的结果
        */
        public View css(String selector) { return new View(elements.select(selector)); }
        /**
        * Xpath
        *
        * @param xpath xpath
        * @return xpath的结果
        */
        public View xpath(String xpath) {
            Elements result = new Elements();
            for (Element element : elements) {
                result.addAll(element.selectXpath(xpath));
            }
            return new View(result);
        }
        /**
        * 查看
        *
        * @param consumer consumer
        * @return peek的结果
        */
        public View peek(Consumer<Elements> consumer) {
            consumer.accept(elements);
            return this;
        }
        /**
        * 映射
        *
        * @param mapper 映射器
        * @return 映射的结果
        */
        public <R> R map(Function<Elements, R> mapper) { return mapper.apply(elements); }
        /**
        * 映射列表
        *
        * @param mapper 映射器
        * @return 映射列表的结果
        */
        public <R> List<R> mapList(Function<Element, R> mapper) { return elements.stream().map(mapper).toList(); }
        /**
        * 第一个
        *
        * @param mapper 映射器
        * @return 第一个的结果
        */
        public <R> R first(Function<Element, R> mapper) {
            Element first = elements.first();
            return first == null ? null : mapper.apply(first);
        }
        /**
        * 最后一个
        *
        * @param mapper 映射器
        * @return 最后一个的结果
        */
        public <R> R last(Function<Element, R> mapper) {
            Element last = elements.last();
            return last == null ? null : mapper.apply(last);
        }
        /**
        * 获取Elements
        *
        * @return 获取elements的结果
        */
        public Elements getElements() { return elements; }
    }
}
