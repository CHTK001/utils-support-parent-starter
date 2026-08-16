package com.chua.deeplearning.support.pytorch.example;

import ai.djl.Model;
import ai.djl.inference.Predictor;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * parsenet 最小复现测试。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ParseNetMinimalTest {

    private ParseNetMinimalTest() {
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        byte[] img = Files.readAllBytes(Path.of("G:\\images\\output\\face0_restore.png"));
        Path modelPath = Path.of("G:\\work\\models\\pytorch\\face\\segmentation\\parsenet_traced_model.pt");
        Path parent = modelPath.getParent();
        String name = modelPath.getFileName().toString();
        name = name.substring(0, name.lastIndexOf('.'));

        for (int variant = 0; variant < 2; variant++) {
            try (Model model = Model.newInstance("parsenet", "PyTorch")) {
                model.load(parent, name);
                Translator<Image, Image> tr = new MyTranslator(variant);
                Predictor<Image, Image> predictor = model.newPredictor(tr);
                try {
                    Image out = predictor.predict(ImageFactory.getInstance().fromInputStream(new java.io.ByteArrayInputStream(img)));
                    System.out.println("variant=" + variant + " OK out=" + out.getWidth() + "x" + out.getHeight());
                } catch (Exception e) {
                    System.out.println("variant=" + variant + " ERR " + e.getMessage());
                }
            }
        }
    }

    static final class MyTranslator implements Translator<Image, Image> {
        private final int variant;

        MyTranslator(int variant) {
            this.variant = variant;
        }

        @Override
        public NDList processInput(TranslatorContext ctx, Image input) {
            NDManager manager = ctx.getNDManager();
            NDArray array = input.toNDArray(manager).toType(DataType.FLOAT32, false);
            array = array.transpose(2, 0, 1).div(255.0f);
            NDArray mean = manager.create(new float[]{0.5f, 0.5f, 0.5f}, new Shape(3, 1, 1));
            NDArray std = manager.create(new float[]{0.5f, 0.5f, 0.5f}, new Shape(3, 1, 1));
            array = array.sub(mean).div(std);
            if (variant == 1) {
                array = array.expandDims(0);
                System.out.println("feeding shape=" + array.getShape());
            } else {
                System.out.println("feeding shape=" + array.getShape());
            }
            return new NDList(array);
        }

        @Override
        public Image processOutput(TranslatorContext ctx, NDList list) {
            NDArray out = list.get(0);
            System.out.println("out shape=" + out.getShape());
            return null;
        }

        @Override
        public Batchifier getBatchifier() {
            return Batchifier.STACK;
        }
    }
}