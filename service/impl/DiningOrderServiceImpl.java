package com.AITaste.service.impl;

import com.AITaste.entity.DiningOrder;
import com.AITaste.mapper.DiningOrderMapper;
import com.AITaste.service.IDiningOrderService;
import com.AITaste.utils.RedisIdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class DiningOrderServiceImpl extends ServiceImpl<DiningOrderMapper, DiningOrder> implements IDiningOrderService {

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Override
    public DiningOrder createOrder(Long userId, Long shopId, Integer totalAmount, Long voucherOrderId, Integer finalAmount) {
        DiningOrder order = new DiningOrder();
        long orderId = redisIdWorker.nextId("dining_order");
        order.setId(orderId);
        order.setUserId(userId);
        order.setShopId(shopId);
        order.setTotalAmount(totalAmount);
        order.setVoucherOrderId(voucherOrderId);
        order.setFinalAmount(finalAmount);
        order.setStatus(0); // 待支付
        order.setCreateTime(LocalDateTime.now());
        save(order);
        return order;
    }

    @Override
    public void payOrder(Long orderId) {
        DiningOrder order = getById(orderId);
        if (order == null || order.getStatus() != 0) {
            throw new RuntimeException("订单不存在或状态错误");
        }
        order.setStatus(1);
        order.setPayTime(LocalDateTime.now());
        updateById(order);
    }
}