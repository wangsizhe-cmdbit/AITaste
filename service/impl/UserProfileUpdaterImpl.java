package com.AITaste.service.impl;

import com.AITaste.entity.Shop;
import com.AITaste.entity.Voucher;
import com.AITaste.entity.VoucherOrder;
import com.AITaste.service.IShopService;
import com.AITaste.service.IUserProfileService;
import com.AITaste.service.IUserProfileUpdater;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;


@Service
public class UserProfileUpdaterImpl implements IUserProfileUpdater {

    @Autowired
    private IUserProfileService userProfileService;

    @Autowired
    private IShopService shopService;

    /**
     * 根据优惠券订单更新用户画像（异步执行）
     * @param voucherOrder 优惠券订单
     * @param voucher      优惠券信息（用于获取金额和店铺ID）
     */
    @Async
    public void updateFromVoucherOrder(VoucherOrder voucherOrder, Voucher voucher) {
        if (voucherOrder == null || voucherOrder.getUserId() == null) {
            return;
        }
        Long userId = voucherOrder.getUserId();

        // 1. 更新消费等级（使用优惠券面额，单位：分）
        if (voucher != null && voucher.getPayValue() != null) {
            int priceLevel = calculatePriceLevel(voucher.getPayValue());
            userProfileService.updateTag(userId, "avgPriceLevel", priceLevel);
        }

        // 2. 更新口味标签（从订单关联的店铺获取）
        if (voucher != null && voucher.getShopId() != null) {
            Shop shop = shopService.getById(voucher.getShopId());
            if (shop != null && shop.getTasteTag() != null && !shop.getTasteTag().isEmpty()) {
                userProfileService.updateTag(userId, "taste", shop.getTasteTag());
            }
        }
    }

    /**
     * 根据金额（分）计算消费等级，与推荐模块中的梯度保持一致：
     * 等级1（低消费）：< 50元
     * 等级2（中消费）：50元 ~ 150元
     * 等级3（高消费）：> 150元
     */
    private int calculatePriceLevel(Long payValueInCents) {
        if (payValueInCents == null) return 2; // 默认中等
        double money = payValueInCents / 100.0; // 转换为元
        if (money < 50) {
            return 1;
        } else if (money <= 150) {
            return 2;
        } else {
            return 3;
        }
    }
}