package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    /*
    * 关注、取关
    * */
    @Override
    public Result follow(Long id, Boolean isFollow) {
        //获得当前用户ID
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();
        //判断保存到数据库还是删除
        if (isFollow) {
            //封装
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            boolean success = save(follow);
            //保存到redis，方便做共同关注功能
            if (success) {
                stringRedisTemplate.opsForSet().add(RedisConstants.FOLLOW_KEY + userId, id.toString());
            }
        } else {
            remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", id));
            //redis中也要删除
            stringRedisTemplate.opsForSet().remove(RedisConstants.FOLLOW_KEY + userId, id.toString());
        }
        return Result.ok();
    }

    /*
    * 查看是否关注
    * */
    @Override
    public Result isFollow(Long id) {
        //获取用户ID
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.ok(false);
        }
        Long userId = user.getId();
        //查数据库
//        Integer count = query().eq("user_id", userId).eq("follow_user_id", id).count();
//        if (count > 0) {
//            return Result.ok(true);
//        }
//        return Result.ok(false);
        //查redis
        Boolean aBoolean = stringRedisTemplate.opsForSet().isMember(RedisConstants.FOLLOW_KEY + userId, id.toString());
        if (BooleanUtil.isTrue(aBoolean)) {
            return Result.ok(true);
        }
        return Result.ok(false);
    }

    /*
    * 查看共同关注
    * */
    @Override
    public Result followCommons(Long id) {
        //获取当前用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();
        //判断是否存在交集
        Set<String> set = stringRedisTemplate.opsForSet().intersect(RedisConstants.FOLLOW_KEY + userId, RedisConstants.FOLLOW_KEY + id);
        if (set == null || set.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //存在则解析获得用户ID，查用户信息，并返回
        List<Long> ids = set.stream().map(Long::valueOf).collect(Collectors.toList());
        List<User> users = userService.listByIds(ids);
        //封装到DTO
        List<UserDTO> userDTOS = users.stream().map(user1 -> BeanUtil.copyProperties(user1, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
