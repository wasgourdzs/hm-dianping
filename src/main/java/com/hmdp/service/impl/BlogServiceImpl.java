package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jodd.util.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
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
    @Resource
    private IFollowService followService;

    /*
    * 发布博客
    * */
    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean success = save(blog);
        if (!success) {
            return Result.fail("发布博客失败");
        }
        //数据库中查粉丝是谁
        List<Follow> followUserId = followService.query().eq("follow_user_id", user.getId()).list();
        //推送给粉丝 （写到redis内博客的ID，查询时候获取博客ID查询即可）
        for (Follow follow : followUserId) {
            Long followId = follow.getUserId();
            stringRedisTemplate.opsForZSet().add(RedisConstants.FEED_KEY + followId, blog.getId().toString(), System.currentTimeMillis());
        }
        //返回博客ID
        return Result.ok(blog.getId());
    }

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

    /*
    * 查看自己关注的博客
    * */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //获得当前用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();
        //去redis中查收件箱(滚动方式)
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(RedisConstants.FEED_KEY + userId, 0, max, offset, 3);
        if (tuples == null || tuples.isEmpty()) {
            return Result.ok();
        }
        //解析得到博客内容，同时得到最小时间戳和偏移量
        List<Long> blogIds = new ArrayList<>(tuples.size());
        long minTime = max;
        int os = offset;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
            String blogId = tuple.getValue();
            blogIds.add(Long.valueOf(blogId));
        }
        //查数据库（根据ids中的顺序查）
        String StrIds = StringUtil.join(blogIds, ",");
        List<Blog> list = query().in("id", blogIds).last("ORDER BY FIELD(id," + StrIds + ")").list();
        //为每个blog设置喜欢数和用户（是否点赞和用户显示）
        for (Blog blog : list) {
            //封装用户相关信息
            queryUserByBlog(blog);
            //判断用户是否点赞，封装到blog
            userLikeBlog(blog);
        }
        //结果对象
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(list);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);

        return Result.ok(scrollResult);
    }
}
