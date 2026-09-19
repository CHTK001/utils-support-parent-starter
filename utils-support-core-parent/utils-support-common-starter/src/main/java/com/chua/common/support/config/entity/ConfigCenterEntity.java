package com.chua.common.support.config.entity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
/**
 * <p>
 *                    config_center                     
 * </p>
 *
 * @author CH
 * @since 2024/12/07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigCenterEntity {
    /**
     *       ID
     */
    private Long id;
    /**
     *       ID       application.yml   
     */
    private String dataId;
    /**
     *                          dev   prod   DEFAULT_GROUP   
     */
    private String groupName;
    /**
     */
    private String configKey;
    /**
     */
    private String configValue;
    /**
     */
    private LocalDateTime updateTime;
}
