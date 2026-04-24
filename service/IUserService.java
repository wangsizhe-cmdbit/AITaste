package com.AITaste.service;

import com.AITaste.dto.LoginFormDTO;
import com.AITaste.dto.Result;
import com.AITaste.entity.User;
import com.AITaste.entity.UserInfo;
import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.multipart.MultipartFile;

public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result passwordLogin(LoginFormDTO loginForm, HttpSession session);

    Result updatePassword(String oldPassword, String newPassword);

    Result logout(HttpServletRequest request);

    Result updateMe(User user);

    Result updateInfo(UserInfo userInfo);

    Result uploadAvatar(MultipartFile file);

    void updateBalance(Long userId, int delta);

    int getBalance(Long userId);
}
