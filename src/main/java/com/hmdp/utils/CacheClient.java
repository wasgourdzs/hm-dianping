package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /*
    * 存入redis，设置过期时间
    * */
    public <R> void set (String key, R value, Long time, TimeUnit unit) {
        //转为json字符串
        String jsonStr = JSONUtil.toJsonStr(value);
        //写入redis
        stringRedisTemplate.opsForValue().set(key, jsonStr, time, unit);
    }

    /*
    * 存入redis，设置逻辑过期时间
    * */
    public <R> void setWithLogicalExpire (String key, R value, Long time, TimeUnit unit) {
        //封装为RedisData
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //转为json字符串
        String jsonStr = JSONUtil.toJsonStr(redisData);
        //写入redis
        stringRedisTemplate.opsForValue().set(key, jsonStr);
    }

    /*
    * 查询redis，并解决缓存穿透问题
    * */
    public <R, ID> R queryWithPassThrough (String keyPre, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        //redis数据库查数据
        String shopJSON = stringRedisTemplate.opsForValue().get(keyPre + id);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJSON)) {
            //存在直接返回
            return JSONUtil.toBean(shopJSON, type);
        }
        //判断是否为空，是否是重复请求（穿透问题）
        if (shopJSON != null) {
            return null;
        }
        //不存在则去mysql数据库查
        R r = dbFallback.apply(id);
        //判断是否存在
        if (r == null) {
            //将空值写入redis，解决缓存穿透问题
            stringRedisTemplate.opsForValue().set(keyPre + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误
            return null;
        }
        //存在则写到redis中，并返回
        set(keyPre + id, r, time, unit);
        return r;
    }

    /*
     * 缓存击穿问题(互斥锁)
     * */
    public <R, ID> R queryWithMutex (String keyPre, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        //redis数据库查数据
        String shopJSON = stringRedisTemplate.opsForValue().get(keyPre + id);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJSON)) {
            //存在直接返回
            return JSONUtil.toBean(shopJSON, type);
        }
        //判断是否为空，是否是重复请求（穿透问题）
        if (shopJSON != null) {
            return null;
        }
        R r = null;
        try {
            //获得锁
            boolean flag = getLock(RedisConstants.LOCK_SHOP_KEY + id);
            //如果失败，则休眠，之后再重试
            if (!flag) {
                Thread.sleep(50);
                return queryWithMutex(keyPre, id, type, dbFallback, time, unit);
            }
            //二次判断redis是否存在数据
            shopJSON = stringRedisTemplate.opsForValue().get(keyPre + id);
            if (StrUtil.isNotBlank(shopJSON)) {
                return JSONUtil.toBean(shopJSON, type);
            }
            if (shopJSON != null) {
                return null;
            }
            //成功则去mysql数据库查
            r = dbFallback.apply(id);
            //模拟线程创建时间
            Thread.sleep(200);
            //判断是否存在
            if (r == null) {
                //将空值写入redis，解决缓存穿透问题
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                //不存在返回错误
                return null;
            }
            //存在则写到redis中，并返回
            set(keyPre + id, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            unLock(RedisConstants.LOCK_SHOP_KEY + id);
        }
        return r;
    }

    /*
     * 线程池
     * */
    private static final ExecutorService CACHE_REBUILD_SERVICE = Executors.newFixedThreadPool(10);

    /*
    * 查询redis，解决缓存击穿问题（逻辑过期）
    * */
    public <R, ID> R queryWithLogicalExpire (String keyPre, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        //redis数据库查数据
        String shopJSON = stringRedisTemplate.opsForValue().get(keyPre + id);
        //判断是否存在
        if (StrUtil.isBlank(shopJSON)) {
            //不存在直接返回不存在
            return null;
        }
        //存在，获得RedisData对象
        RedisData redisData = JSONUtil.toBean(shopJSON, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //没过期，直接返回
            return r;
        }
        //过期
        //获得锁
        if (getLock(RedisConstants.LOCK_SHOP_KEY + id)) {
            //二次检查是否过期
            String s = stringRedisTemplate.opsForValue().get(keyPre + id);
            if (StrUtil.isBlank(s)) {
                return null;
            }
            RedisData bean = JSONUtil.toBean(s, RedisData.class);
            R r1 = JSONUtil.toBean((JSONObject) bean.getData(), type);
            if (bean.getExpireTime().isAfter(LocalDateTime.now())) {
                return r1;
            }
            //开启新线程进行数据重建
            CACHE_REBUILD_SERVICE.submit(() -> {
                try {
                    R r2 = dbFallback.apply(id);
                    //写入redis
                    setWithLogicalExpire(keyPre + id, r2, time, unit);
                } catch (RuntimeException e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(RedisConstants.LOCK_SHOP_KEY + id);
                }
            });
        }
        //返回（旧）数据
        return r;
    }

    /*
     * 获得锁
     * */
    public boolean getLock (String key) {
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(aBoolean);
    }

    /*
     * 释放锁
     * */
    public void unLock (String key) {
        stringRedisTemplate.delete(key);
    }
}
