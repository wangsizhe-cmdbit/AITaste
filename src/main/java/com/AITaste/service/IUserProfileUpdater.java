package com.AITaste.service;


import com.AITaste.entity.Voucher;
import com.AITaste.entity.VoucherOrder;

public interface IUserProfileUpdater {

    void updateFromVoucherOrder(VoucherOrder voucherOrder, Voucher voucher);

}
