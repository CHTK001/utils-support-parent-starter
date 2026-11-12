// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.network.support.tshark.restorer;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 短信协议还原器（SMPP 3.4，端口 2775 及运营商网关）。
 *
 * <p><b>为什么是 SMPP 而不是「短信」</b>：手机点对点短信不走 IP，抓不到；企业短信
 * （验证码、通知）都是应用 → 短信网关的 SMPP 长连接，这一段在明文里跑，能完整还原：
 * 谁发的、发给谁、什么编码、正文是什么。</p>
 *
 * <p>解析内容：16 字节头（长度 / 命令 / 状态 / 序号）+ 命令体。
 * 覆盖 {@code bind_*}、{@code submit_sm}、{@code deliver_sm}、{@code enquire_link}、
 * {@code unbind} 及其应答；正文字段按 {@code data_coding} 解码
 * （0/3 走 ASCII/GSM，8 走 UCS2/UTF-16BE），这是中文短信最常踩的坑。</p>
 *
 * <p><b>不做</b>：GSM 7-bit 压缩表的完整映射（只按 ASCII 处理，遇扩展字符保留原字节），
 * 也不解长短信的 UDH 分片——分片还原需要按 {@code sequence_number} 聚合多条 PDU，
 * 那是会话级的事，不在单帧还原里硬造。</p>
 *
 * @author CH
 * @since 4.0.0.60
 */
public class SmppProtocolRestorer extends AbstractProtocolRestorer {

    /**
     * SMPP 头长度
     */
    private static final int HEADER_LEN = 16;

    /**
     * 正文预览上限（字符）
     */
    private static final int MAX_TEXT = 200;

    /**
     * 命令：bind_receiver
     */
    private static final int CMD_BIND_RECEIVER = 0x00000001;

    /**
     * 命令：bind_transmitter
     */
    private static final int CMD_BIND_TRANSMITTER = 0x00000002;

    /**
     * 命令：submit_sm
     */
    private static final int CMD_SUBMIT_SM = 0x00000004;

    /**
     * 命令：deliver_sm
     */
    private static final int CMD_DELIVER_SM = 0x00000005;

    /**
     * 命令：unbind
     */
    private static final int CMD_UNBIND = 0x00000006;

    /**
     * 命令：bind_transceiver
     */
    private static final int CMD_BIND_TRANSCEIVER = 0x00000009;

    /**
     * 命令：enquire_link
     */
    private static final int CMD_ENQUIRE_LINK = 0x00000015;

    /**
     * 应答位
     */
    private static final int RESPONSE_BIT = 0x80000000;

    /**
     * data_coding：UCS2（UTF-16BE）
     */
    private static final int CODING_UCS2 = 8;

    /**
     * 获取协议名称
     */
    @Override
    public String getProtocolName() {
        return "smpp";
    }

    /**
     * 获取Priority
     */
    @Override
    public int getPriority() {
        return 55;
    }

