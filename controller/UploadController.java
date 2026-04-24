package com.AITaste.controller;

import com.AITaste.dto.Result;
import com.AITaste.service.impl.OssService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("upload")
@RequiredArgsConstructor
public class UploadController {

    private final OssService ossService;

    /**
     * 上传博客图片到阿里云 OSS（存放在 blog-picture 目录下）
     */
    @PostMapping("blog")
    public Result uploadBlogImage(@RequestParam("file") MultipartFile file) {
        try {
            String url = ossService.uploadImage(file, "blog-picture");
            return Result.ok(url);   // 直接返回完整的 OSS URL
        } catch (Exception e) {
            log.error("图片上传失败", e);
            return Result.fail("图片上传失败：" + e.getMessage());
        }
    }

    /**
     * 删除 OSS 中的图片（可选）
     * @param objectKey 文件的 OSS Key，例如 blog-picture/1/2/uuid.jpg
     */
    @GetMapping("/blog/delete")
    public Result deleteBlogImage(@RequestParam("name") String objectKey) {
        try {
            // 注意：前端传来的 name 可能是完整 URL 或 objectKey，需要提取
            String key = extractObjectKey(objectKey);
            ossService.deleteImage(key);
            return Result.ok();
        } catch (Exception e) {
            log.error("图片删除失败", e);
            return Result.fail("删除失败：" + e.getMessage());
        }
    }

    /**
     * 辅助方法：从完整 URL 或直接 objectKey 中提取 key
     */
    private String extractObjectKey(String input) {
        if (input == null) return "";
        // 如果包含 http:// 或 https://，则解析出路径部分
        if (input.startsWith("http")) {
            int index = input.indexOf("/", 8); // 跳过 http:// 或 https://
            if (index != -1) {
                return input.substring(index + 1);
            }
        }
        return input;
    }
}