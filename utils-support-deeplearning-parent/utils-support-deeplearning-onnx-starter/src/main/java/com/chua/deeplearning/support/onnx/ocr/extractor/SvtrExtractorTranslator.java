package com.chua.deeplearning.support.onnx.ocr.extractor;

import ai.djl.Model;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.index.NDIndex;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import ai.djl.util.Utils;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;


/**
 * SVTR                    Translator
 * <p>
 * SVTR (Scene Text Recognition)                 Transformer                                  
 *        OpenOCR              SVTR       
 * </p>
 * <p>
 *              Translator                                                                                        
 * </p>
 * <p>
 *                
 * -        Transformer                               
 * -                                  
 * -                                  
 * </p>
 *
 * @author CH
 * @since 2025-02-01
 */
@Slf4j
@Spi("svtr_rec")
public class SvtrExtractorTranslator implements Translator<Image, String> {

    /**
     *                              
     */
    private List<String> table;

    /**
     *                                             
     */
    private final boolean useSpaceChar;

    /**
     *                   
     */
    private final String batchifier;

    /**
     * OpenOCR                 ONNX                 48   
     */
    private static final int DEFAULT_IMG_HEIGHT = 48;

    /**
     *                      SVTR        256   
     */
    private static final int DEFAULT_IMG_WIDTH = 256;

    /**
     *              
     *
     * @param arguments             
     */
    public SvtrExtractorTranslator(Map<String, ?> arguments) {
        useSpaceChar = arguments.containsKey("use_space_char")
                ? Boolean.parseBoolean(arguments.get("use_space_char").toString())
                : true;
        batchifier = arguments.containsKey("batchifier")
                ? arguments.get("batchifier").toString()
                : "padding";
    }

    /**
     *                   
     */
    public SvtrExtractorTranslator() {
        this(Map.of());
    }

    /**
     *                
     * <p>
     *                         
     * </p>
     *
     * @param ctx                   
     * @throws IOException                         
     */
    @Override
    public void prepare(@Nonnull TranslatorContext ctx) throws IOException {
        Model model = ctx.getModel();
        try {
            table = readVocab(model);
            table.add(0, "blank");
            if (useSpaceChar) {
                table.add(" ");
                table.add(" ");
            } else {
                table.add("");
                table.add("");
            }
            log.debug("[SVTR][            ]                               : {}", table.size());
        } catch (Exception e) {
            log.warn("[SVTR][            ]                                ASCII       : {}",
                    e.getMessage());
            table = buildAsciiFallbackTable();
            table.add(0, "blank");
            table.add(" ");
            table.add(" ");
        }
    }

