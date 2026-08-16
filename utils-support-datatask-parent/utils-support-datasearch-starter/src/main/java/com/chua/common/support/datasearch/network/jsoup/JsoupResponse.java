package com.chua.common.support.datasearch.network.jsoup;

import com.chua.common.support.utils.BeanUtils;
import com.chua.common.support.utils.StringUtils;
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

    private final Mappings mappings;

    public JsoupResponse(String html, Mappings mappings) {
        this.document = Jsoup.parse(html);
        this.mappings = mappings;
    }

    public Document getDocument() {
        return document;
    }

    public Mappings getMappings() {
        return mappings;
    }

    public JsoupResponse peek(Consumer<Document> consumer) {
        consumer.accept(document);
        return this;
    }

    public <R> R map(Function<Document, R> mapper) {
        return mapper.apply(document);
    }

    public View view() {
        return new View(document);
    }

    public <T> List<T> eval(Class<T> targetClass) {
        String parentXpath = mappings.getParentXpath();
        List<MappingsPath> mapping = mappings.getMapping();
        if (StringUtils.isNotEmpty(parentXpath)) {
            return createResult(targetClass, parentXpath, mapping);
        }
        Map<String, Object> item = createItem(document, mapping);
        return Collections.singletonList(createResult(targetClass, (Map<String, Object>) item));
    }

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

    private <T> T createResult(Class<T> targetClass, Map<String, Object> item) {
        T result;
        try {
            result = targetClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("创建实例失败: " + targetClass.getName(), e);
        }
        BeanUtils.copyProperties(item, result);
        return result;
    }

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
        private String parentXpath;
        @Singular("addMapping")
        private List<MappingsPath> mapping;
    }

    @Data
    public static class MappingsPath {
        private String path;
        private String attribute;
        private boolean last;
        private boolean first;
        private Function<Element, String> function;
        private PathType type = PathType.XPATH;
        private String field;

        public static MappingsPathBuilder builder() {
            return new MappingsPathBuilder();
        }

        public static class MappingsPathBuilder {
            private String path;
            private String attribute;
            private boolean last;
            private boolean first;
            private Function<Element, String> function;
            private PathType type = PathType.XPATH;
            private String field;

            public MappingsPathBuilder path(String path) { this.path = path; return this; }
            public MappingsPathBuilder attribute(String attribute) { this.attribute = attribute; return this; }
            public MappingsPathBuilder first(boolean first) { this.first = first; return this; }
            public MappingsPathBuilder last(boolean last) { this.last = last; return this; }
            public MappingsPathBuilder function(Function<Element, String> function) { this.function = function; return this; }
            public MappingsPathBuilder type(PathType type) { this.type = type; return this; }
            public MappingsPathBuilder field(String field) { this.field = field; return this; }
            public MappingsPathBuilder href() { this.attribute("href"); return this; }
            public MappingsPathBuilder src() { this.attribute("src"); return this; }
            public MappingsPathBuilder isFirst() { this.first(true); return this; }
            public MappingsPathBuilder isLast() { this.last(true); return this; }
            public MappingsPathBuilder css() { this.type(PathType.CSS); return this; }
            public MappingsPathBuilder function() { this.type(PathType.XPATH_FUNCTION); return this; }

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
        private final Elements elements;

        private View(Elements elements) { this.elements = elements; }
        private View(Document document) { this.elements = document.getAllElements(); }

        public View css(String selector) { return new View(elements.select(selector)); }
        public View xpath(String xpath) {
            Elements result = new Elements();
            for (Element element : elements) {
                result.addAll(element.selectXpath(xpath));
            }
            return new View(result);
        }
        public View peek(Consumer<Elements> consumer) { consumer.accept(elements); return this; }
        public <R> R map(Function<Elements, R> mapper) { return mapper.apply(elements); }
        public <R> List<R> mapList(Function<Element, R> mapper) { return elements.stream().map(mapper).toList(); }
        public <R> R first(Function<Element, R> mapper) { Element first = elements.first(); return first == null ? null : mapper.apply(first); }
        public <R> R last(Function<Element, R> mapper) { Element last = elements.last(); return last == null ? null : mapper.apply(last); }
        public Elements getElements() { return elements; }
    }
}