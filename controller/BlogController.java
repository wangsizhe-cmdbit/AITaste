package com.AITaste.controller;


import com.AITaste.dto.Result;
import com.AITaste.dto.UserDTO;
import com.AITaste.entity.Blog;
import com.AITaste.service.IBlogService;
import com.AITaste.utils.SystemConstants;
import com.AITaste.utils.UserHolder;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;

    /**
     * 发布新的探店笔记
     * @param blog 笔记内容（标题、图片等）
     * @return 包含新笔记id的结果
     */
    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    /**
     * 点赞或取消点赞某篇笔记（支持切换）
     * @param id 笔记id
     * @return 操作结果
     */
    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        return blogService.likeBlog(id);
    }

    /**
     * 查询当前登录用户自己发布的笔记列表（分页）
     * @param current 页码，默认1
     * @return 笔记列表
     */
    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    /**
     * 查询热门笔记（按点赞数降序分页）
     * @param current 页码，默认1
     * @return 热门笔记列表
     */
    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }

    /**
     * 根据id查询笔记详情（包含作者信息、当前用户是否点赞）
     * @param id 笔记id
     * @return 笔记详情
     */
    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.queryBlogById(id);
    }

    /**
     * 查询点赞某篇笔记的前5名用户（用于点赞排行榜展示）
     * @param id 笔记id
     * @return 用户列表（按点赞时间倒序）
     */
    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id) {
        return blogService.queryBlogLikes(id);
    }

    /**
     * 查询指定用户发布的笔记列表（分页）
     * @param current 页码，默认1
     * @param id 目标用户id
     * @return 笔记列表
     */
    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    /**
     * 查询关注的人发布的笔记（收件箱滚动分页，类似朋友圈）
     * @param max 上一次查询的最小时间戳（用于游标）
     * @param offset 偏移量（处理同一时间戳有多条记录）
     * @return 笔记列表 + 下一次查询的游标参数
     */
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam("lastId") Long max, @RequestParam(value = "offset", defaultValue = "0") Integer offset){
        return blogService.queryBlogOfFollow(max, offset);
    }
}
