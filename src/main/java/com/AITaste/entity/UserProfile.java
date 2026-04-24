package com.AITaste.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Data
@TableName("tb_user_profile")
public class UserProfile {
    @TableId(type = IdType.AUTO)
    private Long userId;

    private String taste;           // 口味偏好：辣,甜,清淡

    private Integer avgPriceLevel;  // 消费能力等级：1-低,2-中,3-高

    private String frequentArea;    // 常去区域

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // 将非空字段转为 Map，用于 Redis 批量写入
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        if (taste != null) map.put("taste", taste);
        if (avgPriceLevel != null) map.put("avgPriceLevel", avgPriceLevel);
        if (frequentArea != null) map.put("frequentArea", frequentArea);
        return map;
    }
}