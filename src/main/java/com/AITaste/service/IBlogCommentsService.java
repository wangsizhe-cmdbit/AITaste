package com.AITaste.service;


import com.AITaste.dto.Result;
import com.AITaste.entity.BlogComments;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IBlogCommentsService extends IService<BlogComments> {

    Result queryUserComments(Long userId, Integer page, Integer size);

    Result countUserComments(Long userId);

    /**
     * 查询某篇博客的评论列表（分页）
     * @param blogId 博客ID
     * @param page 页码
     * @param size 每页大小
     * @return 评论列表
     */
    Result queryCommentsByBlogId(Long blogId, Integer page, Integer size);

    /**
     * 发表评论
     * @param blogId 博客ID
     * @param content 评论内容
     * @param parentId 父评论ID（回复时使用，默认0）
     * @return 操作结果
     */
    Result addComment(Long blogId, String content, Long parentId);

}