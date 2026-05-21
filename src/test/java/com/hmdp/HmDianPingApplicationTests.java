package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.thread.ThreadUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWork;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import javax.annotation.Resource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;
    @Autowired
    private CacheClient cacheClient;
    @Autowired
    private RedisIdWork redisIdWork;
    @Autowired
    private RedissonClient redissonClient;

    private static final ExecutorService es = Executors.newFixedThreadPool(500);

    //店铺数据预热
    @Test
    public void test () {
//        shopService.saveShop2Redis(1L, 10L);
        for (long i = 1; i < 15; i++) {
            Shop shop = shopService.getById(i);
            cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY + i, shop, 10L, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testRedisIdWork () throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWork.getId("order");
                System.out.println(id);
            }
            latch.countDown();
        };

        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }

    private RLock lock;

    @BeforeEach
    public void setUp () {
        lock = redissonClient.getLock("order");
    }

    @Test
    //测试可重入锁
    public void method1 () throws InterruptedException {
        boolean tryLock = lock.tryLock(1L, TimeUnit.SECONDS);
        if (!tryLock) {
            System.out.println("1获取锁失败...");
            return;
        }
        try {
            System.out.println("1获得锁成功...");
            method2();
            System.out.println("1执行业务...");
        } finally {
            System.out.println("1释放锁...");
            lock.unlock();
        }
    }

    public void method2 () {
        boolean tryLock = lock.tryLock();
        if (!tryLock) {
            System.out.println("2获得锁失败..");
            return;
        }
        try {
            System.out.println("2获得锁成功..");
            System.out.println("2执行业务..");
        } finally {
            System.out.println("2释放锁...");
            lock.unlock();
        }
    }

    @Autowired
    private UserServiceImpl userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //登录1000个用户
    @Test
    public void testMultiLogin () throws IOException {
        List<User> list = userService.lambdaQuery().last("limit 1000").list();
        for (User user : list) {
            String token = UUID.randomUUID().toString(true);
            UserDTO userDTO = new UserDTO();
            BeanUtil.copyProperties(user, userDTO);
            Map<String, Object> map = BeanUtil.beanToMap(userDTO);
            map.put("id", user.getId().toString());
            stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token, map);
            stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        }
        Set<String> set = stringRedisTemplate.keys(RedisConstants.LOGIN_USER_KEY + "*");
        FileWriter fileWriter = new FileWriter(new File(System.getProperty("user.dir") + "\\tokens.txt"));
        BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);
        for (String s : set) {
            String token = s.substring(RedisConstants.LOGIN_USER_KEY.length());
            String text = token + "\n";
            bufferedWriter.write(text);
        }
        bufferedWriter.close();
        fileWriter.close();
    }
}
