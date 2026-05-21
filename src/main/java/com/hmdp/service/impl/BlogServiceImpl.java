package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jodd.util.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Autowired
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryUserByBlog(blog);
            this.userLikeBlog(blog);
        });
        return Result.ok(records);
    }

    /*
    * 根据ID查博客
    * */
    @Override
    public Result queruByBlogId(Long id) {
        //查博客
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }
        //封装用户相关信息
        queryUserByBlog(blog);
        //判断用户是否点赞，封装到blog
        userLikeBlog(blog);
        return Result.ok(blog);
    }

    //点赞博客
    @Override
    public Result likeBlog(Long id) {
        //判断当前用户是否点赞
        //当前登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录再点赞");
        }
        Long userId = user.getId();
        //使用zset，可以做点赞排行榜
//        Boolean flag = stringRedisTemplate.opsForSet().isMember(RedisConstants.BLOG_LIKED_KEY + id, userId.toString());
        Double score = stringRedisTemplate.opsForZSet().score(RedisConstants.BLOG_LIKED_KEY + id, userId.toString());
        //未点赞
        if (score == null) {
            //数据库like字段 + 1
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            //redis中添加
            if (success) {
//                stringRedisTemplate.opsForSet().add(RedisConstants.BLOG_LIKED_KEY + id, userId.toString());
                stringRedisTemplate.opsForZSet().add(RedisConstants.BLOG_LIKED_KEY + id, userId.toString(), System.currentTimeMillis());
            }
        }  else {
            //已点赞
            //数据库liked字段 - 1
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            //redis中移除
            if (success) {
//                stringRedisTemplate.opsForSet().remove(RedisConstants.BLOG_LIKED_KEY + id, userId.toString());
                stringRedisTemplate.opsForZSet().remove(RedisConstants.BLOG_LIKED_KEY + id, userId.toString());
            }
        }
        return Result.ok();
    }

    /*
    * 查看点赞博客
    * */
    @Override
    public Result queryBlogLikes(Long id) {
        //redis中查点赞用户ID，前五名
        Set<String> set = stringRedisTemplate.opsForZSet().range(RedisConstants.BLOG_LIKED_KEY + id, 0, 4);
        if (set == null || set.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //解析出ID
        List<Long> ids = set.stream().map(Long::valueOf).collect(Collectors.toList());
        String s = StringUtil.join(ids, ",");
        //查数据库得到用户信息，封装到dto中 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        List<User> list = userService.query().in("id", ids).last("ORDER BY FIELD(id," + s + ")").list();
        List<UserDTO> userDTOS = list.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        //返回列表
        return Result.ok(userDTOS);
    }

    //通过blog查对应user
    private void queryUserByBlog(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    //判断用户是否点赞
    private void userLikeBlog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            //未登录无需查
            return;
        }
        Long userId = user.getId();
//        Boolean success = stringRedisTemplate.opsForSet().isMember(RedisConstants.BLOG_LIKED_KEY + blog.getId(), userId.toString());
        Double score = stringRedisTemplate.opsForZSet().score(RedisConstants.BLOG_LIKED_KEY + blog.getId(), userId.toString());
        if (score != null) {
            blog.setIsLike(true);
            return;
        }
        blog.setIsLike(false);
    }
}
