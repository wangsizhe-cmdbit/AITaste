package com.AITaste.mapper;

import com.AITaste.entity.UserInfo;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface UserInfoMapper extends BaseMapper<UserInfo> {

    /**
     * 安全更新关注数（不会让数值变成负数）
     */
    @Update("UPDATE tb_user_info SET followee = GREATEST(followee + #{delta}, 0) WHERE user_id = #{userId}")
    int updateFolloweeSafe(@Param("userId") Long userId, @Param("delta") int delta);

    /**
     * 安全更新粉丝数（不会让数值变成负数）
     */
    @Update("UPDATE tb_user_info SET fans = GREATEST(fans + #{delta}, 0) WHERE user_id = #{userId}")
    int updateFansSafe(@Param("userId") Long userId, @Param("delta") int delta);
}
