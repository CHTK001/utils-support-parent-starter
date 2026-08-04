package com.chua.common.support.lang.placeholder;

import org.jspecify.annotations.NullUnmarked;


/**
 * 属性占位符配置器。
 * 该类用于配置属性占位符的解析行为，特别是系统属性的检查模式。
 * 它扩展了 PlaceholderSupport 类，提供了三种系统属性检查模式：
 * 1. 从不检查系统属性。
 * 2. 如果指定的属性中无法解析，则检查系统属性（默认模式）。
 * 3. 优先检查系统属性，允许系统属性覆盖任何其他属性源。
 *
 * @author CH
 */
@NullUnmarked
public class PropertyPlaceholderConfigurer extends PlaceholderSupport {

    /**
     * 从不检查系统属性。
     * 在此模式下，系统将完全忽略系统属性，仅使用配置的属性源进行解析。
     */
    public static final int SYSTEM_PROPERTIES_MODE_NEVER = 0;

    /**
     * 如果指定的属性中无法解析，则检查系统属性。
     * 这是默认模式。当在配置文件中找不到对应的属性值时，会尝试从系统属性中获取。
     */
    public static final int SYSTEM_PROPERTIES_MODE_FALLBACK = 1;

    /**
     * 优先检查系统属性，然后再尝试使用指定的属性。
     * 此模式允许系统属性覆盖任何其他属性源中的值，确保系统级配置具有最高优先级。
     */
    public static final int SYSTEM_PROPERTIES_MODE_OVERRIDE = 2;
}