    /**
     *                   
     * <p>
     *                                                    
     * 1.                                        
     * 2.              [0, 1]       
     * 3.           CHW       
     * </p>
     *
     * @param ctx                     
     * @param input             
     * @return              NDList
     */
    @Override
    @Nonnull
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Image input) {
        NDArray img = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        int imgC = 3;
        int imgH = DEFAULT_IMG_HEIGHT;
        int imgW = DEFAULT_IMG_WIDTH;

        float maxWhRatio = (float) imgW / (float) imgH;

        int h = input.getHeight();
        int w = input.getWidth();
        float whRatio = (float) w / (float) h;

        maxWhRatio = Math.max(maxWhRatio, whRatio);
        imgW = (int) (imgH * maxWhRatio);

        int resizedW;
        if (Math.ceil(imgH * whRatio) > imgW) {
            resizedW = imgW;
        } else {
            resizedW = (int) (Math.ceil(imgH * whRatio));
        }

        NDArray resizedImage = NDImageUtils.resize(img, resizedW, imgH);
        resizedImage = resizedImage.transpose(2, 0, 1).toType(DataType.FLOAT32, false);
        resizedImage.divi(255f).subi(0.5f).divi(0.5f);

        NDArray paddingIm = ctx.getNDManager().zeros(new Shape(imgC, imgH, imgW), DataType.FLOAT32);
        paddingIm.set(new NDIndex(":,:,0:" + resizedW), resizedImage);

        paddingIm = paddingIm.flip(0);
        return new NDList(paddingIm);
    }

    /**
     *                   
     * <p>
     *                    token                                  
     * 1.                                      token
     * 2.                       token
     * 3.        blank token
     * 4.                            
     * </p>
     *
     * @param ctx                    
     * @param list                 NDList
     * @return                            
     * @throws IOException                   
     */
    @Override
    @Nonnull
    public String processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) throws IOException {
        if (table == null || table.isEmpty()) {
            log.warn("[SVTR][            ]                                     ");
            return "";
        }

        StringBuilder sb = new StringBuilder();
        NDArray tokens = list.singletonOrThrow();
        if (tokens.getShape().dimension() == 3 && tokens.getShape().get(0) == 1) {
            tokens = tokens.squeeze(0);
        }

        long[] indices = tokens.argMax(1).toLongArray();
        boolean[] selection = new boolean[indices.length];
        Arrays.fill(selection, true);

        for (int i = 1; i < indices.length; i++) {
            if (indices[i] == indices[i - 1]) {
                selection[i] = false;
            }
        }

        int lastIdx = 0;
        for (int i = 0; i < indices.length; i++) {
            if (selection[i] && indices[i] > 0 && !(i > 0 && indices[i] == lastIdx)) {
                int idx = (int) indices[i];
                if (idx < table.size()) {
                    sb.append(table.get(idx));
                }
            }
            lastIdx = (int) indices[i];
        }

        String result = sb.toString();
        log.debug("[SVTR][            ]             : {}", result);
        return result;
    }

    /**
     *                           
     *
     * @return             
     */
    @Override
    @Nullable
    public Batchifier getBatchifier() {
        return Batchifier.fromString(batchifier);
    }

    private List<String> readVocab(Model model) throws IOException {
        IOException lastError = null;
        for (String artifact : List.of("dict.txt", "vocab.txt", "ppocr_keys_v1.txt")) {
            try (InputStream is = model.getArtifact(artifact).openStream()) {
                return Utils.readLines(is, true);
            } catch (IOException e) {
                lastError = e;
            }
        }
        for (Path candidate : resolveFallbackVocabPaths(model.getModelPath())) {
            if (candidate == null || !Files.isRegularFile(candidate)) {
                continue;
            }
            try (InputStream is = Files.newInputStream(candidate)) {
                log.info("[SVTR][            ]                   : {}", candidate);
                return Utils.readLines(is, true);
            } catch (IOException e) {
                lastError = e;
            }
        }
        throw lastError == null ? new IOException("Unable to load OCR vocab artifact") : lastError;
    }

    private List<Path> resolveFallbackVocabPaths(Path modelPath) {
        Path modelDir = resolveModelDir(modelPath);
        if (modelDir == null) {
            return List.of();
        }

        List<Path> candidates = new ArrayList<>();
        for (String artifact : List.of("dict.txt", "vocab.txt", "ppocr_keys_v1.txt")) {
            candidates.add(modelDir.resolve(artifact));
        }

        Path recognitionRoot = modelDir.getParent();
        if (recognitionRoot != null) {
            candidates.add(recognitionRoot.resolve("ch_PP-OCRv4_rec_infer").resolve("dict.txt"));
            candidates.add(recognitionRoot.resolve("PP-OCRv4_mobile_rec_infer").resolve("dict.txt"));
            candidates.add(recognitionRoot.resolve("PP-OCRv4_server_rec_infer").resolve("dict.txt"));
            candidates.add(recognitionRoot.resolve("ch_PP-OCRv5_rec_infer").resolve("ppocr_keys_v1.txt"));
        }
        return candidates;
    }

    private Path resolveModelDir(Path modelPath) {
        if (modelPath == null) {
            return null;
        }
        if (Files.isDirectory(modelPath)) {
            return modelPath;
        }
        return modelPath.getParent();
    }

    private List<String> buildAsciiFallbackTable() {
        List<String> fallback = new ArrayList<>();
        for (char c : "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray()) {
            fallback.add(String.valueOf(c));
        }
        return fallback;
    }
}
