package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    /*
    * 负责拦截所有的请求，然后更新token的时间
    * */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获得session
//        HttpSession session = request.getSession();
        //获得User对象
//        Object user = session.getAttribute("user");

        //通过请求头获得token
        String token = request.getHeader("authorization");
        //判断是否有token
        if (token == null) {
            return true;
        }
        //读取redis相应对象
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);
        //判断是否有相关数据
        if (map.isEmpty()) {
            return true;
        }
        //将map转为对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(map, new UserDTO(), false);

        //将对象放入线程中
        UserHolder.saveUser(userDTO);

        //每次操作都要刷新token的有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
