package com.chua.deeplearning.support.utils;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDArrays;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;

import java.util.ArrayList;
import java.util.List;


/**
   * NMS 非极大值抑制工具类，提供基于 ndarray 的 NMS、MTCNN NMS 以及批量 NMS 实现
 *
 * @author CH
 * @since 4.0.0.42
 */
public class NMSUtils {

    /**
      * 标准 NMS 非极大值抑制，基于 candidate bounding boxes 的置信度分数和 iou 阈值进行筛选
     *
     * @param boxes       候选边界框 ndarray，形状为 (N, 4)，格式为 [x1, y1, x2, y2]
     * @param scores      置信度分数 ndarray，形状为 (N,) 或 (N, 1)，每个候选框对应一个分数
     * @param iouThreshold iou 重叠阈值，超过此值的框将被抑制
     * @return 保留的候选框索引数组
     */
    public static int[] nms(NDArray boxes, NDArray scores, float iouThreshold) {
        if (boxes.isEmpty()) {
            return new int[0];
        }

        NDArray x1 = boxes.get(":, 0");
        NDArray y1 = boxes.get(":, 1");
        NDArray x2 = boxes.get(":, 2");
        NDArray y2 = boxes.get(":, 3");

        NDArray areas = x2.sub(x1).add(1).mul(y2.sub(y1).add(1));

        // 按置信度分数降序排列
        NDArray order = scores.argSort().flip(0);

        List<Integer> keep = new ArrayList<>();

        while (order.size() > 0) {
            int idx = (int) order.getLong(0);
            keep.add(idx);

            if (order.size() == 1) {
                break;
            }

            NDArray currentBox = boxes.get(idx);
            NDArray others = boxes.get(order);

            NDArray xx1 = x1.get(order).maximum(x1.get(idx));
            NDArray yy1 = y1.get(order).maximum(y1.get(idx));
            NDArray xx2 = x2.get(order).minimum(x2.get(idx));
            NDArray yy2 = y2.get(order).minimum(y2.get(idx));

            NDArray w = xx2.sub(xx1).add(1).maximum(0);
            NDArray h = yy2.sub(yy1).add(1).maximum(0);
            NDArray inter = w.mul(h);

            NDArray remAreas = areas.get(order);
            NDArray union = remAreas.add(areas.get(idx)).sub(inter);
            NDArray iou = inter.div(union);

            NDArray mask = iou.lte(iouThreshold);
            order = order.get(mask);
        }
        return keep.stream().mapToInt(i -> i).toArray();
    }

    /**
      * 批量 NMS，按 批量 标识 分组执行 NMS，每组的用例 标识 通过全局索引返回为单个 ndarray
     *
     * @param boxes 待筛选的边界框 ndarray，形状为 (N, 4)，格式为 [x1, y1, x2, y2]
     * @param scores 置信度分数 ndarray，形状为 (N,) 或 (N, 1)，每个框对应一个分数
     * @param idxs 每个框对应的 批量 标识 ndarray，形状为 (N,)
     * @param iouThreshold iou 阈值，超过此值的框将被抑制
     * @param manager nd管理器 用于创建临时 ndarray
     * @return 保留的候选框全局索引 ndarray
     */
    public static NDArray batchedNms(NDArray boxes, NDArray scores, NDArray idxs, float iouThreshold, NDManager manager) {
        List<NDArray> keepList = new ArrayList<>();

 // 获取唯一的 批量 标识 列表
        NDArray uniqueIdxs = idxs.unique().get(0);

        for (long batchId : uniqueIdxs.toLongArray()) {
 // 筛选出属于当前 批量 的框
            NDArray mask = idxs.eq(batchId);
            NDArray batchBoxes = boxes.get(mask);
            NDArray batchScores = scores.get(mask);

 // 对当前 批量 执行 NMS
            int[] keepIndices = mtcnnNms(batchBoxes, batchScores, iouThreshold);

            if (keepIndices.length > 0) {
 // 将 批量 内索引映射为全局索引
                NDArray globalIndices = manager.arange(boxes.getShape().get(0))
                        .get(mask)
                        .toType(DataType.INT64, false)
                        .get(manager.create(keepIndices));

                keepList.add(globalIndices);
            }
        }

        if (keepList.isEmpty()) {
            return manager.create(new long[0]);
        }
        return NDArrays.concat(new NDList(keepList));
    }

