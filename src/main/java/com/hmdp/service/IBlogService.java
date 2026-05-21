package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {
    /*
    * 根据ID查看博客
    * */
    Result queruByBlogId(Long id);
    /*
    * 查热门博客
    * */
    Result queryHotBlog(Integer current);
    /*
    * 点赞博客
    * */
    Result likeBlog(Long id);
    /*
    * 查看点赞用户
    * */
    Result queryBlogLikes(Long id);
}
