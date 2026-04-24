package com.AITaste.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.AITaste.dto.LoginFormDTO;
import com.AITaste.dto.Result;
import com.AITaste.dto.UserDTO;
import com.AITaste.entity.User;
import com.AITaste.entity.UserInfo;
import com.AITaste.mapper.UserMapper;
import com.AITaste.service.IUserInfoService;
import com.AITaste.service.IUserService;
import com.AITaste.utils.RedisConstants;
import com.AITaste.utils.RegexUtils;
import com.AITaste.utils.UserHolder;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.AITaste.utils.RedisConstants.*;
import static com.AITaste.utils.SystemConstants.*;


/**
 * 面试高频考点 & 实战答案（基于此用户登录服务实现）
 * ============================================================================
 * 1. 为什么不使用 Session 存储验证码和用户信息，而是使用 Redis？
 *    答：① 分布式系统中，Session 无法跨服务器共享（需引入 Session 共享中间件，增加复杂度）；
 *       ② Redis 集中存储，所有服务实例共享，天然支持分布式；
 *       ③ Redis 可设置过期时间，方便控制验证码和登录 token 的有效期；
 *       ④ 使用 Redis 的 String 或 Hash 结构，读写性能高，且支持原子操作。
 *
 * 2. 验证码发送和登录校验的流程是怎样的？
 *    答：发送验证码：校验手机号格式 → 生成6位随机数字 → 存入 Redis（key: LOGIN_CODE_KEY+phone，TTL 2分钟）→ 模拟发送。
 *       登录校验：校验手机号格式 → 从 Redis 获取验证码并比对 → 若正确，根据手机号查询用户 → 不存在则自动注册 →
 *       生成随机 token（UUID） → 将用户信息（UserDTO）转为 Hash 存入 Redis（key: LOGIN_USER_KEY+token，TTL 30分钟）→
 *       返回 token 给前端，后续请求携带 token 作为身份凭证。
 *
 * 3. 为什么使用 Hash 结构存储登录用户信息，而不是 String（JSON）？
 *    答：Hash 结构支持单独修改某个字段（如更新用户昵称），无需反序列化整个对象；
 *       且 Hash 的 hget、hset 等操作性能较高，内存占用相对紧凑。
 *       但缺点是无法直接设置整个 Hash 的过期时间，需要通过 expire 命令设置 key 的 TTL（本代码中已设置）。
 *
 * 4. 自动注册用户时，昵称如何生成？为什么要加前缀和随机字符串？
 *    答：使用常量前缀（USER_NICK_NAME_PREFIX，例如“user_”） + 10位随机字符串，保证昵称唯一且不暴露用户真实信息。
 *       同时避免用户首次登录时需要额外填写昵称，提升用户体验。
 *
 * 5. 验证码的 TTL 为什么设置为 2 分钟？登录 token 的 TTL 为什么设置为 30 分钟？
 *    答：验证码有效期短（2分钟）可降低被截获后滥用的风险；用户输入验证码通常在 1 分钟内完成。
 *       token 有效期 30 分钟是折中考虑：用户可能短暂离开页面，过期太短需频繁登录；过长则增加安全风险。
 *       实际生产环境可结合“续期”机制（用户活跃时自动延长 TTL）。
 *
 * 6. 如何保证手机号格式的正确性？正则表达式如何设计？
 *    答：使用 RegexUtils.isPhoneInvalid 校验，内部正则匹配国内手机号（以1开头，第二位3-9，后9位数字）。
 *       如果不符合格式，直接返回错误，避免无效手机号调用短信接口造成浪费。
 *
 * 7. 如果用户已经登录，如何获取当前用户信息？如何实现用户退出？
 *    答：前端每次请求在 Header 中携带 token，后端拦截器解析 token，从 Redis 中获取用户信息并存入 ThreadLocal（UserHolder）。
 *       退出时，删除 Redis 中对应的 key（LOGIN_USER_KEY + token）即可。本服务未展示拦截器，但 UserHolder 工具类已预留。
 *
 * 8. 这种基于 Redis 的登录方案有什么潜在问题？如何优化？
 *    答：① 每个请求都需要查询 Redis，增加一次网络开销 → 可考虑在本地缓存用户信息（Caffeine），设置短过期；
 *       ② token 被窃取后，攻击者可伪造身份 → 应使用 HTTPS，并设置较短有效期，或结合设备指纹、IP 校验；
 *       ③ 用户退出时若未主动删除 token，会占用 Redis 内存直到过期 → 可增加定时任务清理过期 key（Redis 自带过期机制已足够）；
 *       ④ 单点登录（SSO）场景需要更复杂的 token 管理。
 *
 * 9. 如果同一手机号在短时间内多次请求发送验证码，如何限制频率？
 *    答：本示例未实现，但常见做法是：在 Redis 中记录上次发送时间，例如 key: "send_code_limit:"+phone，
 *       设置 60 秒过期，若存在则提示“请勿频繁发送”。也可以使用滑动窗口或令牌桶算法。
 *
 * 10. 如何防止验证码暴力破解？
 *     答：① 限制同一手机号错误尝试次数（如 5 次后锁定 10 分钟）；
 *       ② 增加图形验证码或滑动验证；
 *       ③ 验证码设计为 6 位数字，随机组合，有效期短，暴力破解成本高。
 *
 * 实战要点：
 * - Redis 中验证码 key 建议加上业务前缀，便于管理和区分。
 * - 登录 token 应使用安全的随机生成器（UUID 或 SecureRandom），避免可预测性。
 * - 存储用户信息到 Hash 时，需将非字符串值转为字符串（CopyOptions.setFieldValueEditor 已处理）。
 * - 注意 Redis 的序列化方式（本示例默认使用 StringRedisTemplate，值均为 String）。
 * - 用户登出时，应当同时删除 ThreadLocal 中的用户信息，避免内存泄漏。
 * - 生产环境建议使用 Redis Cluster 或 Sentinel 保证高可用。
 * - 敏感操作（如修改密码）应要求重新验证验证码或密码。
 * ============================================================================
 */

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private PasswordEncoder passwordEncoder;

    /**
     * 发送手机验证码
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 4.保存验证码到 session
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5.发送验证码
        log.info("发送短信验证码成功，验证码：{}", code);
        // 返回ok
        return Result.ok();
    }

    /**
     * 验证码登录
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.从redis获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            // 不一致，报错
            return Result.fail("验证码错误");
        }

        // 4.一致，根据手机号查询用户 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();

        // 5.判断用户是否存在
        if (user == null) {
            // 6.不存在，创建新用户并保存
            user = createUserWithPhone(phone);
        }

        // 7.保存用户信息到 redis中
        // 7.1.随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 7.2.将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 7.3.存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 7.4.设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("userId", user.getId());
        return Result.ok(result);
    }

    private User createUserWithPhone(String phone) {
        // 1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2.保存用户
        save(user);
        return user;
    }

    /**
     * 密码登录
     */
    @Override
    public Result passwordLogin(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }

        // 2. 根据手机号查询用户
        User user = query().eq("phone", phone).one();
        if (user == null) {
            return Result.fail("用户不存在，请先使用验证码登录注册");
        }

        // 3. 校验密码
        String rawPassword = loginForm.getPassword();
        if (StrUtil.isBlank(user.getPassword())) {
            // 用户未设置密码
            return Result.fail("您尚未设置密码，请使用验证码登录，登录后可在个人中心设置密码");
        }
        // 假设密码使用 BCrypt 加密存储（示例：使用 BCryptPasswordEncoder）
        if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
            return Result.fail("密码错误");
        }

        // 4. 生成 token 并保存到 Redis（与验证码登录保持一致）
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("userId", user.getId());
        return Result.ok(result);
    }

    @Override
    @Transactional
    public Result updatePassword(String oldPassword, String newPassword) {
        // 1. 获取当前登录用户
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("未登录");
        }
        Long userId = currentUser.getId();

        // 2. 参数校验
        if (StrUtil.isBlank(newPassword)) {
            return Result.fail("新密码不能为空");
        }
        if (newPassword.length() < 6 || newPassword.length() > 20) {
            return Result.fail("密码长度应为6-20位");
        }

        // 3. 查询用户当前密码（加密存储）
        User user = getById(userId);
        if (user == null) {
            return Result.fail("用户不存在");
        }

        String storedPassword = user.getPassword();

        if (StrUtil.isNotBlank(storedPassword)) {
            // 已有密码，必须校验旧密码
            if (StrUtil.isBlank(oldPassword)) {
                return Result.fail("请输入原密码");
            }
            if (!passwordEncoder.matches(oldPassword, storedPassword)) {
                return Result.fail("原密码错误");
            }
        }

        // 5. 加密新密码并更新
        String encodedNewPwd = passwordEncoder.encode(newPassword);
        user.setPassword(encodedNewPwd);
        boolean success = updateById(user);
        if (!success) {
            return Result.fail("修改密码失败，请稍后重试");
        }

        // 修改成功后，清除当前用户的登录 token，强制重新登录
        try {
            HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
            String token = request.getHeader("Authorization");
            if (StrUtil.isNotBlank(token) && token.startsWith("Bearer ")) {
                token = token.substring(7);
                stringRedisTemplate.delete(LOGIN_USER_KEY + token);
            }
        } catch (Exception e) {
            log.warn("清除登录 token 失败，不影响密码修改", e);
        }
        // 清除 ThreadLocal 中的用户信息
        UserHolder.removeUser();

        return Result.ok("密码修改成功，下次登录请使用新密码");
    }

    /**
     * 登出功能
     * @return 无
     */
    @Override
    public Result logout(HttpServletRequest request){
        // 1. 获取请求头中的Authorization
        String token = request.getHeader("Authorization");
        if (StrUtil.isNotBlank(token) && token.startsWith("Bearer ")) {
            // 2. 截取真实Token
            token = token.substring(7);
            // 3. 删除Redis中存储的Token
            stringRedisTemplate.delete(RedisConstants.LOGIN_USER_KEY + token);
        }
        // 4. 清除ThreadLocal中的用户信息
        UserHolder.removeUser();
        return Result.ok();
    }

    /**
     * 更新当前登录用户的基本信息（昵称、头像）
     * @param user 包含需要更新的字段（nickName, icon）
     * @return Result
     */
    @Override
    public Result updateMe(@RequestBody User user){
        // 1. 获取当前登录用户ID
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("未登录");
        }
        Long userId = currentUser.getId();

        // 2. 只允许更新 nickName 和 icon，防止恶意修改其他字段
        LambdaUpdateWrapper<User> updateWrapper = Wrappers.lambdaUpdate(User.class)
                .eq(User::getId, userId);

        // 只有当字段不为空时才更新
        if (StrUtil.isNotBlank(user.getNickName())) {
            updateWrapper.set(User::getNickName, user.getNickName());
        }
        if (StrUtil.isNotBlank(user.getIcon())) {
            updateWrapper.set(User::getIcon, user.getIcon());
        }

        // 3. 执行更新
        boolean success = update(updateWrapper);
        if (!success) {
            return Result.fail("更新基本信息失败");
        }

        // 4. 更新ThreadLocal中的用户信息（可选，保持一致性）
        if (StrUtil.isNotBlank(user.getNickName())) {
            currentUser.setNickName(user.getNickName());
        }
        if (StrUtil.isNotBlank(user.getIcon())) {
            currentUser.setIcon(user.getIcon());
        }
        UserHolder.saveUser(currentUser);
        return Result.ok();
    }

    /**
     * 更新当前登录用户的详细信息（个人介绍、性别、城市、生日）
     * @param userInfo 包含需要更新的字段（introduce, gender, city, birthday）
     * @return Result
     */
    @Override
    public Result updateInfo(@RequestBody UserInfo userInfo){
        // 1. 获取当前登录用户ID
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("未登录");
        }
        Long userId = currentUser.getId();
        // 2. 查询是否已有详细信息记录
        UserInfo existingInfo = userInfoService.getById(userId);
        if (existingInfo == null) {
            // 没有则新建
            existingInfo = new UserInfo();
            existingInfo.setUserId(userId);
        }
        // 3. 只更新允许修改的字段
        if (StrUtil.isNotBlank(userInfo.getIntroduce())) {
            existingInfo.setIntroduce(userInfo.getIntroduce());
        }
        if (userInfo.getGender() != null) {
            existingInfo.setGender(userInfo.getGender());
        }
        if (StrUtil.isNotBlank(userInfo.getCity())) {
            existingInfo.setCity(userInfo.getCity());
        }
        if (userInfo.getBirthday() != null) {
            existingInfo.setBirthday(userInfo.getBirthday());
        }
        // 4. 保存或更新
        boolean success = userInfoService.saveOrUpdate(existingInfo);
        if (!success) {
            return Result.fail("更新详细信息失败");
        }
        return Result.ok();
    }

    /**
     * 上传头像（支持图片文件）
     * @param file 上传的文件
     * @return 返回新头像的访问URL
     */
    @Override
    public Result uploadAvatar(@RequestParam("file") MultipartFile file){
        // 1. 获取当前登录用户ID
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("未登录");
        }

        // 2. 校验文件类型和大小（示例：仅允许jpg/png，最大2MB）
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || (!originalFilename.endsWith(".jpg") && !originalFilename.endsWith(".png") && !originalFilename.endsWith(".jpeg"))) {
            return Result.fail("只支持 JPG、PNG 格式的图片");
        }
        if (file.getSize() > 2 * 1024 * 1024) {
            return Result.fail("图片大小不能超过 2MB");
        }

        try {
            // 3. 生成唯一文件名，保存到本地或云存储（此处示例保存到本地项目静态目录）
            String newFileName = UUID.randomUUID() + "_" + originalFilename;
            // 注意：实际部署时需要配置绝对路径或使用OSS，以下仅为示例
            String uploadDir = "C:/nginx-1.18.0/html/hmdp/imgs/icons/";
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }
            Path filePath = uploadPath.resolve(newFileName);
            file.transferTo(filePath.toFile());

            // 4. 返回可访问的URL（根据实际项目配置，可能需要映射静态资源）
            String avatarUrl = "/imgs/icons/" + newFileName;  // 假设配置了静态资源映射
            return Result.ok(avatarUrl);
        } catch (IOException e) {
            log.error("头像上传失败", e);
            return Result.fail("头像上传失败");
        }
    }

    @Override
    @Transactional
    public void updateBalance(Long userId, int delta) {
        // 如果是扣减（delta < 0），需要先检查余额是否足够
        if (delta < 0) {
            int currentBalance = getBalance(userId);
            if (currentBalance + delta < 0) {
                throw new RuntimeException("余额不足");
            }
        }
        int affected = baseMapper.updateBalance(userId, delta);
        if (affected == 0) {
            throw new RuntimeException("用户不存在或更新失败");
        }
    }

    @Override
    public int getBalance(Long userId) {
        User user = getById(userId);
        return user == null ? 0 : user.getBalance();
    }
}