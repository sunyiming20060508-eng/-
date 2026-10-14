package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.如果不符合格式，返回错误
            return Result.fail("手机号格式错误");
        }
        //3.符合格式，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码到 Session
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code,  LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5.发送验证码（本地演示：直接打印到控制台）
        log.debug("验证码：" + code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        //2.校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !cacheCode.equals(loginForm.getCode())) {
            return Result.fail("验证码错误");
        }
        //3.查询用户
        User user = query().eq("phone", phone).one();
        //4.判断用户是否存在
        if (user == null) {
            //5.不存在，创建用户
            user = createUserWithPhone(phone);
        }
        String token = UUID.randomUUID().toString();
        //6.保存用户（转为 UserDTO，保护密码等敏感信息）
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //7.保存用户到 Redis（id是Long类型，需转为String才能存入StringRedisTemplate）
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);
        userMap.replaceAll((k, v) -> v != null ? v.toString() : null);
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
