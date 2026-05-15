package com.hmdp.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /*
    * 利用缓存展示店铺标签
    * */
    @Override
    public Result show() {
        //redis中查
        List<String> shopType = stringRedisTemplate.opsForList().range(RedisConstants.CACHE_SHOP_TYPE, 0, -1);
        List<JSONObject> shopTypeObj = new ArrayList<>();
        //存在直接返回
        if (shopType != null && !shopType.isEmpty()) {
            for (String s : shopType) {
                JSONObject jsonObject = JSON.parseObject(s);
                shopTypeObj.add(jsonObject);
            }
            return Result.ok(shopTypeObj);
        }
        //不存在去mysql查
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        //不存在，返回错误信息
        if (shopTypes == null || shopTypes.isEmpty()) {
            return Result.fail("未找到店铺分类信息");
        }
        //存在，写入redis，并返回
        for (ShopType type : shopTypes) {
            String jsonString = JSON.toJSONString(type);
            stringRedisTemplate.opsForList().rightPush(RedisConstants.CACHE_SHOP_TYPE, jsonString);
        }

        return Result.ok(shopTypes);
    }
}
