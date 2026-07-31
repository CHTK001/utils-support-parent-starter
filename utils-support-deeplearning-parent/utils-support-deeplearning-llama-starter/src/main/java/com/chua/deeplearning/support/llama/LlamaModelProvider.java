package com.chua.deeplearning.support.llama;

import com.chua.deeplearning.support.engine.BulkModelProvider;
import com.chua.deeplearning.support.llama.translator.*;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.translator.TranslatorModelDefinition;
import com.chua.common.support.ai.chat.ModelDefinition;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class LlamaModelProvider implements BulkModelProvider {

    private static final String PROVIDER = "llama";

    @Override
    public TranslatorModelDefinition getDefinition() {
        List<TranslatorModelDefinition> all = getAll();
        return all.isEmpty() ? null : all.get(0);
    }

    @Override
    public List<TranslatorModelDefinition> getAll() {
        List<TranslatorModelDefinition> list = new ArrayList<>();

        list.add(model("minicpm5", "MiniCPM5-1B Thinking LLM",
                "models/llama/MiniCPM5-1B-Claude-Opus-Fable5-V2-Thinking-Q8_0.gguf",
                "https://huggingface.co/GnLOLot/MiniCPM5-1B-Claude-Opus-Fable5-V2-Thinking-GGUF/resolve/main/MiniCPM5-1B-Claude-Opus-Fable5-V2-Thinking-Q8_0.gguf",
                new MiniCpm5Translator()));

        list.add(model("embeddinggemma-300m", "EmbeddingGemma-300M text embedding",
                "models/llama/embeddinggemma-300m-qat-Q8_0.gguf",
                "https://huggingface.co/ggml-org/embeddinggemma-300m-qat-q8_0-GGUF/resolve/main/embeddinggemma-300m-qat-Q8_0.gguf",
                new EmbeddingGemmaTranslator()));

        list.add(model("otzaria-embedding", "Otzaria-Embedding-V1-Flash-0.6B text embedding",
                "models/llama/Otzaria-Embedding-V1-Flash-0.6B-Q8_0.gguf",
                "https://huggingface.co/EMD123/Otzaria-Embedding-V1-Flash-0.6B-GGUF/resolve/main/Otzaria-Embedding-V1-Flash-0.6B-Q8_0.gguf",
                new OtzariaEmbeddingTranslator()));

        list.add(model("neutts-2e", "NeuTts-2E expressive text-to-speech",
                "models/llama/neutts-2e-Q4_0.gguf",
                "https://huggingface.co/neuphonic/neutts-2e-q4-gguf/resolve/main/neutts-2e-Q4_0.gguf",
                new NeuTts2eTranslator()));

        list.add(model("gemma-4-e2b", "Gemma-4-E2B any-to-any multimodal",
                "models/llama/gemma-4-E2B_q4_0-it.gguf",
                "https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf/resolve/main/gemma-4-E2B_q4_0-it.gguf",
                new Gemma4Translator()));

        list.add(model("bitnet-embedding", "BitNet-Embedding-0.6B text embedding",
                "models/llama/bitnet-embeddings-0.6b-bf16-i2_s.gguf",
                "https://huggingface.co/microsoft/bitnet-embedding-0.6b/resolve/main/bitnet-embeddings-0.6b-bf16-i2_s.gguf",
                new BitnetEmbeddingTranslator()));

        log.info("[Llama] registered {} models", list.size());
        return list;
    }

    private TranslatorModelDefinition model(String id, String desc, String path, String url, AutoCloseable translator) {
        return TranslatorModelDefinition.builder()
                .modelDefinition(ModelDefinition.builder()
                        .id(id).name(id).provider(PROVIDER)
                        .description(PROVIDER + ": " + desc)
                        .build())
                .translator(translator instanceof ITranslator<?, ?> t ? t : null)
                .config(Map.of("path", path, "downloadUrl", url))
                .build();
    }
}