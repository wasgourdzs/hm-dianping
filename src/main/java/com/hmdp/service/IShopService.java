package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {
    /*
    * 利用缓存查店铺信息
    * */
    Result queryById(Long id);
    /*
    * 利用缓存更新策略更新店铺信息
    * */
    Result update(Shop shop);
    /*
    * 分页查询店铺（带有距离信息）
    * */
    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
