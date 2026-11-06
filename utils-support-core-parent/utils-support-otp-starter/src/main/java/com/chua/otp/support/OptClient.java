package com.chua.otp.support;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.spi.annotations.SpiParam;
import com.chua.common.support.task.message.MessageEnvironment;
import com.chua.common.support.task.message.MessagePush;
import com.chua.common.support.task.message.MessageRequest;
import com.chua.common.support.task.message.MessageResponse;
import com.chua.common.support.task.message.TemplateInfo;
import com.chua.common.support.utils.RandomUtils;
import com.chua.common.support.utils.StringUtils;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * OTP（一次性密码）消息推送实现
 *
 * <p>通过 {@link MessagePush} SPI 提供 OTP 的发送（call）、获取最近记录（get）和列表查询（list）能力，
 * 适用于手机/邮箱接收的一次性验证码场景。
 *
 * <h3>环境配置</h3>
 * <pre>
 *   otp.length       验证码长度（默认 6）
 *   otp.duration     验证码有效时长，单位毫秒（默认 60000）
 *   otp.content      下发内容模板，占位符 {code}（默认 您的验证码为：{code}，请于 5 分钟内使用。
 *   otp.type         验证码类型：DIGIT / LETTER / MIXED（默认 DIGIT）
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 *   OptClient client = (OptClient) MessagePush.create("opt");
 *
 *   // 发送验证码
 *   MessageResponse response = client.call("user123");
 *
 *   // 获取最近一条验证码记录
 *   OptMessage message = client.get("user123");
 *
 *   // 验证码记录列表
 *   List<OptMessage> list = client.list("user123");
 *
 *   // 核对验证码
 *   boolean ok = client.verify("user123", message.code());
 * }</pre>
 *
 * @author CH
 * @since 4.0.0
 */
@Spi("opt")
@SpiDescribe(
        value = "OTP推送",
        type = "OTP",
        desc = "通过 MessagePush SPI 提供一次性密码（OTP）的生成、发送、获取和列表查询",
        optional = {
                @SpiParam(value = "otp.length", defaultValue = "6", desc = "验证码长度", type = "int"),
                @SpiParam(value = "otp.duration", defaultValue = "60000", desc = "验证码有效时长（毫秒）", type = "long"),
                @SpiParam(value = "otp.content", defaultValue = "您的验证码为：{code}，请于 5 分钟内使用。",
                        desc = "验证码下发内容模板，占位符 {code}", type = "String"),
                @SpiParam(value = "otp.type", defaultValue = "DIGIT", desc = "验证码类型：DIGIT/LETTER/MIXED", type = "String")
        }
)
@Slf4j
public class OptClient implements MessagePush {

    /** 验证码长度属性 */
    private static final String PROP_LENGTH = "otp.length";
    /** 验证码有效时长属性 */
    private static final String PROP_DURATION = "otp.duration";
    /** 验证码下发内容属性 */
    private static final String PROP_CONTENT = "otp.content";
    /** 验证码类型属性 */
    private static final String PROP_TYPE = "otp.type";

    /** 默认长度 */
    private static final int DEFAULT_LENGTH = 6;
    /** 默认有效时长（毫秒） */
    private static final long DEFAULT_DURATION = 60_000L;
    /** 默认内容模板 */
    private static final String DEFAULT_CONTENT = "您的验证码为：{code}，请于 5 分钟内使用。";
    /** 默认类型 */
    private static final String DEFAULT_TYPE = "DIGIT";
    /** 每个用户最大保留记录数 */
    private static final int MAX_KEEP = 20;
    /** 字母码表大小（a-z，共 26 位） */
    private static final int CODE_LETTER_SIZE = 26;
    /** 混合码表大小（a-z、A-Z、0-9，共 62 位） */
    private static final int CODE_MIXED_SIZE = 62;
    /** 数字码表大小（0-9，共 10 位） */
    private static final int CODE_DIGIT_SIZE = 10;

    /**
     * 消息环境
     */
    private final MessageEnvironment environment;

