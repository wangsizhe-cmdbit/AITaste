package com.AITaste.service;


import com.AITaste.entity.DiningOrder;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IDiningOrderService extends IService<DiningOrder> {
    DiningOrder createOrder(Long userId, Long shopId, Integer totalAmount, Long voucherOrderId, Integer finalAmount);

    void payOrder(Long orderId);
}