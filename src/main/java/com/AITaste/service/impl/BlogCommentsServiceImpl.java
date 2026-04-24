package com.AITaste.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.AITaste.VO.CommentVO;
import com.AITaste.dto.Result;
import com.AITaste.entity.Blog;
import com.AITaste.entity.BlogComments;
import com.AITaste.entity.User;
import com.AITaste.mapper.BlogCommentsMapper;
import com.AITaste.service.IBlogCommentsService;
import com.AITaste.service.IBlogService;
import com.AITaste.service.IUserService;
import com.AITaste.utils.UserHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

    @Resource
    private IBlogService blogService;

    @Resource
    private IUserService userService;

    @Override
    public Result queryUserComments(Long userId, Integer page, Integer size) {
        // 分页查询用户的所有评论（按时间倒序）
        Page<BlogComments> commentPage = new Page<>(page, size);
        LambdaQueryWrapper<BlogComments> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BlogComments::getUserId, userId)
                .orderByDesc(BlogComments::getCreateTime);
        Page<BlogComments> result = this.page(commentPage, wrapper);
        List<BlogComments> records = result.getRecords();
        if (records.isEmpty()) {
            return Result.ok(new Page<>());
        }
        // 转换为VO，并填充博客（店铺）信息
        List<CommentVO> voList = records.stream().map(comment -> {
            CommentVO vo = BeanUtil.copyProperties(comment, CommentVO.class);
            // 根据 blogId 查询博客信息（店铺名称、评分等）
            Blog blog = blogService.getById(comment.getBlogId());
            if (blog != null) {
                vo.setShopName(blog.getTitle()); // 假设博客标题就是店铺名
                // 评分可能需要从博客扩展字段获取，这里暂时默认5分，或者可以从博客的 score 字段（如果有）获取
                vo.setScore(5); // 根据实际业务调整，例如 blog.getScore()
            } else {
                vo.setShopName("未知店铺");
                vo.setScore(5);
            }
            return vo;
        }).collect(Collectors.toList());

        Page<CommentVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(voList);
        return Result.ok(voPage);
    }

    @Override
    public Result countUserComments(Long userId) {
        long count = this.lambdaQuery().eq(BlogComments::getUserId, userId).count();
        return Result.ok(count);
    }

    @Override
    public Result queryCommentsByBlogId(Long blogId, Integer page, Integer size) {
        // 1. 分页查询一级评论（parent_id = 0）
        Page<BlogComments> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<BlogComments> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BlogComments::getBlogId, blogId)
                .eq(BlogComments::getParentId, 0)
                .orderByDesc(BlogComments::getCreateTime);
        Page<BlogComments> result = this.page(pageParam, wrapper);
        List<BlogComments> rootComments = result.getRecords();
        if (rootComments.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 2. 收集所有需要查询的评论 ID（包括所有层级的子评论）
        List<Long> allIds = new ArrayList<>();
        // 先添加一级评论 ID
        List<Long> rootIds = rootComments.stream().map(BlogComments::getId).collect(Collectors.toList());
        allIds.addAll(rootIds);

        // 递归收集所有子评论 ID（可以使用 while 循环逐层查询）
        List<Long> parentIds = new ArrayList<>(rootIds);
        while (!parentIds.isEmpty()) {
            List<BlogComments> children = this.lambdaQuery()
                    .in(BlogComments::getParentId, parentIds)
                    .orderByAsc(BlogComments::getCreateTime) // 按时间正序
                    .list();
            if (children.isEmpty()) break;
            allIds.addAll(children.stream().map(BlogComments::getId).collect(Collectors.toList()));
            parentIds = children.stream().map(BlogComments::getId).collect(Collectors.toList());
        }

        // 3. 批量查询所有评论（避免 N+1）
        List<BlogComments> allComments = this.listByIds(allIds);
        Map<Long, BlogComments> commentMap = allComments.stream()
                .collect(Collectors.toMap(BlogComments::getId, Function.identity()));

        // 4. 构建树形结构：将所有评论按 parentId 分组
        Map<Long, List<BlogComments>> childrenMap = allComments.stream()
                .filter(c -> c.getParentId() != null && c.getParentId() != 0)
                .collect(Collectors.groupingBy(BlogComments::getParentId));

        // 5. 递归填充 children
        for (BlogComments root : rootComments) {
            fillChildren(root, childrenMap);
        }

        // 6. 收集所有评论的用户ID
        Set<Long> userIds = new HashSet<>();
        collectUserIds(rootComments, userIds);
        collectAnswerUserIds(rootComments, userIds);

        // 7. 批量查询用户昵称
        if (!userIds.isEmpty()) {
            List<User> users = userService.listByIds(userIds);
            Map<Long, User> userMap = users.stream()
                    .collect(Collectors.toMap(User::getId, Function.identity()));
            fillUserInfo(rootComments, userMap);
        }
        return Result.ok(rootComments);
    }

    // 递归填充子评论
    private void fillChildren(BlogComments parent, Map<Long, List<BlogComments>> childrenMap) {
        List<BlogComments> children = childrenMap.get(parent.getId());
        if (children != null && !children.isEmpty()) {
            parent.setChildren(children);
            for (BlogComments child : children) {
                fillChildren(child, childrenMap);
            }
        }
    }

    // 收集所有评论的用户ID
    private void collectUserIds(List<BlogComments> comments, Set<Long> userIds) {
        for (BlogComments comment : comments) {
            userIds.add(comment.getUserId());
            if (comment.getChildren() != null && !comment.getChildren().isEmpty()) {
                collectUserIds(comment.getChildren(), userIds);
            }
        }
    }

    // 收集被回复人ID
    private void collectAnswerUserIds(List<BlogComments> comments, Set<Long> userIds) {
        for (BlogComments comment : comments) {
            if (comment.getAnswerId() != null && comment.getAnswerId() != 0) {
                userIds.add(comment.getAnswerId());
            }
            if (comment.getChildren() != null && !comment.getChildren().isEmpty()) {
                collectAnswerUserIds(comment.getChildren(), userIds);
            }
        }
    }

    // 填充用户信息（昵称、头像、被回复人昵称）
    private void fillUserInfo(List<BlogComments> comments, Map<Long, User> userMap) {
        for (BlogComments comment : comments) {
            User user = userMap.get(comment.getUserId());
            if (user != null) {
                comment.setNickname(user.getNickName());
                comment.setAvatar(user.getIcon());  // 假设 User 有 getIcon() 方法
            }
            if (comment.getAnswerId() != null && comment.getAnswerId() != 0) {
                User answerUser = userMap.get(comment.getAnswerId());
                if (answerUser != null) {
                    comment.setAnswerNickname(answerUser.getNickName());
                }
            }
            if (comment.getChildren() != null && !comment.getChildren().isEmpty()) {
                fillUserInfo(comment.getChildren(), userMap);
            }
        }
    }

    @Override
    @Transactional
    public Result addComment(Long blogId, String content, Long parentId) {
        Long userId = UserHolder.getUser().getId();
        if (userId == null) {
            return Result.fail("请先登录");
        }
        if (content == null || content.trim().isEmpty()) {
            return Result.fail("评论内容不能为空");
        }
        BlogComments comment = new BlogComments();
        comment.setUserId(userId);
        comment.setBlogId(blogId);
        comment.setContent(content);
        comment.setParentId(parentId == null ? 0L : parentId);
        comment.setLiked(0);
        comment.setStatus(false);
        comment.setCreateTime(LocalDateTime.now());
        comment.setUpdateTime(LocalDateTime.now());
        // 保存评论
        boolean saved = this.save(comment);
        if (!saved) {
            return Result.fail("评论失败");
        }
        // 更新博客的评论数量 +1（无论主评论还是回复，都增加博客总评论数）
        blogService.update()
                .setSql("comments = comments + 1")
                .eq("id", blogId)
                .update();
        return Result.ok("评论成功");
    }
}