    /**
     * 是否可以Restore
     */
    @Override
    public boolean canRestore(Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < HEADER_LEN) {
            return false;
        }
        int declared = readInt(rawData, 0);
        if (declared < HEADER_LEN || declared > rawData.length) {
            return false;
        }
        int command = readInt(rawData, 4);
        int base = command & ~RESPONSE_BIT;
        return base == CMD_BIND_RECEIVER || base == CMD_BIND_TRANSMITTER || base == CMD_SUBMIT_SM
                || base == CMD_DELIVER_SM || base == CMD_UNBIND || base == CMD_BIND_TRANSCEIVER
                || base == CMD_ENQUIRE_LINK;
    }

    /**
     * Restore
     */
    @Override
    public String restore(Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < HEADER_LEN) {
            return "[SMPP] empty";
        }
        int length = readInt(rawData, 0);
        int command = readInt(rawData, 4);
        int status = readInt(rawData, 8);
        int sequence = readInt(rawData, 12);
        boolean response = (command & RESPONSE_BIT) != 0;
        int base = command & ~RESPONSE_BIT;
        int end = Math.min(rawData.length, Math.max(HEADER_LEN, length));
        Cursor cursor = new Cursor(rawData, HEADER_LEN, end);

        StringBuilder sb = new StringBuilder("[SMPP] ");
        sb.append(commandName(base));
        if (response) {
            sb.append("_resp");
        }
        sb.append(" seq=").append(sequence);
        if (response) {
            sb.append(", status=").append(status).append(status == 0 ? " (OK)" : "");
            return sb.toString();
        }
        if (base == CMD_SUBMIT_SM || base == CMD_DELIVER_SM) {
            cursor.cstring();
            cursor.skip(2);
            String source = cursor.cstring();
            cursor.skip(2);
            String dest = cursor.cstring();
            cursor.skip(3);
            cursor.cstring();
            cursor.cstring();
            cursor.skip(2);
            int coding = cursor.u8();
            cursor.u8();
            int textLength = cursor.u8();
            byte[] text = cursor.bytes(textLength);
            sb.append(", src=").append(source);
            sb.append(", dst=").append(dest);
            sb.append(", coding=").append(codingName(coding));
            String message = decodeText(text, coding);
            if (!message.isEmpty()) {
                sb.append(", text=").append(message);
            }
        } else if (base == CMD_BIND_TRANSCEIVER || base == CMD_BIND_TRANSMITTER
                || base == CMD_BIND_RECEIVER) {
            String systemId = cursor.cstring();
            cursor.cstring();
            String systemType = cursor.cstring();
            sb.append(", system_id=").append(systemId);
            if (!systemType.isEmpty()) {
                sb.append(", system_type=").append(systemType);
            }
        }
        return sb.toString();
    }

    /**
     * 解正文：UCS2 走 UTF-16BE，其余按 ASCII 兜底。
     *
     * @param text   正文字节
     * @param coding data_coding
     * @return 可读文本
     */
    protected static String decodeText(byte[] text, int coding) {
        if (text == null || text.length == 0) {
            return "";
        }
        String value;
        if (coding == CODING_UCS2) {
            value = new String(text, StandardCharsets.UTF_16BE);
        } else {
            value = new String(text, StandardCharsets.ISO_8859_1);
        }
        value = value.trim();
        return value.length() > MAX_TEXT ? value.substring(0, MAX_TEXT) + "…" : value;
    }

    /**
     * 命令名。
     *
     * @param command 命令号（已去掉应答位）
     * @return 名称
     */
    private static String commandName(int command) {
        return switch (command) {
            case CMD_BIND_RECEIVER -> "bind_receiver";
            case CMD_BIND_TRANSMITTER -> "bind_transmitter";
            case CMD_BIND_TRANSCEIVER -> "bind_transceiver";
            case CMD_SUBMIT_SM -> "submit_sm";
            case CMD_DELIVER_SM -> "deliver_sm";
            case CMD_UNBIND -> "unbind";
            case CMD_ENQUIRE_LINK -> "enquire_link";
            default -> "0x" + Integer.toHexString(command);
        };
    }

    /**
     * data_coding 名称。
     *
     * @param coding 编码
     * @return 名称
     */
    private static String codingName(int coding) {
        return switch (coding) {
            case 0 -> "SMSC_DEFAULT";
            case 1 -> "ASCII";
            case 3 -> "LATIN1";
            case CODING_UCS2 -> "UCS2";
            default -> String.valueOf(coding);
        };
    }

    /**
     * 读 4 字节大端整数。
     *
     * @param data 数据
     * @param at   起点
     * @return 值
     */
    private static int readInt(byte[] data, int at) {
        return ((data[at] & 0xff) << 24) | ((data[at + 1] & 0xff) << 16)
                | ((data[at + 2] & 0xff) << 8) | (data[at + 3] & 0xff);
    }

    /**
     * 只读游标：PDU 体是按顺序排的，越界一律返回空值而不是抛异常。
     */
    private static final class Cursor {

        /**
         * 数据
         */
        private final byte[] data;

        /**
         * 当前位置
         */
        private int pos;

        /**
         * 结束位置
         */
        private final int end;

        /**
         * 构造。
         *
         * @param data 数据
         * @param from 起点
         * @param to   终点
         */
        private Cursor(byte[] data, int from, int to) {
            this.data = data;
            this.pos = from;
            this.end = Math.min(to, data.length);
        }

        /**
         * 读 1 字节。
         *
         * @return 值，越界返回 0
         */
        private int u8() {
            return this.pos < this.end ? this.data[this.pos++] & 0xff : 0;
        }

        /**
         * 跳过若干字节。
         *
         * @param count 字节数
         */
        private void skip(int count) {
            this.pos = Math.min(this.end, this.pos + count);
        }

        /**
         * 读 C 字符串（以 0 结尾）。
         *
         * @return 字符串，越界返回空串
         */
        private String cstring() {
            int start = this.pos;
            while (this.pos < this.end && this.data[this.pos] != 0) {
                this.pos++;
            }
            String value = new String(this.data, start, this.pos - start, StandardCharsets.ISO_8859_1);
            if (this.pos < this.end) {
                this.pos++;
            }
            return value;
        }

        /**
         * 读定长字节。
         *
         * @param count 长度
         * @return 字节，越界返回空数组
         */
        private byte[] bytes(int count) {
            if (count <= 0 || this.pos + count > this.end) {
                return new byte[0];
            }
            byte[] value = java.util.Arrays.copyOfRange(this.data, this.pos, this.pos + count);
            this.pos += count;
            return value;
        }
    }
}
