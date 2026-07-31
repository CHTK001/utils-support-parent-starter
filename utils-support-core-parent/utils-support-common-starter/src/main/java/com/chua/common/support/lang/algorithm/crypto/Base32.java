package com.chua.common.support.lang.algorithm.crypto;

import java.util.Arrays;


/**
 * Base32 编码与解码工具类
 * 使用 RFC 4648 标准定义的 Base32 字符集进行数据转换
 * @author CH
 * @since 2024/12/3
 */
public class Base32 {
    /**
     * 默认使用的 Base32 字母表 (RFC 4648)
     */
    private static final String DEFAULT_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    /**
     * 十六进制风格的 Base32 字母表 (未在当前实现中使用，保留备用)
     */
    private static final String HEX_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUV";
    /**
     * 默认填充字符
     */
    private static final Character DEFAULT_PAD = '=';
    /**
     * 用于计算编码后长度的填充偏移量数组
     * 索引对应原始字节数 mod 5 的结果：0->-1, 1->4, 2->1, 3->6, 4->3
     */
    private static final int[] BASE32_FILL = {-1, 4, 1, 6, 3};
    /**
     * 用于查找表的基准字符 ('0')
     */
    private static final char BASE_CHAR = '0';
    /**
     * 运行时生成的字母表字符数组
     */
    private static final char[] alphabet;
    /**
     * 运行时生成的填充字符
     */
    private static final Character pad;
    /**
     * 反向查找表，将字符映射回其对应的数值 (0-31)
     */
    private static final byte[] lookupTable;

    static  {
        // 初始化字母表
        alphabet = DEFAULT_ALPHABET.toCharArray();
        pad = DEFAULT_PAD;
        // 初始化查找表，大小为 ASCII 128
        lookupTable = new byte[128];
        Arrays.fill(lookupTable, (byte) -1);

        final int length = DEFAULT_ALPHABET.length();

        char c;
        for (int i = 0; i < length; i++) {
            c = DEFAULT_ALPHABET.charAt(i);
            // 将大写字母映射到查找表中
            lookupTable[c - BASE_CHAR] = (byte) i;
            // 同时支持小写字母，忽略大小写处理
            if(c >= 'A' && c <= 'Z'){
                lookupTable[Character.toLowerCase(c) - BASE_CHAR] = (byte) i;
            }
        }
    }

    /**
     * 将字节数组编码为 Base32 字符串
     * @param data 待编码的原始字节数据
     * @return Base32 编码后的字符串
     */
    public static String encode(byte[] data) {
        // 当前处理的字节索引
        int i = 0;
        // 当前位偏移量 (0-7)，表示在 8 位流中的位置
        int index = 0;
        // 提取出的 5 位数字 (0-31)
        int digit;
        // 当前字节的无符号值
        int currByte;
        // 下一个字节的无符号值
        int nextByte;

        // 计算编码后的理论长度
        // 每 5 个比特产生一个字符，不足部分需要填充
        int encodeLen = data.length * 8 / 5;
        if (encodeLen != 0) {
            // 根据剩余比特数调整长度，BASE32_FILL 提供修正值
            encodeLen = encodeLen + 1 + BASE32_FILL[(data.length * 8) % 5];
        }

        StringBuilder base32 = new StringBuilder(encodeLen);

        while (i < data.length) {
            // 将有符号字节转换为无符号值 (0-255)
            currByte = (data[i] >= 0) ? data[i] : (data[i] + 256);

            /* 
             * 判断当前的 5 位数字是否跨越了字节边界
             * 当 index > 3 时，剩余的位数不足以构成完整的 5 位，需要借用下一个字节的部分位
             */
            if (index > 3) {
                if ((i + 1) < data.length) {
                    // 获取下一个字节的无符号值
                    nextByte = (data[i + 1] >= 0) ? data[i + 1] : (data[i + 1] + 256);
                } else {
                    // 如果是最后一个字节且不够 5 位，补 0
                    nextByte = 0;
                }

                // 从当前字节的高位提取剩余的有效位
                digit = currByte & (0xFF >> index);
                // 更新位偏移量
                index = (index + 5) % 8;
                // 将提取的位移到正确位置
                digit <<= index;
                // 从下一个字节补充低位
                digit |= nextByte >> (8 - index);
                // 移动到下一个字节
                i++;
            } else {
                // 不需要跨字节，直接从当前字节中提取 5 位
                digit = (currByte >> (8 - (index + 5))) & 0x1F;
                index = (index + 5) % 8;
                // 如果正好处理完一个字节 (index 回到 0)，移动字节指针
                if (index == 0) {
                    i++;
                }
            }
            // 将 5 位数字映射为字符并追加
            base32.append(alphabet[digit]);
        }

        // 如果需要填充字符，则补齐到计算好的长度
        if (null != pad) {
            while (base32.length() < encodeLen) {
                base32.append(pad.charValue());
            }
        }

        return base32.toString();
    }

    /**
     * 将 Base32 字符串解码为字节数组
     * @param encoded Base32 编码后的字符串
     * @return 解码后的原始字节数组
     */
    public static byte[] decode(CharSequence encoded) {
        // // 遍历编码字符串的索引
        int i;      
        // // 当前位偏移量 (0-7)
        int index;  
        // // 字符对应的 ASCII 偏移量
        int lookup; 
        // // 输出字节数组的索引
        int offset; 
        // // 解码得到的 5 位数值 (0-31)
        int digit;  
        
        final String base32 = encoded.toString();
        
        // 计算解码后的字节长度
        // 如果有填充符 '='，则根据第一个填充符的位置计算；否则按总长度计算
        int len = base32.endsWith("=") ? base32.indexOf("=") * 5 / 8 : base32.length() * 5 / 8;
        byte[] bytes = new byte[len];

        for (i = 0, index = 0, offset = 0; i < base32.length(); i++) {
            lookup = base32.charAt(i) - BASE_CHAR;

            /* 跳过超出查找表范围的字符 (非 ASCII 或控制字符等) */
            if (lookup < 0 || lookup >= lookupTable.length) {
                continue;
            }

            digit = lookupTable[lookup];

            /* 如果该字符不在 Base32 字母表中 (值为 -1)，则忽略 */
            if (digit < 0) {
                continue;
            }

            // 根据当前位偏移量决定如何重组字节
            if (index <= 3) {
                // 情况 A: 当前 5 位主要在高位，或者刚好填满一个字节
                index = (index + 5) % 8;
                if (index == 0) {
                    // 刚好凑齐 8 位，写入结果数组
                    bytes[offset] |= digit;
                    offset++;
                    if (offset >= bytes.length) {
                        break;
                    }
                } else {
                    // 还有剩余空间，将当前 5 位移到高位暂存
                    bytes[offset] |= digit << (8 - index);
                }
            } else {
                // 情况 B: 当前 5 位跨越了字节边界，需要拆分
                index = (index + 5) % 8;
                // 高 5 位中的有效部分移到低位填入当前字节
                bytes[offset] |= (digit >>> index);
                offset++;

                if (offset >= bytes.length) {
                    break;
                }
                // 低 5 位中的剩余部分移到高位存入下一个字节
                bytes[offset] |= digit << (8 - index);
            }
        }
        return bytes;
    }
}
