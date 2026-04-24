package com.AITaste.mapper;

import com.AITaste.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface UserMapper extends BaseMapper<User> {
    @Update("UPDATE tb_user SET balance = balance + #{delta} WHERE id = #{userId} AND balance + #{delta} >= 0")
    int updateBalance(@Param("userId") Long userId, @Param("delta") int delta);

}
