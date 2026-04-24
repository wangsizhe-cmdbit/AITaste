package com.AITaste.service.impl;

import com.AITaste.entity.UserProfile;
import com.AITaste.mapper.UserProfileMapper;
import com.AITaste.service.IUserProfileService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class UserProfileServiceImpl implements IUserProfileService {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;
    @Resource
    private UserProfileMapper userProfileMapper;

    private static final String PROFILE_KEY_PREFIX = "user:profile:";

    // 更新标签（已有，保持不变）
    public void updateTag(Long userId, String tagName, Object tagValue) {
        if (userId == null || tagName == null) return;
        String redisKey = PROFILE_KEY_PREFIX + userId;
        redisTemplate.opsForHash().put(redisKey, tagName, tagValue);
        redisTemplate.expire(redisKey, 30, TimeUnit.DAYS);
        updateProfileToDB(userId, tagName, tagValue);
    }

    @Async
    public void updateProfileToDB(Long userId, String tagName, Object tagValue) {
        LambdaQueryWrapper<UserProfile> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserProfile::getUserId, userId);
        UserProfile profile = userProfileMapper.selectOne(wrapper);
        if (profile == null) {
            profile = new UserProfile();
            profile.setUserId(userId);
            setTagValue(profile, tagName, tagValue);
            userProfileMapper.insert(profile);
        } else {
            // 数据库有记录，更新
            setTagValue(profile, tagName, tagValue);
            userProfileMapper.updateById(profile);
        }
    }

    private void setTagValue(UserProfile profile, String tagName, Object tagValue) {
        switch (tagName) {
            case "taste":
                profile.setTaste((String) tagValue);
                break;
            case "avgPriceLevel":
                profile.setAvgPriceLevel((Integer) tagValue);
                break;
            case "frequentArea":
                profile.setFrequentArea((String) tagValue);
                break;
            default:
        }
    }

    public UserProfile getUserProfile(Long userId) {
        String redisKey = PROFILE_KEY_PREFIX + userId;
        // 尝试从 Redis 中获取所有标签并组装成 UserProfile
        Map<Object, Object> hash = redisTemplate.opsForHash().entries(redisKey);
        if (!hash.isEmpty()) {
            UserProfile profile = new UserProfile();
            profile.setUserId(userId);
            if (hash.containsKey("taste")) profile.setTaste((String) hash.get("taste"));
            if (hash.containsKey("avgPriceLevel")) profile.setAvgPriceLevel((Integer) hash.get("avgPriceLevel"));
            if (hash.containsKey("frequentArea")) profile.setFrequentArea((String) hash.get("frequentArea"));
            return profile;
        }
        // Redis 未命中，从 DB 查询
        LambdaQueryWrapper<UserProfile> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserProfile::getUserId, userId);
        UserProfile profile = userProfileMapper.selectOne(wrapper);
        if (profile != null) {
            // 回填 Redis
            redisTemplate.opsForHash().putAll(redisKey, profile.toMap());
            redisTemplate.expire(redisKey, 30, TimeUnit.DAYS);
            return profile;
        }
        return new UserProfile();
    }
}
