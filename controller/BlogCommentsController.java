package com.AITaste.controller;

import com.AITaste.dto.Result;
import com.AITaste.service.IBlogCommentsService;
import com.AITaste.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/comment")
public class BlogCommentsController {

    @Resource
    private IBlogCommentsService commentsService;

    /**
     * 查询当前登录用户发表的评价列表（分页）
     * @param page 页码，默认1
     * @param size 每页大小，默认8
     * @return 评价VO列表（包含店铺信息）
     */
    @GetMapping("/of/user")
    public Result queryUserComments(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "8") Integer size) {
        Long userId = UserHolder.getUser().getId();
        return commentsService.queryUserComments(userId, page, size);
    }

    /**
     * 获取用户评价总数
     * @param userId 用户id
     * @return 总数
     */
    @GetMapping("/count/{userId}")
    public Result countUserComments(@PathVariable("userId") Long userId) {
        return commentsService.countUserComments(userId);
    }

    /**
     * 查询某篇博客的评论列表（分页）
     * @param blogId 博客ID
     * @param page 页码
     * @param size 每页大小
     */
    @GetMapping("/of/blog")
    public Result queryCommentsByBlogId(
            @RequestParam Long blogId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return commentsService.queryCommentsByBlogId(blogId, page, size);
    }

    /**
     * 发表评论
     * @param blogId 博客ID
     * @param content 评论内容
     * @param parentId 父评论ID（可选，默认0）
     */
    @PostMapping("/add")
    public Result addComment(
            @RequestParam Long blogId,
            @RequestParam String content,
            @RequestParam(defaultValue = "0") Long parentId) {
        return commentsService.addComment(blogId, content, parentId);
    }
}