    /**
     * 验证码记录池（接收人标识 → 记录列表）
     */
    private final ConcurrentMap<String, List<OptMessage>> store;

    /**
     * 创建 OTP 推送器，使用默认环境配置
     */
    public OptClient() {
        this(new MessageEnvironment());
    }

    /**
     * 创建 OTP 推送器
     *
     * @param environment 消息环境，提供 OTP 的参数配置
     */
    public OptClient(MessageEnvironment environment) {
        this.environment = environment;
        this.store = new ConcurrentHashMap<>();
    }

    /**
     * 获取推送渠道标识
     *
     * @return "opt"
     */
    @Override
    public String getProvider() {
        return "opt";
    }

    /**
     * 发送 OTP 验证码（使用 toUser 作为接收人）
     *
     * @param request 消息请求
     * @return 发送结果
     */
    @Override
    public MessageResponse send(MessageRequest request) {
        return call(request.getTo());
    }

    /**
     * 获取模板列表（OTP 没有模板，返回空列表）
     *
     * @return 模板列表
     */
    @Override
    public List<TemplateInfo> listTemplates() {
        return Collections.emptyList();
    }

    /**
     * 根据标识获取模板（OTP 没有模板，返回 空）
     *
     * @param templateId 模板标识
     * @return 模板信息
     */
    @Override
    public TemplateInfo getTemplate(String templateId) {
        return null;
    }

    /**
     * 向 toUser 发送 OTP 验证码（自动生成）
     *
     * @param toUser 接收人标识
     * @return 发送结果
     */
    public MessageResponse call(String toUser) {
        return call(toUser, null);
    }

    /**
     * 向 toUser 发送 OTP 验证码
     *
     * @param toUser 接收人标识
     * @param code   验证码，为 空 时自动生成
     * @return 发送结果
     */
    public MessageResponse call(String toUser, String code) {
        long start = System.currentTimeMillis();
        if (StringUtils.isBlank(toUser)) {
            return MessageResponse.failure("接收人不能为空");
        }
        String submitted = StringUtils.isBlank(code) ? generateCode() : code;
        OptMessage message = save(toUser, submitted);
        return MessageResponse.builder()
                .success(message.success())
                .messageId(message.id())
                .errorMessage(message.errorMsg())
                .durationMillis(System.currentTimeMillis() - start)
                .build();
    }

    /**
     * 获取 toUser 最近一条验证码记录
     *
     * @param toUser 接收人标识
     * @return 最近记录，未发送过返回 空
     */
    public OptMessage get(String toUser) {
        if (StringUtils.isBlank(toUser)) {
            return null;
        }
        expire(toUser);
        List<OptMessage> list = store.get(toUser);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.stream()
                .filter(m -> !m.isExpired())
                .findFirst()
                .orElseGet(() -> list.get(0));
    }

    /**
     * 获取 toUser 的 OTP 验证码记录列表
     *
     * @param toUser 接收人标识
     * @return 记录列表（按发送时间倒序）
     */
    public List<OptMessage> list(String toUser) {
        if (StringUtils.isBlank(toUser)) {
            return Collections.emptyList();
        }
        expire(toUser);
        List<OptMessage> list = store.get(toUser);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return copy(list);
    }

    /**
     * 获取全部 OTP 验证码记录
     *
     * @return 记录列表
     */
    public List<OptMessage> list() {
        List<OptMessage> result = new ArrayList<>();
        for (List<OptMessage> list : store.values()) {
            result.addAll(list);
        }
        return result;
    }

    /**
     * 核对 toUser 的最近一条有效验证码
     *
     * @param toUser 接收人标识
     * @param code   用户提供的验证码
     * @return true 表示核验通过
     */
    public boolean verify(String toUser, String code) {
        if (StringUtils.isBlank(toUser) || StringUtils.isBlank(code)) {
            return false;
        }
        OptMessage message = get(toUser);
        if (message == null) {
            return false;
        }
        return StringUtils.equals(message.code(), code);
    }

