package com.AITaste.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("tb_dining_order")
public class DiningOrder {
    @TableId
    private Long id;
    private Long userId;
    private Long shopId;
    private Integer totalAmount;     // 总金额（分）
    private Long voucherOrderId;     // 使用的优惠券订单ID
    private Integer finalAmount;     // 实付金额（分）
    private Integer status;          // 0待支付 1已支付 2已取消
    private LocalDateTime createTime;
    private LocalDateTime payTime;
}