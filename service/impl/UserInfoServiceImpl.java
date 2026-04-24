package com.AITaste.service.impl;

import com.AITaste.entity.UserInfo;
import com.AITaste.mapper.UserInfoMapper;
import com.AITaste.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
