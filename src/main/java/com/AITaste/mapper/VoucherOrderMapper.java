package com.AITaste.mapper;

import com.AITaste.VO.UserVoucherVO;
import com.AITaste.entity.VoucherOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface VoucherOrderMapper extends BaseMapper<VoucherOrder> {
        /**
         * 查询用户的所有优惠券（关联 voucher 和 shop 表）
         * @param userId 用户ID
         * @param status 状态筛选：0未使用，1已使用，2已过期，传null查全部
         */
        List<UserVoucherVO> selectUserVouchers(@Param("userId") Long userId,
                                               @Param("status") Integer status);
}