package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;


@Component
@Slf4j
/*
* 全局ID生成器(redis自增)
* */
public class RedisIdWork {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final int COUNT_BITS = 32;

    public long getId (String keyPre) {
        //生成时间戳
        LocalDateTime beginTime = LocalDateTime.of(2026, 5, 1, 0, 0, 0);
        long beginSecond = beginTime.toEpochSecond(ZoneOffset.UTC);
        LocalDateTime nowTime = LocalDateTime.now();
        long nowSecond = nowTime.toEpochSecond(ZoneOffset.UTC);
        long time = nowSecond - beginSecond;
        //获得序列号
            //获得当前时间并格式化
        String date = nowTime.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
            //redis值自增
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPre + ":" + date);
        //拼接id (先将时间戳左移32位再通过位与操作拼接)
        return time << COUNT_BITS | count;
    }
}
