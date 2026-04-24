package com.AITaste.service;


import com.AITaste.dto.Result;
import com.AITaste.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IBlogService extends IService<Blog> {

    /**
     * 查询热门笔记（按 liked 字段降序分页）
     * @param current 页码
     * @return 包含笔记列表的结果，每个笔记附带作者信息和当前用户是否点赞
     */
    Result queryHotBlog(Integer current);

    /**
     * 根据id查询笔记详情
     * @param id 笔记id
     * @return 笔记详情，包含作者、是否点赞
     */
    Result queryBlogById(Long id);

    /**
     * 点赞/取消点赞（原子操作，使用Redis ZSet存储点赞用户及时间戳）
     * @param id 笔记id
     * @return 操作结果
     */
    Result likeBlog(Long id);

    /**
     * 查询点赞某篇笔记的前5名用户（按点赞时间倒序）
     * @param id 笔记id
     * @return 前5名用户DTO列表
     */
    Result queryBlogLikes(Long id);

    /**
     * 发布新笔记：保存到MySQL，并推送给所有粉丝的收件箱（Redis ZSet）
     * @param blog 笔记内容
     * @return 包含新笔记id的结果
     */
    Result saveBlog(Blog blog);

    /**
     * 查询关注的人发布的笔记（滚动分页，基于Redis ZSet的按分数倒序查询）
     * @param max 上一次查询的最小时间戳（lastId）
     * @param offset 偏移量（用于处理同一时间戳的多条记录）
     * @return 包含笔记列表、下一次查询的offset和minTime
     */
    Result queryBlogOfFollow(Long max, Integer offset);
}