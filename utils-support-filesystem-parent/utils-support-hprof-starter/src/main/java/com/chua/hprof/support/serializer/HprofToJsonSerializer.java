package com.chua.hprof.support.serializer;

import com.chua.hprof.support.analyzer.HprofAnalyzer;
import com.chua.hprof.support.model.HprofHistogramRow;
import com.chua.hprof.support.model.HprofObject;
import com.chua.hprof.support.parser.HprofParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Serializes a parsed hprof result into the JSON document shape:
 * <pre>{@code
 * {
 *   "leak_suspects": [
 *     {"class": "java.util.HashMap", "retained_size": "1.2GB", "gc_root": "static OrderCache.cache"}
 *   ]
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class HprofToJsonSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HprofToJsonSerializer() {
    }

    /**
    * Serialize the parsed result to a JSON string.
    *
    * @param result   parsed hprof result
    * @param fileName source file name for the metadata block
    * @return JSON document string
    */
    public static String serialize(HprofParser.Result result, String fileName) {
        try {
            return MAPPER.writeValueAsString(buildDocument(result, fileName));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize hprof result", e);
        }
    }

    /**
    * Build the root JSON document node.
    *
    * @param result   parsed hprof result
    * @param fileName source file name
    * @return the document node
    */
    private static JsonNode buildDocument(HprofParser.Result result, String fileName) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode meta = root.putObject("meta");
        meta.put("source_file", fileName);
        meta.put("total_objects", result.totalObjectCount());
        meta.put("total_retained_bytes", result.totalRetainedBytes());
        meta.put("total_retained", HprofObject.formatSize(result.totalRetainedBytes()));

        ArrayNode leakSuspects = root.putArray("leak_suspects");
        for (HprofObject suspect : result.topRetained()) {
            ObjectNode item = leakSuspects.addObject();
            item.put("class", suspect.getClassName());
            item.put("retained_size", suspect.getRetainedSizeText());
            item.put("gc_root", suspect.getGcRoot() != null ? suspect.getGcRoot() : "unknown");
        }

        ArrayNode histogram = root.putArray("class_histogram");
        for (HprofHistogramRow row : result.histogram()) {
            ObjectNode item = histogram.addObject();
            item.put("class", row.getClassName());
            item.put("instance_count", row.getInstanceCount());
            item.put("shallow_size", row.getShallowSize());
            item.put("retained_size", row.getRetainedSize());
            item.put("retained_size_text", HprofObject.formatSize(row.getRetainedSize()));
        }

        // algorithmic analysis (findings + conclusions) so an AI can read
        // the "why is memory high" answer directly from the document
        com.chua.hprof.support.analyzer.HprofAnalyzer.HprofAnalysis analysis =
                com.chua.hprof.support.analyzer.HprofAnalyzer.analyze(result);
        ObjectNode analysisNode = root.putObject("analysis");
        ArrayNode findings = analysisNode.putArray("findings");
        for (com.chua.hprof.support.analyzer.HprofAnalyzer.HprofFinding f : analysis.findingDetails) {
            ObjectNode item = findings.addObject();
            item.put("key", f.key());
            item.put("severity", f.severity());
            item.put("title", f.title());
            item.put("detail", f.detail());
        }
        ArrayNode conclusions = analysisNode.putArray("conclusions");
        for (String c : analysis.conclusions) {
            conclusions.add(c);
        }        ObjectNode metrics = analysisNode.putObject("metrics");
        metrics.put("top10_retained_ratio", analysis.topNRetainedRatio);
        metrics.put("collection_retained_bytes", analysis.collectionRetainedBytes);
        metrics.put("class_loader_class_count", analysis.classLoaderClassCount);
        metrics.put("string_and_binary_retained_bytes", analysis.stringAndBinaryRetainedBytes);
        metrics.put("jdk_retained_bytes", analysis.jdkRetainedBytes);
        metrics.put("non_jdk_retained_bytes", analysis.nonJdkRetainedBytes);
        ArrayNode nonJdkGroups = analysisNode.putArray("non_jdk_packages");
        for (com.chua.hprof.support.analyzer.HprofAnalyzer.PackageGroup g : analysis.nonJdkPackageGroups) {
            ObjectNode item = nonJdkGroups.addObject();
            item.put("package", g.name());
            item.put("instances", g.instances());
            item.put("retained_bytes", g.retained());
        }
        analysisNode.put("root_cause", analysis.rootCause);
        return root;
    }

    /**
    * Whether the given bytes form a valid hprof document.
    *
    * @param json json candidate
    * @return true when parseable
    */
    public static boolean isValidJson(String json) {
        try {
            MAPPER.readTree(json);
            return true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }
}
