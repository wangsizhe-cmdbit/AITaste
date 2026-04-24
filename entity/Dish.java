package com.AITaste.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("tb_dish")
public class Dish {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shopId;
    private String name;
    private Integer price;   // 单位：分
    private String image;
    private Integer status;  // 1上架 0下架
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}