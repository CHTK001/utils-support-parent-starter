package com.chua.deeplearning.support.utils;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.types.DataType;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * ndarray 转换工具类，提供 ndarray 与 打开cv Mat、Java 数组之间的双向转换方法
 *
 * @author CH
 * @since 4.0.0.42
 */
public class NDArrayUtils {

    /**
     * 将 打开cv Mat 转换为 mat的point 轮廓对象
     *
     * @param mat 输入的 打开cv Mat 矩阵，每行代表一个点，第一列为 x 坐标，第二列为 y 坐标
     * @return 转换后的 mat的point 轮廓对象
     */
    public static MatOfPoint matToMatOfPoint(Mat mat) {
        int rows = mat.rows();
        MatOfPoint matOfPoint = new MatOfPoint();

        List<Point> list = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            Point point = new Point((float) mat.get(i, 0)[0], (float) mat.get(i, 1)[0]);
            list.add(point);
        }
        matOfPoint.fromList(list);

        return matOfPoint;
    }

    /**
     * 将 float 类型的 ndarray 转换为 float[][] 二维数组
     *
     * @param ndArray 输入的 float 类型 ndarray，要求为二维形状 (rows, cols)
     * @return 转换后的 float[][] 二维数组
     */
    public static float[][] floatNDArrayToArray(NDArray ndArray) {
        int rows = (int) (ndArray.getShape().get(0)); // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
        int cols = (int) (ndArray.getShape().get(1));
        float[][] arr = new float[rows][cols];

        float[] arrs = ndArray.toFloatArray();
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                arr[i][j] = arrs[i * cols + j];
            }
        }
        return arr;
    }

    /**
     * 将 打开cv Mat 转换为 double[][] 二维数组
     *
     * @param mat 输入的单通道 打开cv Mat 矩阵
     * @return 转换后的 double[][] 二维数组
     */
    public static double[][] matToDoubleArray(Mat mat) {
        int rows = mat.rows();
        int cols = mat.cols();

        double[][] doubles = new double[rows][cols];

        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                doubles[i][j] = mat.get(i, j)[0];
            }
        }

        return doubles;
    }

    /**
     * 将 打开cv Mat 转换为 float[][] 二维数组
     *
     * @param mat 输入的单通道 Mat 矩阵
     * @return 转换后的 float[][] 二维数组
     */
    public static float[][] matToFloatArray(Mat mat) {
        int rows = mat.rows();
        int cols = mat.cols();

        float[][] floats = new float[rows][cols];

        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                floats[i][j] = (float) mat.get(i, j)[0];
            }
        }

        return floats;
    }

    /**
     * 将 打开cv Mat 转换为 byte[][] 二维数组（uint8 无符号字节）
     *
     * @param mat 输入的单通道 Mat 矩阵
     * @return 转换后的 byte[][] 二维数组
     */
    public static byte[][] matToUint8Array(Mat mat) {
        int rows = mat.rows();
        int cols = mat.cols();

        byte[][] bytes = new byte[rows][cols];

        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                bytes[i][j] = (byte) mat.get(i, j)[0];
            }
        }

        return bytes;
    }

    /**
     * 将 float 类型的 ndarray 转换为指定 cv类型 的 打开cv Mat
     *
     * @param ndArray 输入的 float 类型 ndarray，形状为二维 (rows, cols)
     * @param cvType  打开cv Mat 的数据类型，如 cv类型.CV_32F
     * @return 转换后的 打开cv Mat 对象
     */
    public static Mat floatNDArrayToMat(NDArray ndArray, int cvType) {
        int rows = (int) (ndArray.getShape().get(0)); // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
        int cols = (int) (ndArray.getShape().get(1));
        Mat mat = new Mat(rows, cols, cvType);

        float[] arrs = ndArray.toFloatArray();
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                mat.put(i, j, arrs[i * cols + j]);
            }
        }
        return mat;
    }

    /**
     * 将 float 类型的 ndarray 转换为 CV_32F 类型的 打开cv Mat
     *
     * @param ndArray 输入的 float 类型 ndarray，形状为二维 (rows, cols)
     * @return 转换后的 CV_32F 类型 Mat 对象
     */
    public static Mat floatNDArrayToMat(NDArray ndArray) {
        int rows = (int) (ndArray.getShape().get(0)); // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
        int cols = (int) (ndArray.getShape().get(1));
        Mat mat = new Mat(rows, cols, CvType.CV_32F);

        float[] arrs = ndArray.toFloatArray();
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                mat.put(i, j, arrs[i * cols + j]);
            }
        }

        return mat;

    }

    /**
     * 将 uint8（无符号 8 位整数）类型的 ndarray 转换为 CV_8U 类型的 打开cv Mat
     *
     * @param ndArray 输入的 uint8 类型 ndarray，形状为二维 (rows, cols)
     * @return 转换后的 CV_8U 类型 Mat 对象
     */
    public static Mat uint8NDArrayToMat(NDArray ndArray) {
        int rows = (int) (ndArray.getShape().get(0)); // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
        int cols = (int) (ndArray.getShape().get(1));
        Mat mat = new Mat(rows, cols, CvType.CV_8U);

        byte[] arrs = ndArray.toByteArray();

        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                mat.put(i, j, arrs[i * cols + j]);
            }
        }
        return mat;
    }

    /**
     * 将 float[][] 二维数组转换为 CV_32F 类型的 打开cv Mat
     *
     * @param arr 输入的 float[][] 二维数组
     * @return 转换后的 CV_32F 类型 Mat 对象
     */
    public static Mat floatArrayToMat(float[][] arr) {
        int rows = arr.length;
        int cols = arr[0].length;
        Mat mat = new Mat(rows, cols, CvType.CV_32F);

        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                mat.put(i, j, arr[i][j]);
            }
        }

        return mat;
    }

    /**
     * 将 byte[][] 二维数组转换为 CV_8U 类型的 打开cv Mat
     *
     * @param arr 输入的 byte[][] 二维数组
     * @return 转换后的 CV_8U 类型 Mat 对象
     */
    public static Mat uint8ArrayToMat(byte[][] arr) {
        int rows = arr.length;
        int cols = arr[0].length;
        Mat mat = new Mat(rows, cols, CvType.CV_8U);

        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                mat.put(i, j, arr[i][j]);
            }
        }

        return mat;
    }

    /**
     * 将 DJL 关键点列表转换为 CV_32F 类型的 Mat（每行为一个点的 (x, y) 坐标）
     *
     * @param points DJL 关键点列表
     * @return 转换后的 Mat 对象，形状为 (points.大小(), 2)，类型为 CV_32F
     */
    public static Mat toMat(List<ai.djl.modality.cv.output.Point> points) {
        Mat mat = new Mat(points.size(), 2, CvType.CV_32F);
        for (int i = 0; i < points.size(); i++) {
            ai.djl.modality.cv.output.Point point = points.get(i);
            mat.put(i, 0, (float) point.getX());
            mat.put(i, 1, (float) point.getY());
        }

        return mat;
    }

    /**
     * 安全地将 ndarray 转换为 float[] 数组，自动处理 ONNX Runtime 数据类型兼容问题
     *
     * <p>部分 ONNX 运行时输出的 NDArray 内部为 {@code Object[]} 格式（INT64 / FLOAT64 等数据类型），
     * DJL 的 {@code toFloatArray()} 方法可能抛出 {@link java.lang.ArrayStoreException}。
     * 本方法捕获异常后，先将数组转换为 FLOAT32 类型再重试。</p>
     *
     * @param array 输入的 ndarray 对象
     * @return 转换后的 float[] 数组
     */
    public static float[] safeToFloatArray(NDArray array) {
        try {
            return array.toFloatArray();
        } catch (Exception e) {
            // ONNX 运行时输出为 Object[] 时，DJL 的 toFloatArray() 会失败
 // 通过 系统.arraycopy 将 FLOAT64 转为 FLOAT32 时可能触发 array存储异常
            // 此处先转换为 FLOAT32 类型再尝试获取
            NDArray floatArray = array.toType(DataType.FLOAT32, true);
            return floatArray.toFloatArray();
        }
    }
}
