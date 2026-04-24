package com.AITaste.service;


import com.AITaste.dto.Result;
import com.AITaste.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    boolean createVoucherOrderSafe(VoucherOrder voucherId);

    Result orderCommonVoucher(Long voucherId);

    Result orderVoucher(Long id);

    // 查询用户所有优惠券
    Result getUserVouchers();
    // 按状态查询（0未使用，1已使用，2已过期）
    Result getUserVouchersByStatus(Integer status);

    void updateExpiredStatus();

    Result useVoucher(String orderId);

    Result cancelOrder(String orderId);

    Result receiveVoucher(Long voucherId);
}
