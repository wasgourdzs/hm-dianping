package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cglib.core.Local;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CacheClient cacheClient;

    /*
    * 根据缓存查店铺信息
    * */
    @Override
    public Result queryById(Long id) {
        //解决缓存穿透问题
//        Shop shop = queryWithPassThrough(id);
        //解决缓存击穿问题（互斥锁）
//        Shop shop = queryWithMutex(id);
        //解决缓存击穿问题（逻辑过期）
//        Shop shop = queryWithLogicalExpire(id);

        //使用工具类
        //穿透
//        Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
//                this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //击穿（互斥锁）
//        Shop shop = cacheClient.queryWithMutex(RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
//                this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //击穿（逻辑过期）
        Shop shop = cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
                this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

    /*
    *   缓存穿透问题
    * */
    public Shop queryWithPassThrough (Long id) {
        //redis数据库查数据
        String shopJSON = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJSON)) {
            //存在直接返回
//            JSONObject jsonObject = JSON.parseObject(shopJSON);
//            return Result.ok(jsonObject);
            return JSONUtil.toBean(shopJSON, Shop.class);
        }
        //判断是否为空，是否是重复请求（穿透问题）
        if (shopJSON != null) {
//            return Result.fail("店铺信息不存在");
            return null;
        }
        //不存在则去mysql数据库查
        Shop shop = getById(id);
        //判断是否存在
        if (shop == null) {
            //将空值写入redis，解决缓存穿透问题
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            //不存在返回错误
//            return Result.fail("店铺不存在");
            return null;
        }
        //存在则写到redis中，并返回
//        String shopStr = JSON.toJSONString(shop);
        String shopStr = JSONUtil.toJsonStr(shop);
        //设置超时时间
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, shopStr, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }

    /*
    * 缓存击穿问题(互斥锁)
    * */
    public Shop queryWithMutex (Long id) {
        //redis数据库查数据
        String shopJSON = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJSON)) {
            //存在直接返回
            return JSONUtil.toBean(shopJSON, Shop.class);
        }
        //判断是否为空，是否是重复请求（穿透问题）
        if (shopJSON != null) {
            return null;
        }
        Shop shop = null;

        try {
            //获得锁
            boolean flag = getLock(RedisConstants.LOCK_SHOP_KEY + id);
            //如果失败，则休眠，之后再重试
            if (!flag) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            //二次判断redis是否存在数据
            shopJSON = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
            if (StrUtil.isNotBlank(shopJSON)) {
                return JSONUtil.toBean(shopJSON, Shop.class);
            }
            if (shopJSON != null) {
                return null;
            }

            //成功则去mysql数据库查
            shop = getById(id);
            //模拟线程创建时间
            Thread.sleep(200);
            //判断是否存在
            if (shop == null) {
                //将空值写入redis，解决缓存穿透问题
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                //不存在返回错误
                return null;
            }
            //存在则写到redis中，并返回
            String shopStr = JSONUtil.toJsonStr(shop);
            //设置超时时间
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, shopStr, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            unLock(RedisConstants.LOCK_SHOP_KEY + id);
        }

        return shop;
    }

    /*
    * 线程池
    * */
    private static final ExecutorService CACHE_REBUILD_SERVICE = Executors.newFixedThreadPool(10);

    /*
     * 缓存击穿问题(逻辑过期)
     * */
    public Shop queryWithLogicalExpire (Long id) {
        //redis数据库查数据
        String shopJSON = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //判断是否存在
        if (StrUtil.isBlank(shopJSON)) {
            //不存在直接返回不存在
            return null;
        }
        //存在，获得RedisData对象
        RedisData redisData = JSONUtil.toBean(shopJSON, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //没过期，直接返回
            return shop;
        }
        //过期
        //获得锁
        if (getLock(RedisConstants.LOCK_SHOP_KEY + id)) {
            //二次检查是否过期
            String s = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
            if (StrUtil.isBlank(s)) {
                return null;
            }
            RedisData bean = JSONUtil.toBean(s, RedisData.class);
            Shop shop1 = JSONUtil.toBean((JSONObject) bean.getData(), Shop.class);
            if (bean.getExpireTime().isAfter(LocalDateTime.now())) {
                return shop1;
            }
            //开启新线程进行数据重建
            CACHE_REBUILD_SERVICE.submit(() -> {
                try {
                    this.saveShop2Redis(id, 20L);
                } catch (RuntimeException e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(RedisConstants.LOCK_SHOP_KEY + id);
                }
            });
        }
        //返回（旧）数据
        return shop;
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

    /*
    * 写入redis店铺数据，并设定逻辑过期时间
    * */
    public void saveShop2Redis (Long id, Long expireSeconds) {
        //查数据库
        Shop shop = getById(id);
        //模拟重建
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        //封装RedisData对象
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //存入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }


    /*
    *   利用缓存更新策略来更新店铺的信息
    * */
    @Transactional
    @Override
    public Result update(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("店铺信息错误");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
