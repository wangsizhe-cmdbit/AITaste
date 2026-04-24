package com.AITaste.service;


import com.AITaste.entity.UserProfile;

public interface IUserProfileService {

    void updateTag(Long userId, String tagName, Object tagValue);

    UserProfile getUserProfile(Long userId);
}