    /**
      * MTCNN 专用的 NMS 变体，按置信度升序处理，采用面积最小值计算 iou
     *
     * @param boxes 待筛选的边界框 ndarray，形状为 (N, 4)，格式为 [x1, y1, x2, y2]
     * @param scores 置信度分数 ndarray，形状为 (N,) 或 (N, 1)，每个框对应一个分数
     * @param iouThreshold iou 阈值，超过此值的框将被抑制
     * @return 保留的候选框索引数组
     */
    public static int[] mtcnnNms(NDArray boxes, NDArray scores, float iouThreshold) {
        if (boxes.isEmpty()) {
            return new int[0];
        }

        NDArray x1 = boxes.get(":, 0");
        NDArray y1 = boxes.get(":, 1");
        NDArray x2 = boxes.get(":, 2");
        NDArray y2 = boxes.get(":, 3");

        // 计算候选框面积
        NDArray areas = x2.sub(x1).add(1).mul(y2.sub(y1).add(1));

        // 按分数升序排列
        NDArray order = scores.argSort();

        List<Integer> keep = new ArrayList<>();

        while (order.size() > 0) {
            int i = (int) order.getLong(-1);
            keep.add(i);

            if (order.size() == 1) {
                break;
            }

            // 取除了最后一个元素以外的所有索引
            NDArray idx = order.get("0:-1");

            NDArray xx1 = x1.get(i).maximum(x1.get(idx));
            NDArray yy1 = y1.get(i).maximum(y1.get(idx));
            NDArray xx2 = x2.get(i).minimum(x2.get(idx));
            NDArray yy2 = y2.get(i).minimum(y2.get(idx));

            NDArray w = xx2.sub(xx1).add(1).maximum(0);
            NDArray h = yy2.sub(yy1).add(1).maximum(0);
            NDArray inter = w.mul(h);

            NDArray union = areas.get(i).minimum(areas.get(idx));
            NDArray iou = inter.div(union);

            // 只保留 IoU <= 阈值的结果
            NDArray mask = iou.lte(iouThreshold);

            // 更新候选框顺序
            order = idx.get(mask);
        }

        return keep.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Nms
     * @param boxes boxes
     * @param probabilities probabilities
     * @param nmsThreshold nms阈值
     * @param i2 i2
     * @param box2 box2
     * @param nmsThreshold nms阈值
     * @param rect1 rect1
     * @param rect2 rect2
     */
    public static List<Integer> nms(
            List<ai.djl.modality.cv.output.BoundingBox> boxes,
            List<Double> probabilities,
            float nmsThreshold) {
        List<Integer> indices = new ArrayList<>();
        if (boxes.isEmpty()) {
            return indices;
        }

        List<Integer> sorted = new ArrayList<>();
        for (int i = 0; i < probabilities.size(); i++) {
            sorted.add(i);
        }
        sorted.sort((i1, i2) -> Double.compare(probabilities.get(i2), probabilities.get(i1)));

        boolean[] suppressed = new boolean[boxes.size()];
        for (int idx : sorted) {
            if (suppressed[idx]) {
                continue;
            }
            indices.add(idx);
            ai.djl.modality.cv.output.Rectangle box1 = boxes.get(idx).getBounds();
            for (int j = 0; j < boxes.size(); j++) {
                if (j == idx || suppressed[j]) {
                    continue;
                }
                ai.djl.modality.cv.output.Rectangle box2 = boxes.get(j).getBounds();
                double iou = calculateIoU(box1, box2);
                if (iou > nmsThreshold) {
                    suppressed[j] = true;
                }
            }
        }
        return indices;
    }

    /**
      * calculateiou
     * @param rect1 rect1
     * @param rect2 rect2
     */
    private static double calculateIoU(
            ai.djl.modality.cv.output.Rectangle rect1,
            ai.djl.modality.cv.output.Rectangle rect2) {
        double x1 = Math.max(rect1.getX(), rect2.getX());
        double y1 = Math.max(rect1.getY(), rect2.getY());
        double x2 = Math.min(rect1.getX() + rect1.getWidth(), rect2.getX() + rect2.getWidth());
        double y2 = Math.min(rect1.getY() + rect1.getHeight(), rect2.getY() + rect2.getHeight());

        double intersection = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        double union = rect1.getWidth() * rect1.getHeight() + rect2.getWidth() * rect2.getHeight() - intersection;
        return union > 0 ? intersection / union : 0;
    }
}