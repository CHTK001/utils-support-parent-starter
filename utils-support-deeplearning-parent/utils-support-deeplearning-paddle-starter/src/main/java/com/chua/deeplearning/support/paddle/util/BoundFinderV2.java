package com.chua.deeplearning.support.paddle.util;

import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.Point;
import ai.djl.modality.cv.output.Rectangle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.stream.Collectors;

/**
* OCR 检测掩码连通域框查找。
*
* @author CH
* @since 4.0.0.42
 */
public class BoundFinderV2 {

    /**
    * 四邻域 X 偏移。
     */
    private final int[] deltaX = {0, 1, -1, 0};

    /**
    * 四邻域 Y 偏移。
     */
    private final int[] deltaY = {1, 0, 0, -1};

    /**
    * 连通域点集合。
     */
    private final List<List<Point>> pointsCollection;

    /**
    * 掩码宽。
     */
    private final int width;

    /**
    * 掩码高。
     */
    private final int height;

    /**
    * 最小框宽（像素阈值，相对归一化后反算）。
     */
    private final float limitWidth;

    /**
    * 最小框高。
     */
    private final float limitHeight;

    /**
    * 基于布尔掩码构造。
    *
    * @param grid 2D 掩码
     */
    public BoundFinderV2(boolean[][] grid) {
        this(grid, 5.0f, 1.1f);
    }

    /**
    * 基于布尔掩码构造。
    *
    * @param grid         2D 掩码
    * @param limitWidth   最小宽（像素）
    * @param limitHeight  最小高（像素）
     */
    public BoundFinderV2(boolean[][] grid, float limitWidth, float limitHeight) {
        this.limitWidth = limitWidth;
        this.limitHeight = limitHeight;
        this.pointsCollection = new ArrayList<>();
        this.width = grid.length;
        this.height = grid[0].length;
        boolean[][] visited = new boolean[width][height];
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                if (grid[i][j] && !visited[i][j]) {
                    pointsCollection.add(bfs(grid, i, j, visited));
                }
            }
        }
    }

    /**
    * 计算矩形框列表（归一化坐标）。
    *
    * @return 边界框
     */
    public List<BoundingBox> getBoxes() {
        return pointsCollection.stream()
                .parallel()
                .map(points -> {
                    double[] minMax = {Integer.MAX_VALUE, Integer.MAX_VALUE, -1, -1};
                    points.forEach(p -> {
                        minMax[0] = Math.min(minMax[0], p.getX());
                        minMax[1] = Math.min(minMax[1], p.getY());
                        minMax[2] = Math.max(minMax[2], p.getX());
                        minMax[3] = Math.max(minMax[3], p.getY());
                    });
                    return new Rectangle(
                            minMax[1],
                            minMax[0],
                            minMax[3] - minMax[1],
                            minMax[2] - minMax[0]);
                })
                .filter(rect -> rect.getWidth() * width > limitWidth
                        && rect.getHeight() * height > limitHeight)
                .collect(Collectors.toList());
    }

    /**
    * Bfs
    *
    * @param grid grid
    * @param x x
    * @param y y
    * @param visited visited
    * @return bfs的结果
     */
    private List<Point> bfs(boolean[][] grid, int x, int y, boolean[][] visited) {
        Queue<Point> queue = new ArrayDeque<>();
        queue.offer(new Point(x, y));
        visited[x][y] = true;
        List<Point> points = new ArrayList<>();
        while (!queue.isEmpty()) {
            Point point = queue.poll();
            points.add(new Point(point.getX() / width, point.getY() / height));
            for (int direction = 0; direction < 4; direction++) {
                int newX = (int) point.getX() + deltaX[direction];
                int newY = (int) point.getY() + deltaY[direction];
                if (!isValid(grid, newX, newY, visited)) {
                    continue;
                }
                queue.offer(new Point(newX, newY));
                visited[newX][newY] = true;
            }
        }
        return points;
    }

    /**
    * 是否Valid
    *
    * @param grid grid
    * @param x x
    * @param y y
    * @param visited visited
    * @return 是否valid的结果
     */
    private boolean isValid(boolean[][] grid, int x, int y, boolean[][] visited) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return false;
        }
        if (visited[x][y]) {
            return false;
        }
        return grid[x][y];
    }
}
