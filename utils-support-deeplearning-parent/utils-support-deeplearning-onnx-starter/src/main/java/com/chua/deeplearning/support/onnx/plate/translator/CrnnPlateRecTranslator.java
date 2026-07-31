package com.chua.deeplearning.support.onnx.plate.translator;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.plate.PlateResult;

import java.util.ArrayList;
import java.util.List;


/**
 * CRNN              Translator
 * <p>
 *        SmartJavaAI     CRNNPlateRecTranslator          
 *                                                          
 * </p>
 *
 * @author CH
 * @since 2025-01-20
 */
public class CrnnPlateRecTranslator implements Translator<Image, PlateResult> {

    private static final String PLATE_NAME = "#                                                                                                                           0123456789ABCDEFGHJKLMNPQRSTUVWXYZ      ";
    private static final String[] PLATE_COLORS = {"      ", "      ", "      ", "      ", "      "};
    private static final float MEAN = 0.588f;
    private static final float STD = 0.193f;
    private static final int INPUT_W = 168;
    private static final int INPUT_H = 48;
    private static final String PROVINCES = "                                                                                             ";

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDManager manager = ctx.getNDManager();

        // Resize to (168, 48)
        NDArray array = input.toNDArray(manager, Image.Flag.COLOR);
        array = NDImageUtils.resize(array, INPUT_W, INPUT_H);

        // Normalize
        array = array.toType(DataType.FLOAT32, false)
                .div(255f)
                .sub(MEAN)
                .div(STD);

        // HWC -> CHW
        array = array.transpose(2, 0, 1);
        array = array.expandDims(0);

        return new NDList(array);
    }

    @Override
    public PlateResult processOutput(TranslatorContext ctx, NDList list) {
        //                         plate logits + color logits
        // (0);  // shape: [1, T, num_classes]
        NDArray plateOutput = list.get(0);  // shape: [1, T, num_classes]
        // ull;  // shape: [1, num_colors]
        NDArray colorOutput = list.size() > 1 ? list.get(1) : null;

        int[] plateIdx = plateOutput.argMax(-1)
                .toType(DataType.INT32, false)
                .toIntArray();

        String plateNo = decodePlate(plateIdx);
        String plateColor = "      ";

        if (colorOutput != null && !colorOutput.isEmpty()) {
            int colorIdx = colorOutput.argMax(1).toType(DataType.INT32, false).toIntArray()[0];
            if (colorIdx >= 0 && colorIdx < PLATE_COLORS.length) {
                plateColor = PLATE_COLORS[colorIdx];
            }
        }

        plateNo = normalizePlateText(plateNo);
        return new PlateResult(plateNo, plateColor);
    }

    private String decodePlate(int[] preds) {
        int pre = 0;
        List<Integer> newPreds = new ArrayList<>();
        for (int idx : preds) {
            if (idx != 0 && idx != pre) {
                newPreds.add(idx);
            }
            pre = idx;
        }

        StringBuilder sb = new StringBuilder();
        for (int i : newPreds) {
            if (i >= 0 && i < PLATE_NAME.length()) {
                sb.append(PLATE_NAME.charAt(i));
            }
        }
        return sb.toString();
    }

    private String normalizePlateText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        String cleaned = raw.replaceAll("[^\\u4e00-\\u9fa5A-Z0-9]", "");
        if (cleaned.isBlank()) {
            return "";
        }

        int provinceIndex = findProvinceIndex(cleaned);
        if (provinceIndex > 0) {
            cleaned = cleaned.substring(provinceIndex);
        }

        if (cleaned.length() >= 2 && isProvince(cleaned.charAt(0)) && isProvince(cleaned.charAt(1))) {
            cleaned = cleaned.substring(1);
        }

        if (!cleaned.isEmpty() && !isProvince(cleaned.charAt(0))) {
            int firstLatinOrDigit = -1;
            for (int i = 0; i < cleaned.length(); i++) {
                char c = cleaned.charAt(i);
                if (isProvince(c)) {
                    cleaned = cleaned.substring(i);
                    break;
                }
                if (firstLatinOrDigit < 0 && (Character.isUpperCase(c) || Character.isDigit(c))) {
                    firstLatinOrDigit = i;
                }
            }
            if (!cleaned.isEmpty() && !isProvince(cleaned.charAt(0)) && firstLatinOrDigit > 0) {
                cleaned = cleaned.substring(firstLatinOrDigit);
            }
        }

        if (cleaned.length() > 8) {
            cleaned = cleaned.substring(0, 8);
        }
        return cleaned;
    }

    private int findProvinceIndex(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (isProvince(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private boolean isProvince(char c) {
        return PROVINCES.indexOf(c) >= 0;
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}


