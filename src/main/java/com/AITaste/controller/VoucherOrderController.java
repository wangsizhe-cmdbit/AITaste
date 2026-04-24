package com.AITaste.controller;

import com.AITaste.dto.Result;
import com.AITaste.service.IVoucherOrderService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    // 创建订单
    @PostMapping("order/{id}")
    public Result order(@PathVariable Long id) {

        return voucherOrderService.orderVoucher(id);
    }

    // 查询当前用户的所有优惠券
    @GetMapping("/user/vouchers")
    public Result getUserVouchers() {

        return voucherOrderService.getUserVouchers();
    }

    // 按状态查询优惠券
    @GetMapping("/user/vouchers/status")
    public Result getUserVouchersByStatus(@RequestParam(required = false) Integer status) {
        // status允许为null，此时查全部（也可以复用上面的接口，分开更清晰）
        return voucherOrderService.getUserVouchersByStatus(status);
    }

    @PostMapping("/use")
    public Result useVoucher(@RequestBody Map<String, String> params) {
        String orderId = params.get("orderId");
        return voucherOrderService.useVoucher(orderId);
    }

    @PutMapping("/cancel")
    public Result cancelOrder(@RequestBody Map<String, String> params) {
        String orderId = params.get("orderId");
        return voucherOrderService.cancelOrder(orderId);
    }
  }
