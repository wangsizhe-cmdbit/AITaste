package com.AITaste.controller;


import cn.hutool.core.bean.BeanUtil;
import com.AITaste.VO.UserProfileVO;
import com.AITaste.dto.LoginFormDTO;
import com.AITaste.dto.Result;
import com.AITaste.dto.UserDTO;
import com.AITaste.entity.User;
import com.AITaste.entity.UserInfo;
import com.AITaste.service.IUserInfoService;
import com.AITaste.service.IUserService;
import com.AITaste.utils.UserHolder;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    /**
     * 发送手机验证码
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        // 发送短信验证码并保存验证码
        return userService.sendCode(phone, session);
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){
        // 实现登录功能
        return userService.login(loginForm, session);
    }

    /**
     * 密码登录
     */
    @PostMapping("/passwordLogin")
    public Result passwordLogin(@RequestBody LoginFormDTO loginForm, HttpSession session){
        return userService.passwordLogin(loginForm, session);
    }

    /**
     * 修改密码
     */
    @PutMapping("/password")
    public Result updatePassword(@RequestBody Map<String, String> params) {
        String oldPassword = params.get("oldPassword");
        String newPassword = params.get("newPassword");
        return userService.updatePassword(oldPassword, newPassword);
    }

    /**
     * 登出功能
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(HttpServletRequest request) {
        return userService.logout(request);
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId) {
        // 1. 查询基本信息
        User user = userService.getById(userId);
        if (user == null) {
            return Result.fail("用户不存在");
        }
        // 2. 查询详细信息
        UserInfo userInfo = userInfoService.getById(userId);
        // 3. 组装 VO
        UserProfileVO vo = new UserProfileVO();
        BeanUtil.copyProperties(user, vo);  // 复制 id, nickName, icon
        if (userInfo != null) {
            vo.setIntroduce(userInfo.getIntroduce());
            if (userInfo.getGender() != null) {
                vo.setGender(userInfo.getGender() ? "女" : "男");
            }
            vo.setCity(userInfo.getCity());
            vo.setBirthday(userInfo.getBirthday());
            vo.setFans(userInfo.getFans());
            vo.setFollowee(userInfo.getFollowee());
            vo.setCredits(userInfo.getCredits());
            vo.setLevel(userInfo.getLevel());
        } else {
            // 没有详细信息时设置默认值
            vo.setIntroduce("");
            vo.setGender("");
            vo.setCity("");
            vo.setBirthday(null);
        }
        return Result.ok(vo);
    }

    /**
     * 获取当前登录用户的信息
     * @return 当前用户信息
     */
    @GetMapping("/me")
    public Result getCurrentUser() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录");
        }
        User fullUser = userService.getById(user.getId());
        return Result.ok(fullUser);
    }

    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long userId){
        // 查询详情
        User user = userService.getById(userId);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 返回
        return Result.ok(userDTO);
    }

    /**
     * 更新当前登录用户的基本信息（昵称、头像）
     * @param user 包含需要更新的字段（nickName, icon）
     * @return Result
     */
    @PutMapping("/me")
    public Result updateMe(@RequestBody User user) {
        return userService.updateMe(user);
    }

    /**
     * 更新当前登录用户的详细信息（个人介绍、性别、城市、生日）
     * @param userInfo 包含需要更新的字段（introduce, gender, city, birthday）
     * @return Result
     */
    @PutMapping("/info")
    public Result updateInfo(@RequestBody UserInfo userInfo) {
        return userService.updateInfo(userInfo);
    }

    /**
     * 上传头像（支持图片文件）
     * @param file 上传的文件
     * @return 返回新头像的访问URL
     */
    @PostMapping("/upload/avatar")
    public Result uploadAvatar(@RequestParam("file") MultipartFile file) {
        return userService.uploadAvatar(file);
    }
}