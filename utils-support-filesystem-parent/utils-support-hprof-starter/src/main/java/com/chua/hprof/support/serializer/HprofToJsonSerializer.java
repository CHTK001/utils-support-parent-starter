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
import java.util.Map;

/**
 * 将解析后的 hprof 结果序列化为 JSON 文档结构：
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

    /**
     * 私有构造函数：本类为静态工具类，仅通过静态方法对外提供能力，禁止外部实例化。
     */
    private HprofToJsonSerializer() {
    }

    /**
     * 将解析后的 hprof 结果序列化为 JSON 字符串。
     *
     * @param result   解析后的 hprof 结果，不允许为 null
     * @param fileName 用于元信息块的源文件名
     * @return JSON 文档字符串
     */
    public static String serialize(HprofParser.Result result, String fileName) {
        try {
            return MAPPER.writeValueAsString(buildDocument(result, fileName));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize hprof result", e);
        }
    }

    /**
     * 构建 JSON 文档根节点。
     *
     * @param result   解析后的 hprof 结果，不允许为 null
     * @param fileName 源文件名
     * @return 文档根节点
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
                com.chua.hprof.support.analyzer.HprofAnalyzer.analyze(result,
                        fileName == null ? null : new java.io.File(fileName));
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
        }
        ObjectNode metrics = analysisNode.putObject("metrics");
        metrics.put("top10_retained_ratio", analysis.topNRetainedRatio);
        metrics.put("collection_retained_bytes", analysis.collectionRetainedBytes);
        metrics.put("class_loader_class_count", analysis.classLoaderClassCount);
        metrics.put("string_and_binary_retained_bytes", analysis.stringAndBinaryRetainedBytes);
        metrics.put("jdk_retained_bytes", analysis.jdkRetainedBytes);
        metrics.put("non_jdk_retained_bytes", analysis.nonJdkRetainedBytes);
        // GC 根分布：区分线程池 / JNI / 类加载器泄漏的关键线索
        ObjectNode gcRootsNode = analysisNode.putObject("gc_roots");
        ObjectNode byKind = gcRootsNode.putObject("by_kind");
        if (analysis.gcRootsByKind != null) {
            for (Map.Entry<String, Long> e : analysis.gcRootsByKind.entrySet()) {
                byKind.put(e.getKey(), e.getValue());
            }
        }
        ArrayNode rootsArr = gcRootsNode.putArray("roots");
        if (result.gcRoots() != null) {
            for (String r : result.gcRoots()) {
                rootsArr.add(r);
            }
        }
        ArrayNode nonJdkGroups = analysisNode.putArray("non_jdk_packages");
        for (com.chua.hprof.support.analyzer.HprofAnalyzer.PackageGroup g : analysis.nonJdkPackageGroups) {
            ObjectNode item = nonJdkGroups.addObject();
            item.put("package", g.name());
            item.put("instances", g.instances());
            item.put("retained_bytes", g.retained());
        }
        analysisNode.put("root_cause", analysis.rootCause);
        analysisNode.put("root_cause_headline", analysis.rootCauseHeadline);
        ArrayNode rcMechanisms = analysisNode.putArray("root_cause_mechanisms");
        if (analysis.rootCauseMechanisms != null) {
            for (String m : analysis.rootCauseMechanisms) {
                rcMechanisms.add(m);
            }
        }
        ArrayNode rcSections = analysisNode.putArray("root_cause_sections");
        if (analysis.rootCauseSections != null) {
            for (com.chua.hprof.support.analyzer.HprofAnalyzer.RootCauseSection s
                    : analysis.rootCauseSections) {
                ObjectNode sec = rcSections.addObject();
                sec.put("label", s.label());
                sec.put("text", s.text());
            }
        }
        // 崩溃语境（OOM 推断 / 堆水位 / hs_err 证据）
        ObjectNode crashNode = analysisNode.putObject("crash_context");
        crashNode.put("oom_likely", analysis.oomLikely);
        ArrayNode crashSignals = crashNode.putArray("signals");
        if (analysis.crashSignals != null) {
            for (com.chua.hprof.support.crash.CrashContext.CrashSignal s : analysis.crashSignals) {
                ObjectNode item = crashSignals.addObject();
                item.put("kind", s.kind());
                item.put("detail", s.detail());
                item.put("evidence", s.evidence());
                item.put("oom_likely", s.oomLikely());
            }
        }
        // 逐实例字段明细：让 AI 能读到"是谁把东西存进去了"
        ArrayNode classDetails = root.putArray("class_details");
        if (result.classDetails() != null) {
            for (com.chua.hprof.support.model.HprofClassDetail detail : result.classDetails().values()) {
                ObjectNode d = classDetails.addObject();
                d.put("class", detail.getClassName());
                ArrayNode insts = d.putArray("instances");
                for (com.chua.hprof.support.model.HprofClassDetail.InstanceDetail inst : detail.getInstances()) {
                    ObjectNode i = insts.addObject();
                    i.put("instance_id", inst.getInstanceId());
                    i.put("retained_size", inst.getRetainedSize());
                    i.put("shallow_size", inst.getShallowSize());
                    ArrayNode fvs = i.putArray("fields");
                    for (com.chua.hprof.support.model.HprofClassDetail.FieldValueDetail fv : inst.getFieldValues()) {
                        ObjectNode f = fvs.addObject();
                        f.put("name", fv.getName());
                        f.put("type", fv.getType());
                        f.put("value", fv.getValueText());
                    }
                }
                ArrayNode statics = d.putArray("static_fields");
                for (com.chua.hprof.support.model.HprofClassDetail.FieldValueDetail fv : detail.getStaticFields()) {
                    ObjectNode f = statics.addObject();
                    f.put("name", fv.getName());
                    f.put("type", fv.getType());
                    f.put("value", fv.getValueText());
                }
            }
        }

        // 3 层引用链（持有实例 → 字段 → 子引用）
        ArrayNode refChains = root.putArray("ref_chains");
        if (result.refChains() != null) {
            for (com.chua.hprof.support.parser.HprofRefChainWalker.RefChain chain : result.refChains()) {
                ObjectNode c = refChains.addObject();
                c.put("holder_class", chain.holderClass());
                c.put("holder_id", chain.holderId());
                c.put("holder_retained", chain.holderRetained());
                ArrayNode fvs = c.putArray("fields");
                for (com.chua.hprof.support.model.HprofClassDetail.FieldValueDetail fv : chain.fields()) {
                    ObjectNode f = fvs.addObject();
                    f.put("name", fv.getName());
                    f.put("value", fv.getValueText());
                }
                ArrayNode children = c.putArray("children");
                for (com.chua.hprof.support.parser.HprofRefChainWalker.ChildRef child : chain.children()) {
                    ObjectNode ch = children.addObject();
                    ch.put("class", child.className());
                    ch.put("retained", child.retained());
                    ch.put("instance_id", child.instanceId());
                }
            }
        }

        // 处置计划（问题 → 怎么做 → 预期 → 验证）
        com.chua.hprof.support.action.HprofActionPlanner.ActionPlan plan =
                com.chua.hprof.support.action.HprofActionPlanner.plan(result, analysis);
        ObjectNode planNode = root.putObject("action_plan");
        planNode.put("problem_summary", plan.problemSummary());
        planNode.put("idea_specific", plan.ideaSpecific());
        ArrayNode planItems = planNode.putArray("items");
        for (com.chua.hprof.support.action.HprofActionPlanner.ActionItem item : plan.items()) {
            ObjectNode p = planItems.addObject();
            p.put("id", item.id());
            p.put("title", item.title());
            p.put("how", item.how());
            p.put("expected_effect", item.expectedEffect());
            p.put("verify", item.verify());
            p.put("priority", item.priority());
        }
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
