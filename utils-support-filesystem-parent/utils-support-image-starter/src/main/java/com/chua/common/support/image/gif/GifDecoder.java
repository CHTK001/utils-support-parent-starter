package com.chua.common.support.image.gif;

import com.chua.common.support.reflection.ReflectUtils;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * GIF 图像解码器。
 * <p>
 * 用于对 GIF 格式的图像流进行解码，支持：
 * <ul>
 *     <li>逐帧读取动画的每一帧画面；</li>
 *     <li>提取每一帧的播放延迟时间（单位：百分之一秒）；</li>
 *     <li>解析 Netscape 扩展中的循环播放次数。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class GifDecoder {

    /**
     * 已解码的帧画面列表。
     */
    protected List<BufferedImage> frames = new ArrayList<>();

    /**
     * 每一帧对应的延迟时间列表，单位为百分之一秒，默认 10（即 100 毫秒）。
     */
    protected List<Integer> delays = new ArrayList<>();

    /**
     * 循环播放次数，0 表示无限循环。
     */
    protected int loopCount = 0;

    /**
     * 整个 GIF 图像的最大宽度。
     */
    protected int width = 0;

    /**
     * 整个 GIF 图像的最大高度。
     */
    protected int height = 0;

    /**
     * 标记是否已成功完成读取。
     */
    protected boolean read = false;

    /**
     * 从输入流中读取并解析 GIF 图像。
     * <p>
     * 解析完成后可通过 {@link #getFrame(int)}、{@link #getDelay(int)} 等方法获取帧画面与延迟信息。
     * </p>
     *
     * @param is 待读取的 GIF 输入流，不能为空
     * @throws IOException 当输入流读取失败时抛出
     */
    public void read(InputStream is) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(is)) {
            if (iis == null) {
                return;
            }

            var readers = ImageIO.getImageReadersByFormatName("GIF");
            if (!readers.hasNext()) {
                return;
            }

            var reader = readers.next();
            reader.setInput(iis, true);

            int numImages = reader.getNumImages(true);
            for (int i = 0; i < numImages; i++) {
                readFrame(reader, i);
            }

            readLoopCount(reader);
            read = true;
            reader.dispose();
        }
    }

    /**
     * 读取并解析指定索引的单帧画面，同时记录该帧的延迟时间。
     *
     * @param reader     图像读取器
     * @param frameIndex 帧索引，从 0 开始
     */
    private void readFrame(ImageReader reader, int frameIndex) throws IOException {
        BufferedImage frame = reader.read(frameIndex);
        frames.add(frame);
        width = Math.max(width, frame.getWidth());
        height = Math.max(height, frame.getHeight());

        int delay = readFrameDelay(reader, frameIndex);
        delays.add(delay);
    }

    /**
     * 提取指定帧的延迟时间（百分之一秒）。
     * <p>
     * 当元数据缺失或解析失败时，默认返回 10（即 100 毫秒）。
     * </p>
     *
     * @param reader     图像读取器
     * @param frameIndex 帧索引
     * @return 延迟时间，单位为百分之一秒
     */
    private int readFrameDelay(ImageReader reader, int frameIndex) {
        try {
            var metadata = reader.getImageMetadata(frameIndex);
            if (metadata == null) {
                return 10;
            }
            String nativeFormat = null;
            try {
                nativeFormat = (String) ReflectUtils.invoke(metadata, "getNativeMetadataFormatName", Object.class);
            } catch (Exception ignored) {
 // Java 25+: 获取natmetadata格式化名称 移除
                nativeFormat = "javax_imageio_gif_image_1.0";
            }
            if (nativeFormat == null) {
                return 10;
            }

            Object node = metadata.getAsTree(nativeFormat);
            if (!(node instanceof IIOMetadataNode iieNode)) {
                return 10;
            }

            IIOMetadataNode gce = findNodeByName(iieNode, "graphicControlExtension");
            if (gce == null) {
                return 10;
            }

            String delayStr = gce.getAttribute("delayTime");
            if (delayStr == null || delayStr.isEmpty()) {
                return 10;
            }

            return Integer.parseInt(delayStr);
        } catch (Exception e) {
            return 10;
        }
    }

    /**
     * 从 GIF 全局元数据中解析循环播放次数。
     * <p>
      * 循环次数来源于 Netscape 扩展（NETSCAPE2.0）中 application延伸 节点的 数据 字段，
     * 字节序列为 {@code [0x01, low, high]}，其中第 3 个字节表示循环次数。
     * </p>
     *
     * @param reader 图像读取器
     */
    private void readLoopCount(ImageReader reader) {
        try {
            Object node = reader.getImageMetadata(0).getAsTree("javax_imageio_gif_image_1.0");
            if (!(node instanceof IIOMetadataNode root)) {
                return;
            }

            IIOMetadataNode appExts = findNodeByName(root, "applicationExtensions");
            if (appExts == null) {
                return;
            }

            var childNodes = appExts.getChildNodes();
            for (int i = 0; i < childNodes.getLength(); i++) {
                IIOMetadataNode child = (IIOMetadataNode) childNodes.item(i);
                if (child == null) {
                    continue;
                }

                String data = child.getAttribute("data");
                if (data == null || data.isEmpty()) {
                    continue;
                }

                byte[] bytes = hexStringToBytes(data);
                if (bytes.length >= 3 && bytes[0] == 0x01) {
                    loopCount = bytes[2] & 0xFF;
                }
                break;
            }
        } catch (Exception ignored) {
            // 忽略循环次数解析失败，不影响正常使用
        }
    }

    /**
     * 在元数据树中递归查找指定节点名称的节点。
     *
     * @param node     当前遍历节点
     * @param nodeName 目标节点名称
     * @return 匹配到的节点，未找到时返回 空
     */
    private IIOMetadataNode findNodeByName(IIOMetadataNode node, String nodeName) {
        if (nodeName.equals(node.getNodeName())) {
            return node;
        }

        var children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            IIOMetadataNode child = (IIOMetadataNode) children.item(i);
            if (child == null) {
                continue;
            }
            IIOMetadataNode result = findNodeByName(child, nodeName);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * 将十六进制字符串转换为字节数组。
     *
     * @param hex 十六进制字符串
     * @return 转换后的字节数组
     */
    private byte[] hexStringToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * 获取 GIF 总帧数。
     *
     * @return 帧数
     */
    public int getFrameCount() {
        return frames.size();
    }

    /**
     * 根据索引获取指定帧画面。
     *
     * @param index 帧索引，越界时返回 空
     * @return 帧画面，越界时返回 空
     */
    public BufferedImage getFrame(int index) {
        if (index < 0 || index >= frames.size()) {
            return null;
        }
        return frames.get(index);
    }

    /**
     * 获取循环播放次数。
     *
     * @return 循环次数，0 表示无限循环
     */
    public int getLoopCount() {
        return loopCount;
    }

    /**
     * 根据索引获取指定帧的延迟时间。
     *
     * @param index 帧索引，越界时返回 10（100 毫秒）
     * @return 延迟时间，单位为百分之一秒
     */
    public int getDelay(int index) {
        if (index < 0 || index >= delays.size()) {
            return 10;
        }
        return delays.get(index);
    }

    /**
     * 获取 GIF 宽度。
     *
     * @return 宽度
     */
    public int getWidth() {
        return width;
    }

    /**
     * 获取 GIF 高度。
     *
     * @return 高度
     */
    public int getHeight() {
        return height;
    }
}
