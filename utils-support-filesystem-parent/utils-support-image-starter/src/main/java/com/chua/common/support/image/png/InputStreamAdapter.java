package com.chua.common.support.image.png;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
* 输入流适配器类，将镜像输入流转换为标准输入流
* 此类实现了输入流，以便在需要标准输入流的地方使用镜像输入流
*
* @author CH
* @since 4.0.0.42
*/
final class InputStreamAdapter extends InputStream {

    ImageInputStream stream; // 流

    /**
    * 构造函数，初始化镜像输入流
    *
    * @param stream 镜像输入流实例，需要被适配的流
     */
    public InputStreamAdapter(ImageInputStream stream) {
        super();

        this.stream = stream;
    }

    /**
    * 读取流中的下一个字节
    *
    * @return int 下一个字节的整数值，如果到达流的末尾则返回-1
    * @throws IOException 如果在读取过程中发生I/O错误
     */
    public int read() throws IOException {
        return stream.read();
    }

    /**
    * 从流中读取最多len个字节的数据到字节数组b中，从偏移量off开始
    *
    * @param b   目标字节数组
    * @param off 开始写入目标数组的偏移量
    * @param len 要读取的最大字节数
    * @return int 实际读取的字节数，如果到达流的末尾则返回-1
    * @throws IOException 如果在读取过程中发生I/O错误
     */
    public int read(byte[] b, int off, int len) throws IOException {
        return stream.read(b, off, len);
    }
}
