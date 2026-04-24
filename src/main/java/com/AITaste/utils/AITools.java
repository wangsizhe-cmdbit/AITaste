package com.AITaste.utils;

import com.AITaste.dto.Result;
import com.AITaste.dto.UserDTO;
import com.AITaste.entity.*;
import com.AITaste.service.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import com.alibaba.fastjson2.JSON;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;


@Component
public class AITools {

    @Autowired
    @Lazy
    private IShopService shopService;

    @Autowired
    private IVoucherService voucherService;

    @Autowired
    private IVoucherOrderService voucherOrderService;

    @Autowired
    private IUserProfileService userProfileService;  // 新增注入

    @Autowired
    private ICartService cartService;

    @Autowired
    private IDiningOrderService diningOrderService;

    @Autowired
    private IUserService userService;

    @Resource
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @PostConstruct
    public void init() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Tool(description = "根据用户位置和类型查询附近的店铺，如果用户传了经纬度就用经纬度，没传就使用常去区域，并根据用户画像（口味、消费等级）智能排序。")
    public String queryShops(
            @ToolParam(description = "经度", required = false) Double x,
            @ToolParam(description = "纬度", required = false) Double y,
            @ToolParam(description = "页码，默认1", required = false) Integer pageNum, ToolContext toolContext) {

        // 获取用户画像
        Long userId = (Long) toolContext.getContext().get("userId");
        UserProfile profile = userProfileService.getUserProfile(userId);

        List<Shop> shops = null;

        // 分支1：用户提供了经纬度 -> 使用地理距离查询
        if (x != null && y != null) {
            Result result = shopService.queryAllShops(pageNum, x, y);
            if (result.getSuccess()) {
                shops = (List<Shop>) result.getData();
            } else {
                return "查询失败：" + result.getErrorMsg();
            }
        }
        // 分支2：用户未提供经纬度，但有常去区域 -> 按区域文本匹配查询
        else if (profile != null && profile.getFrequentArea() != null) {
            String area = profile.getFrequentArea();
            // 调用一个新的 service 方法：根据区域和类型查询店铺（不依赖坐标）
            Result result = shopService.queryShopsByArea(area, pageNum);
            if (result.getSuccess()) {
                shops = (List<Shop>) result.getData();
            } else {
                return "查询失败：" + result.getErrorMsg();
            }
        }
        // 分支3：既无坐标也无常去区域 -> 使用默认查询
        else {
            Result result = shopService.queryAllShops(pageNum, null, null);
            if (result.getSuccess()) {
                shops = (List<Shop>) result.getData();
            } else {
                return "查询失败：" + result.getErrorMsg();
            }
        }

        // 3. 根据画像对店铺排序（画像存在）
        final UserProfile finalProfile = profile;
        if (finalProfile != null && shops != null && !shops.isEmpty()) {
            shops.sort((s1, s2) -> {
                int score1 = calculateMatchScore(s1, finalProfile);
                int score2 = calculateMatchScore(s2, finalProfile);
                return Integer.compare(score2, score1);  // 高分在前
            });
        }

        // 4. 格式化输出（详细模式：每个店铺单独查询完整信息）
        int limit = Math.min(5, shops.size());
        StringBuilder sb = new StringBuilder();
        if (profile != null && profile.getTaste() != null) {
            sb.append("根据您").append(profile.getTaste()).append("的口味偏好，为您推荐以下餐厅：\n");
        } else {
            sb.append("为您推荐以下餐厅：\n");
        }
        for (int i = 0; i < limit; i++) {
            Shop simpleShop = shops.get(i);
            // 调用 queryById 获取详细信息
            Result detailResult = shopService.queryById(simpleShop.getId());
            if (!detailResult.getSuccess() || detailResult.getData() == null) {
                sb.append(i + 1).append(". ").append(simpleShop.getName()).append("（信息获取失败）\n");
                continue;
            }
            Shop fullShop = (Shop) detailResult.getData();

            sb.append(i + 1).append(". ").append(fullShop.getName());

            // 口味标签
            if (fullShop.getTasteTag() != null && !fullShop.getTasteTag().isEmpty()) {
                sb.append("（").append(fullShop.getTasteTag()).append("口味）");
            }
            // 人均价格
            if (fullShop.getAvgPrice() != null) {
                sb.append(" 人均¥").append(fullShop.getAvgPrice());
            }
            // 距离（如果之前有）
            if (simpleShop.getDistance() != null) {
                sb.append(" 距离").append(String.format("%.0f", simpleShop.getDistance())).append("米");
            }
            // 评分
            if (fullShop.getScore() != null) {
                sb.append(" 评分").append(fullShop.getScore());
            }
            // 详细地址
            if (fullShop.getAddress() != null && !fullShop.getAddress().isEmpty()) {
                sb.append(" 地址：").append(fullShop.getAddress());
            }
            // 营业时间（如果 Shop 有 openHours 字段）
            if (fullShop.getOpenHours() != null && !fullShop.getOpenHours().isEmpty()) {
                sb.append(" 营业时间：").append(fullShop.getOpenHours());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public int calculateMatchScore(Shop shop, UserProfile profile) {
        if (profile == null) {
            return 0; // 游客无个性匹配分
        }
        int score = 0;
        String taste = profile.getTaste();
        Integer priceLevel = profile.getAvgPriceLevel();

        // 1. 口味匹配：直接使用店铺的口味标签
        if (taste != null && shop.getTasteTag() != null) {
            if (taste.equals(shop.getTasteTag())) {
                score += 10;
            }
        }

        // 2. 消费等级匹配：根据店铺人均价格动态计算等级
        if (priceLevel != null && shop.getAvgPrice() != null) {
            int shopLevel;
            int avgPrice = Math.toIntExact(shop.getAvgPrice());
            if (avgPrice < 50) {
                shopLevel = 1;      // 低消费
            } else if (avgPrice <= 150) {
                shopLevel = 2;      // 中消费
            } else {
                shopLevel = 3;      // 高消费
            }
            int diff = Math.abs(priceLevel - shopLevel);
            if (diff == 0) {
                score += 8;
            } else if (diff == 1) {
                score += 4;
            }
        }
        return score;
    }

    @Tool(description = "查询指定店铺的优惠券列表")
    public String queryVouchers(@ToolParam(description = "店铺ID") Long shopId,
                                ToolContext toolContext) {

        if (shopId == null) {
            Object ctxShopId = toolContext.getContext().get("shopId");
            if (ctxShopId instanceof Long) {
                shopId = (Long) ctxShopId;
            } else if (ctxShopId instanceof Integer) {
                shopId = ((Integer) ctxShopId).longValue();
            }
        }
        if (shopId == null) {
            return "未提供店铺ID，无法查询优惠券。";
        }
        Result result = voucherService.queryVoucherOfShop(shopId);
        if (result.getSuccess()) {
            return result.getData().toString();
        } else {
            return "查询失败：" + result.getErrorMsg();
        }
    }

    @Tool(description = "购买指定优惠券，生成订单。如果优惠券状态为0是普通券，可以无限制下单。如果优惠券状态为0是普通券，当前用户限购一单。")
    public String createOrder(@ToolParam(description = "优惠券ID") Long voucherId,
                              ToolContext toolContext) { // <-- 添加 ToolContext 参数
        Long userId = getUserId(toolContext);
        if (userId == null) return "用户未登录，请先登录";

        // 临时设置用户到 UserHolder，让现有业务逻辑无需改动
        try {
            UserDTO userDTO = new UserDTO();
            userDTO.setId(userId);
            UserHolder.saveUser(userDTO);
            Result result = voucherOrderService.orderVoucher(voucherId);
            if (result.getSuccess()) {
                return "下单成功，订单号：" + result.getData();
            } else {
                return "下单失败：" + result.getErrorMsg();
            }
        } finally {
            UserHolder.removeUser(); // 确保清理，防止内存泄漏
        }
    }

    @Tool(description = "查询当前用户已购买的优惠券订单列表，可按状态筛选。状态：0-未使用，1-已使用，2-已过期。不传状态则查询所有订单。")
    public String queryMyOrders(
            @ToolParam(description = "订单状态，0未使用，1已使用，2已过期，不传则查全部", required = false) Integer status,
            ToolContext toolContext) {

        Long userId = getUserId(toolContext);
        if (userId == null) return "用户未登录，请先登录";

        // 设置用户上下文（复用原有业务逻辑）
        try {
            UserDTO userDTO = new UserDTO();
            userDTO.setId(userId);
            UserHolder.saveUser(userDTO);

            Result result;
            if (status == null) {
                result = voucherOrderService.getUserVouchers();
            } else {
                result = voucherOrderService.getUserVouchersByStatus(status);
            }

            if (result.getSuccess()) {
                return result.getData().toString();
            } else {
                return "查询订单失败：" + result.getErrorMsg();
            }
        } finally {
            UserHolder.removeUser();
        }
    }

    @Tool(description = "获取指定优惠券的核销二维码。当用户说'生成二维码'、'这个订单的二维码'时，参数 orderId 应该从对话历史中最近提到的订单号中提取，不要直接问用户。")
    public String getVoucherQrCode(
            @ToolParam(description = "优惠券订单ID（字符串类型，例如 '580016873555361807'）") String orderId,
            ToolContext toolContext) {

        Long userId = getUserId(toolContext);
        if (userId == null) return "用户未登录，请先登录";

        // 设置用户上下文
        try {
            UserDTO userDTO = new UserDTO();
            userDTO.setId(userId);
            UserHolder.saveUser(userDTO);

            // 查询订单
            VoucherOrder order = voucherOrderService.getById(orderId);
            if (order == null) {
                return "订单不存在";
            }
            if (!order.getUserId().equals(userId)) {
                return "无权操作此优惠券";
            }
            if (order.getStatus() != VoucherOrder.STATUS_UNUSED) {
                return "优惠券已使用或已过期，无法生成核销二维码";
            }
            if (order.getExpireTime() != null && order.getExpireTime().isBefore(LocalDateTime.now())) {
                return "优惠券已过期，无法生成核销二维码";
            }

            String qrCode = order.getQrCode();
            if (qrCode == null || qrCode.isEmpty()) {
                return "该优惠券未生成二维码，请联系客服";
            }

            // 返回二维码内容及使用说明（前端可根据此内容生成二维码图片）
            return "请出示以下二维码给店员扫码核销：\n" + qrCode + "\n（温馨提示：请勿自行点击核销，需由店员扫码完成）";

        } finally {
            UserHolder.removeUser();
        }
    }

    @Tool(description = "取消当前用户未使用的优惠券订单。仅限未使用且未过期的订单，取消后订单状态变为已取消。")
    public String cancelOrder(
            @ToolParam(description = "优惠券订单ID（字符串类型）") String orderId,
            ToolContext toolContext) {

        // 获取当前登录用户ID
        Object userIdObj = toolContext.getContext().get("userId");
        Long userId = null;
        if (userIdObj instanceof Long) {
            userId = (Long) userIdObj;
        } else if (userIdObj instanceof Integer) {
            userId = ((Integer) userIdObj).longValue();
        }
        if (userId == null) {
            return "用户未登录，请先登录";
        }

        // 设置用户上下文
        try {
            UserDTO userDTO = new UserDTO();
            userDTO.setId(userId);
            UserHolder.saveUser(userDTO);

            Result result = voucherOrderService.cancelOrder(orderId);
            if (result.getSuccess()) {
                return "取消成功：" + result.getData();
            } else {
                return "取消失败：" + result.getErrorMsg();
            }
        } finally {
            UserHolder.removeUser();
        }
    }

    @Tool(description = "获取当前购物车的总金额以及用户可用的优惠券列表（自动计算抵扣后实付金额，并按优惠力度排序）。")
    public String getCartWithVouchers(ToolContext toolContext) {
        Long userId = getUserId(toolContext);
        if (userId == null) return "用户未登录，请先登录";

        // 从 ToolContext 中获取当前店铺ID（前端在聊天请求的 context 中传入）
        Object shopIdObj = toolContext.getContext().get("shopId");
        Long shopId = null;
        if (shopIdObj instanceof Long) {
            shopId = (Long) shopIdObj;
        } else if (shopIdObj instanceof Integer) {
            shopId = ((Integer) shopIdObj).longValue();
        }
        if (shopId == null) {
            return "未指定店铺，请重新操作";
        }

        int totalAmount = cartService.getTotalAmount(userId, shopId);
        if (totalAmount == 0) {
            return "购物车为空";
        }

        // 1. 查询用户所有未使用且未过期的优惠券订单
        List<VoucherOrder> voucherOrders = voucherOrderService.list(
                new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getUserId, userId)
                        .eq(VoucherOrder::getStatus, VoucherOrder.STATUS_UNUSED)
                        .and(w -> w.isNull(VoucherOrder::getExpireTime)
                                .or().gt(VoucherOrder::getExpireTime, LocalDateTime.now()))
        );
        if (voucherOrders.isEmpty()) {
            return JSON.toJSONString(Map.of("totalAmount", totalAmount, "vouchers", Collections.emptyList()));
        }

        // 2. 批量查询优惠券详情，过滤出适用当前店铺的券
        List<Long> voucherIds = voucherOrders.stream()
                .map(VoucherOrder::getVoucherId)
                .collect(Collectors.toList());
        List<Voucher> vouchers = voucherService.listByIds(voucherIds);
        Map<Long, Voucher> voucherMap = vouchers.stream()
                .collect(Collectors.toMap(Voucher::getId, v -> v));

        List<Map<String, Object>> result = new ArrayList<>();
        for (VoucherOrder vo : voucherOrders) {
            Voucher voucher = voucherMap.get(vo.getVoucherId());
            if (voucher == null || !voucher.getShopId().equals(shopId)) continue;

            int discount = calculateDiscount(voucher, totalAmount);
            if (discount == 0 && voucher.getActualValue() > totalAmount) {
                continue; // 跳过面额超过订单金额的券
            }
            int finalAmount = totalAmount - discount;
            if (finalAmount < 0) finalAmount = 0;

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("voucherOrderId", vo.getId());
            map.put("voucherName", voucher.getTitle());
            map.put("originalAmount", totalAmount / 100.0);
            map.put("discount", discount / 100.0);
            map.put("finalAmount", finalAmount / 100.0);
            result.add(map);
        }

        // 按最终实付金额升序排序（即抵扣金额最大的排在前面）
        result.sort(Comparator.comparingDouble(m -> (double) m.get("finalAmount")));
        return JSON.toJSONString(Map.of("totalAmount", totalAmount / 100.0, "vouchers", result));
    }

    @Tool(description = "使用指定的优惠券订单ID结算当前购物车，自动处理多退少补。")
    public String checkoutWithVoucher(@ToolParam(description = "优惠券订单ID") Long voucherOrderId,
                                      ToolContext toolContext) {
        Long userId = getUserId(toolContext);
        if (userId == null) return "用户未登录，请先登录";

        // 从 ToolContext 中获取当前店铺ID（前端在聊天请求的 context 中传入）
        Object shopIdObj = toolContext.getContext().get("shopId");
        Long shopId = null;
        if (shopIdObj instanceof Long) {
            shopId = (Long) shopIdObj;
        } else if (shopIdObj instanceof Integer) {
            shopId = ((Integer) shopIdObj).longValue();
        }
        if (shopId == null) {
            return "未指定店铺，请重新操作";
        }

        int totalAmount = cartService.getTotalAmount(userId, shopId);
        if (totalAmount == 0) {
            return "购物车为空";
        }

        // 1. 校验优惠券
        VoucherOrder voucherOrder = voucherOrderService.getById(voucherOrderId);
        if (voucherOrder == null || voucherOrder.getStatus() != VoucherOrder.STATUS_UNUSED) {
            return "优惠券无效或已使用";
        }
        Voucher voucher = voucherService.getById(voucherOrder.getVoucherId());
        if (voucher == null) return "优惠券不存在";
        if (!voucher.getShopId().equals(shopId)) return "该优惠券不适用于当前店铺";
        if (voucherOrder.getExpireTime() != null && voucherOrder.getExpireTime().isBefore(LocalDateTime.now())) {
            return "优惠券已过期";
        }

        // 2. 计算抵扣金额（不超过订单总金额）
        int discount = calculateDiscount(voucher, totalAmount);
        if (discount == 0 && voucher.getActualValue() > totalAmount) {
            return "该优惠券面额超过订单金额，无法使用。请重新选择。";
        }
        int finalAmount = totalAmount - discount;  // 实付金额（分）

        // 将变量声明为 final，以便在 lambda 中使用
        final Long finalShopId = shopId;
        final int finalTotalAmount = totalAmount;
        final int finalFinalAmount = finalAmount;
        final VoucherOrder finalVoucherOrder = voucherOrder;

        // 3. 开启事务
        return transactionTemplate.execute(status -> {
            try {
                // 核销优惠券
                finalVoucherOrder.setStatus(VoucherOrder.STATUS_USED);
                finalVoucherOrder.setUseTime(LocalDateTime.now());
                voucherOrderService.updateById(finalVoucherOrder);

                // 创建点餐订单
                DiningOrder order = diningOrderService.createOrder(userId, finalShopId, finalTotalAmount, voucherOrderId, finalFinalAmount);

                // 处理多退少补
                StringBuilder msg = new StringBuilder();
                if (finalAmount > 0) {
                    try {
                        userService.updateBalance(userId, -finalAmount);
                    } catch (Exception e) {
                        return "余额不足，请充值后再结算";
                    }
                    // 需要用户支付差价
                    msg.append(String.format("已从余额扣除 %.2f 元。", finalAmount / 100.0));
                } else {
                    // 券面额大于订单金额，退还差额
                    int refund = -finalAmount;  // 实际 refund = discount - totalAmount
                    if (refund > 0) {
                        userService.updateBalance(userId, refund);
                        msg.append(String.format("券面额大于订单金额，已退还 %.2f 元至您的余额。", refund / 100.0));
                    } else {
                        msg.append("优惠券正好抵扣，无需支付。");
                    }
                }
                // 清空购物车
                cartService.clearCart(userId, finalShopId);
                return String.format("结算成功！订单号：%d，原价 %.2f 元，%s",
                        order.getId(), totalAmount / 100.0, msg.toString());
            } catch (Exception e) {
                status.setRollbackOnly();
                return "结算失败：" + e.getMessage();
            }
        });
    }

    private int calculateDiscount(Voucher voucher, int totalAmount) {
        if (voucher.getType() == 2) {
            // 满减券：解析 rules，格式如 "满100减30"
            String rules = voucher.getRules();
            if (rules == null || rules.isEmpty()) return 0;
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("满(\\d+)减(\\d+)");
            java.util.regex.Matcher matcher = pattern.matcher(rules);
            if (matcher.find()) {
                int threshold = Integer.parseInt(matcher.group(1)) * 100; // 转分
                int reduction = Integer.parseInt(matcher.group(2)) * 100;
                if (totalAmount >= threshold) {
                    return Math.min(reduction, totalAmount);
                }
            }
            return 0;
        } else {
            // 普通券/秒杀券：面额不能超过订单金额，否则不可用（防止套现）
            if (voucher.getActualValue() > totalAmount) {
                return 0;
            }
            return Math.toIntExact(Math.min(voucher.getActualValue(), totalAmount));
        }
    }

    // 辅助方法：从 ToolContext 中提取 userId
    private Long getUserId(ToolContext toolContext) {
        Object userIdObj = toolContext.getContext().get("userId");
        if (userIdObj instanceof Long) return (Long) userIdObj;
        if (userIdObj instanceof Integer) return ((Integer) userIdObj).longValue();
        return null;
    }
}