    /**
     * 生成验证码
     *
     * @return 验证码
     */
    private String generateCode() {
        int length = parseInt(PROP_LENGTH, DEFAULT_LENGTH);
        String type = type(PROP_TYPE, DEFAULT_TYPE);
        int bound;
        if (OptMessage.TYPE_LETTER.equals(type)) {
            bound = CODE_LETTER_SIZE;
        } else if (OptMessage.TYPE_MIXED.equals(type)) {
            bound = CODE_MIXED_SIZE;
        } else {
            bound = CODE_DIGIT_SIZE;
        }
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int code = RandomUtils.randomInt(0, bound);
            if (OptMessage.TYPE_LETTER.equals(type)) {
                builder.append((char) ('a' + code));
            } else if (OptMessage.TYPE_MIXED.equals(type)) {
                builder.append(code < CODE_DIGIT_SIZE ? (char) ('0' + code) : (char) ('a' + code - CODE_DIGIT_SIZE));
            } else {
                builder.append((char) ('0' + code));
            }
        }
        return builder.toString();
    }

    /**
     * 保存本次验证码
     *
     * @param toUser 接收人标识
     * @param code   验证码
     * @return 验证码记录
     */
    private OptMessage save(String toUser, String code) {
        int length = parseInt(PROP_LENGTH, DEFAULT_LENGTH);
        long duration = parseLong(PROP_DURATION, DEFAULT_DURATION);
        String type = type(PROP_TYPE, DEFAULT_TYPE);
        OptMessage message = OptMessage.record(toUser, code, render(content(), code), type, true, null, duration);
        store.compute(toUser, (key, list) -> {
            List<OptMessage> result = list == null ? new ArrayList<>(MAX_KEEP) : new ArrayList<>(list);
            result.add(0, message);
            if (result.size() > MAX_KEEP) {
                result = new ArrayList<>(result.subList(0, MAX_KEEP));
            }
            return result;
        });
        return message;
    }

    /**
     * 清理 toUser 的过期记录
     *
     * @param toUser 接收人标识
     */
    private void expire(String toUser) {
        store.compute(toUser, (key, list) -> {
            if (list == null || list.isEmpty()) {
                return null;
            }
            List<OptMessage> result = list.stream()
                    .filter(m -> !m.isExpired())
                    .collect(Collectors.toList());
            return result.isEmpty() ? null : result;
        });
    }

    /**
     * 复制列表
     *
     * @param list 原始列表
     * @return 客户端不可变列表
     */
    private List<OptMessage> copy(List<OptMessage> list) {
        return Collections.unmodifiableList(new ArrayList<>(list));
    }

    /**
     * 验证码内容模板
     *
     * @return 内容模板
     */
    private String content() {
        return parseString(PROP_CONTENT, DEFAULT_CONTENT);
    }

    /**
     * 验证码类型
     *
     * @param prop 属性名
     * @param def  默认值
     * @return 类型
     */
    private String type(String prop, String def) {
        return parseString(prop, def);
    }

    /**
     * 使用 {code} 占位符渲染内容
     *
     * @param template 模板
     * @param code     验证码
     * @return 渲染后的内容
     */
    private String render(String template, String code) {
        if (StringUtils.isBlank(template)) {
            return DEFAULT_CONTENT;
        }
        return template.replace("{code}", code);
    }

    /**
     * 解析整型参数
     *
     * @param prop 属性名
     * @param def  默认值
     * @return 解析结果
     */
    private int parseInt(String prop, int def) {
        String value = environment.get(prop);
        if (StringUtils.isBlank(value)) {
            return def;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 解析长整型参数
     *
     * @param prop 属性名
     * @param def  默认值
     * @return 解析结果
     */
    private long parseLong(String prop, long def) {
        String value = environment.get(prop);
        if (StringUtils.isBlank(value)) {
            return def;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 解析字符串参数
     *
     * @param prop 属性名
     * @param def  默认值
     * @return 解析结果
     */
    private String parseString(String prop, String def) {
        String value = environment.get(prop);
        return StringUtils.isBlank(value) ? def : value;
    }
}
