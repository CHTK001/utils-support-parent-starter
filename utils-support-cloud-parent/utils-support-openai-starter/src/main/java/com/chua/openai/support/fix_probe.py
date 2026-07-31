import re

with open('OpenAiProbeStation.java', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Add scannedModels field
content = content.replace(
    '    private final ChatClientSetting setting;\n\n    /**',
    '    private final ChatClientSetting setting;\n\n    /**\n     * 模型列表探测结果缓存。\n     */\n    private List<String> scannedModels;\n\n    /**'
)

# 2. Add resolveModel method
content = content.replace(
    '    private String resolveBaseUrl() {',
    '    /**\n     * 探测使用的模型名称（优先使用配置中的 model）。\n     *\n     * @return 模型名称\n     */\n    private String resolveModel() {\n        String model = setting.getModel();\n        return (model == null || model.isBlank()) ? "gpt-4" : model;\n    }\n\n    private String resolveBaseUrl() {'
)

# 3. Update probeModelsScan to extract model IDs
content = content.replace(
    '        JsonObject json = Json.getJsonObject(response.getBodyString());\n        int modelCount = json.getJsonArray("data").size();\n\n        if (modelCount > 0) {',
    '        JsonObject json = Json.getJsonObject(response.getBodyString());\n        List<String> models = new ArrayList<>();\n        if (json.has("data") && json.get("data") instanceof java.util.List<?> dataList) {\n            for (Object item : dataList) {\n                if (item instanceof JsonObject obj && obj.has("id")) {\n                    models.add(obj.getString("id"));\n                }\n            }\n        }\n        scannedModels = models;\n        int modelCount = models.size();\n\n        if (modelCount > 0) {'
)

# 4. Update probeModelMatrixSniff to use scanned models
old_sniff = '''    private ProbeResult probeModelMatrixSniff() {
        String baseUrl = resolveBaseUrl();
        String apiKey = setting.getAppKey();
        String[] candidates = {
                "gpt-4", "gpt-4-turbo", "gpt-4o", "gpt-3.5-turbo",
                "claude-3-opus", "claude-3-sonnet", "claude-2",
                "deepseek-chat", "deepseek-coder",
                "qwen-turbo", "qwen-plus", "qwen-max",
                "doubao-lite", "doubao-pro",
                "zhipu", "glm-4",
                "yi-34b", "yi-lightning",
                "moonshot-v1", "moonshot-v1-8k",
                "baichuan", "baichuan2"
        };

        int passCount = 0;
        int totalCount = candidates.length;
        StringBuilder sniffLog = new StringBuilder();

        for (String model : candidates) {'''

new_sniff = '''    private ProbeResult probeModelMatrixSniff() {
        String baseUrl = resolveBaseUrl();
        String apiKey = setting.getAppKey();
        List<String> sniffModels = new ArrayList<>();
        if (scannedModels != null && !scannedModels.isEmpty()) {
            sniffModels.addAll(scannedModels);
        } else {
            sniffModels.add("gpt-4");
            sniffModels.add("gpt-4-turbo");
            sniffModels.add("gpt-4o");
            sniffModels.add("claude-3-opus");
            sniffModels.add("deepseek-chat");
            sniffModels.add("qwen-turbo");
            sniffModels.add("glm-4");
        }
        int maxSniff = Math.min(sniffModels.size(), 10);
        int passCount = 0;
        int totalCount = maxSniff;
        StringBuilder sniffLog = new StringBuilder();

        for (int i = 0; i < maxSniff; i++) {
            String model = sniffModels.get(i);'''

content = content.replace(old_sniff, new_sniff)

# 5. Update probeChat to use resolveModel and handle 403/404
old_chat = '''    private ProbeResult probeChat(String dimensionKey, String prompt) {
        String mapped = switch (dimensionKey) {
            case "identity" -> "IDENTITY_PROBE";
            case "jailbreak" -> "JAILBREAK_PROBE";
            case "knowledge_cutoff" -> "KNOWLEDGE_CUTOFF";
            case "safety_alignment" -> "SAFETY_ALIGNMENT";
            case "math_trap" -> "MATH_TRAP";
            default -> dimensionKey.toUpperCase();
        };
        String apiKey = setting.getAppKey();
        String body = buildChatBody(resolveModel(), prompt, DEFAULT_MAX_TOKENS, DEFAULT_TEMPERATURE);

        try {
            long startTime = System.currentTimeMillis();
            ClientResponse response = HttpClientFactory.of(resolveBaseUrl())
                    .header("Authorization", "Bearer " + apiKey)
                    .path("/v1/chat/completions")
                    .json()
                    .body(body)
                    .post();
            long duration = System.currentTimeMillis() - startTime;

            if (!response.isSuccess()) {
                return ProbeResult.builder()
                        .dimension(ProbeDimension.valueOf(mapped))
                        .passed(false)
                        .confidence(0.0)
                        .detail("请求失败，状态码: " + response.getStatusCode())
                        .rawData(response.getBodyString())
                        .build();
            }

            String respBody = response.getBodyString();
            JsonObject json = Json.getJsonObject(respBody);
            JsonObject choice = json.getJsonArray("choices").getJsonObject(0);
            String content = (String) choice.getJsonObject("message").getObject("content");'''

new_chat = '''    private ProbeResult probeChat(String dimensionKey, String prompt) {
        String mapped = switch (dimensionKey) {
            case "identity" -> "IDENTITY_PROBE";
            case "jailbreak" -> "JAILBREAK_PROBE";
            case "knowledge_cutoff" -> "KNOWLEDGE_CUTOFF";
            case "safety_alignment" -> "SAFETY_ALIGNMENT";
            case "math_trap" -> "MATH_TRAP";
            default -> dimensionKey.toUpperCase();
        };
        String apiKey = setting.getAppKey();
        String model = resolveModel();
        String body = buildChatBody(model, prompt, DEFAULT_MAX_TOKENS, DEFAULT_TEMPERATURE);

        try {
            long startTime = System.currentTimeMillis();
            ClientResponse response = HttpClientFactory.of(resolveBaseUrl())
                    .header("Authorization", "Bearer " + apiKey)
                    .path("/v1/chat/completions")
                    .json()
                    .body(body)
                    .post();
            long duration = System.currentTimeMillis() - startTime;

            if (!response.isSuccess()) {
                int statusCode = response.getStatusCode();
                double confidence = 0.0;
                String detail = "请求失败，状态码: " + statusCode;
                if (statusCode == 403) {
                    confidence = 0.15;
                    detail = "策略限制(403)，服务可达但拒绝本次请求";
                } else if (statusCode == 404) {
                    confidence = 0.1;
                    detail = "端点不存在(404)，服务可达但路径不匹配";
                }
                return ProbeResult.builder()
                        .dimension(ProbeDimension.valueOf(mapped))
                        .passed(false)
                        .confidence(confidence)
                        .detail(detail)
                        .rawData(response.getBodyString())
                        .build();
            }

            String respBody = response.getBodyString();
            JsonObject json = Json.getJsonObject(respBody);
            if (json.getJsonArray("choices") == null || json.getJsonArray("choices").isEmpty()) {
                return ProbeResult.builder()
                        .dimension(ProbeDimension.valueOf(mapped))
                        .passed(false)
                        .confidence(0.2)
                        .detail("响应缺少 choices 字段")
                        .rawData(respBody)
                        .build();
            }
            JsonObject choice = json.getJsonArray("choices").getJsonObject(0);
            Object contentObj = choice.getJsonObject("message").getObject("content");
            String content = contentObj instanceof String contentStr ? contentStr : null;'''

content = content.replace(old_chat, new_chat)

# 6. Update probeFunctionCalling to use resolveModel
content = content.replace(
    '    private ProbeResult probeFunctionCalling() {\n        String apiKey = setting.getAppKey();\n        String toolsBody = "{\\n" +\n                "  \\"model\\": \\"gpt-4\\",\\n" +',
    '    private ProbeResult probeFunctionCalling() {\n        String apiKey = setting.getAppKey();\n        String model = resolveModel();\n        String toolsBody = "{\\n" +\n                "  \\"model\\": \\"" + escapeJson(model) + "\\",\\n" +'
)

# 7. Update probeFunctionCalling failure handling
old_fc_fail = '''            if (!response.isSuccess()) {
                return ProbeResult.builder()
                        .dimension(ProbeDimension.FUNCTION_CALLING)
                        .passed(false)
                        .confidence(0.0)
                        .detail("请求失败，状态码: " + response.getStatusCode())
                        .rawData(response.getBodyString())
                        .build();
            }'''

new_fc_fail = '''            if (!response.isSuccess()) {
                int statusCode = response.getStatusCode();
                double confidence = 0.0;
                String detail = "请求失败，状态码: " + statusCode;
                if (statusCode == 403) {
                    confidence = 0.2;
                    detail = "策略限制(403)，服务不支持 Function Calling 探测";
                } else if (statusCode == 404) {
                    confidence = 0.2;
                    detail = "端点不存在(404)，服务不支持 Function Calling 探测";
                }
                return ProbeResult.builder()
                        .dimension(ProbeDimension.FUNCTION_CALLING)
                        .passed(false)
                        .confidence(confidence)
                        .detail(detail)
                        .rawData(response.getBodyString())
                        .build();
            }'''

content = content.replace(old_fc_fail, new_fc_fail)

with open('OpenAiProbeStation.java', 'w', encoding='utf-8') as f:
    f.write(content)

print('Done applying all changes')
