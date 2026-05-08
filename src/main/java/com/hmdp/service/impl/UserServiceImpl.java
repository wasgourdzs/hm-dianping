package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /*
     * 发送验证码
     * */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //验证手机号是否合规
        if (RegexUtils.isPhoneInvalid(phone)) {
            //不合规返回错误信息
            return Result.fail("手机号不合规");
        }
        //合规，生成验证码 （利用工具包）
        String code = RandomUtil.randomNumbers(6);
        //保存到session
//        session.setAttribute("code", code);

        //保存到redis (设置有效期)
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code,
                                                RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //发送验证码 (跳过)
        log.info("发送验证码：{}", code);
        return Result.ok();
    }

    /*
    * 用户登录
    * */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号是否合规
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机号不合规");
        }
        //校验验证码是否正确
//        Object code = session.getAttribute("code");
//        if (code == null || !loginForm.getCode().equals(code.toString())) {
//            return Result.fail("验证码错误");
//        }

        //使用redis
        String code = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + loginForm.getPhone());
        if (loginForm.getCode() == null|| !loginForm.getCode().equals(code)) {
            return Result.fail("验证码错误");
        }


        //根据手机号判断用户是否存在
        User user = query().eq("phone", loginForm.getPhone()).one();
        //不存在则创建
        if (user == null) {
            user = createUserWithPhone(loginForm.getPhone());
        }
        //将用户加入到session
//        UserDTO userDTO = new UserDTO();
//        BeanUtils.copyProperties(user, userDTO);
//        session.setAttribute("user", userDTO);

        //生成token
        String token = UUID.randomUUID().toString();
        //将用户加入到redis
        UserDTO userDTO = new UserDTO();
        BeanUtil.copyProperties(user, userDTO);
            //将对象转为map
        Map<String, Object> map = BeanUtil.beanToMap(userDTO);
            //处理id不能转为字符串类型
        map.put("id", userDTO.getId().toString());

        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token, map);
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        //返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
