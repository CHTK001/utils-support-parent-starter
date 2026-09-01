package com.chua.deeplearning.support.onnx.florence2;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Florence-2 视觉理解翻译器，支持多种图像理解任务。
 *
 * <p>使用 ONNX Runtime 加载 Florence-2 模型，
 * 支持 caption、detailed caption、OCR、目标检测等任务类型。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class Florence2Translator implements ITranslator<Object[], String> {
    private static final Logger log = LoggerFactory.getLogger(Florence2Translator.class);
    private static final String NAME = "florence2";
    private static final int IMAGE_SIZE = 768;
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};
    private static final int ENCODER_SEQ_LEN = 577;
    private static final int NUM_LAYERS = 6;
    private static final int NUM_HEADS = 12;
    private static final int HEAD_DIM = 64;
    private static final int HIDDEN_SIZE = 768;
    private static final int VOCAB_SIZE = 51289;