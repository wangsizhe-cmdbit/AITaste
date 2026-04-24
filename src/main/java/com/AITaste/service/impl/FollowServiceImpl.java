package com.AITaste.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.AITaste.dto.Result;
import com.AITaste.dto.UserDTO;
import com.AITaste.entity.Follow;
import com.AITaste.entity.UserInfo;
import com.AITaste.mapper.FollowMapper;
import com.AITaste.mapper.UserInfoMapper;
import com.AITaste.service.IFollowService;
import com.AITaste.service.IUserInfoService;
import com.AITaste.service.IUserService;
import com.AITaste.utils.UserHolder;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;
    @Resource
    private UserInfoMapper userInfoMapper;
    @Resource
    private IUserInfoService userInfoService;

    /**
     * 确保 tb_user_info 中存在指定用户的记录，如果不存在则插入默认记录
     * @param userId 用户ID
     */
    private void ensureUserInfoExists(Long userId) {
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            UserInfo newInfo = new UserInfo();
            newInfo.setUserId(userId);
            newInfo.setFollowee(0);
            newInfo.setFans(0);
            userInfoService.save(newInfo);
            log.debug("初始化用户 {} 的 tb_user_info 记录", userId);
        }
    }

    /**
     * 安全更新用户的关注数或粉丝数（原子操作，自动处理记录缺失）
     * @param userId 用户ID
     * @param delta  变化量（+1 或 -1）
     * @param type   "followee" 或 "fans"
     */
    private void updateUserCountSafe(Long userId, int delta, String type) {
        int rows = 0;
        if ("followee".equals(type)) {
            rows = userInfoMapper.updateFolloweeSafe(userId, delta);
        } else if ("fans".equals(type)) {
            rows = userInfoMapper.updateFansSafe(userId, delta);
        }
        // 如果更新影响行数为0，说明记录不存在，尝试插入后再重试一次
        if (rows == 0) {
            ensureUserInfoExists(userId);
            if ("followee".equals(type)) {
                rows = userInfoMapper.updateFolloweeSafe(userId, delta);
            } else {
                rows = userInfoMapper.updateFansSafe(userId, delta);
            }
        }
    }

    @Override
    @Transactional
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        // 1.判断到底是关注还是取关
        if (isFollow) {
            // 2.关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                // 更新 Redis 集合
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
                // 更新当前用户的关注数（+1）
                updateUserCountSafe(userId, 1, "followee");
                // 更新被关注用户的粉丝数（+1）
                updateUserCountSafe(followUserId, 1, "fans");
            }
        } else {
            // 3.取关，删除 delete from tb_follow where user_id = ? and follow_user_id = ?
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if (isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
                // 更新当前用户的关注数（-1）
                updateUserCountSafe(userId, -1, "followee");
                // 更新被关注用户的粉丝数（-1）
                updateUserCountSafe(followUserId, -1, "fans");
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Integer count = Math.toIntExact(query().eq("user_id", userId).eq("follow_user_id", followUserId).count());
        // 3.判断
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        // 2.求交集
        String key2 = "follows:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()) {
            // 无交集
            return Result.ok(Collections.emptyList());
        }
        // 3.解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 4.查询用户
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
