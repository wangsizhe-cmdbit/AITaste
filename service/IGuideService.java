package com.AITaste.service;


import com.AITaste.dto.GuideResponse;

public interface IGuideService{

    GuideResponse generateGuide(Long userId);
}
