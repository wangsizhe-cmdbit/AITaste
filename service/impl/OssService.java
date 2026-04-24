package com.AITaste.service.impl;

import com.AITaste.config.OssConfig;
import com.aliyun.oss.OSS;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OssService {

    private final OSS ossClient;
    private final OssConfig ossConfig;

    /**
     * 上传图片到 OSS 的指定目录
     * @param file      上传的文件
     * @param dir       OSS 中的目录名，例如 "blog-picture"
     * @return 图片的完整访问 URL
     */
    public String uploadImage(MultipartFile file, String dir) throws IOException {
        // 1. 生成唯一的文件名，保持原有目录结构（可选）
        String originalFilename = file.getOriginalFilename();
        String suffix = originalFilename.substring(originalFilename.lastIndexOf("."));
        String uuid = UUID.randomUUID().toString();
        int hash = uuid.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;

        // 2. 构造 OSS 对象键（Key），例如：blog-picture/1/2/uuid.jpg
        String objectKey = String.format("%s/%d/%d/%s%s", dir, d1, d2, uuid, suffix);

        // 3. 上传到 OSS
        ossClient.putObject(ossConfig.getBucketName(), objectKey, file.getInputStream());

        // 4. 生成访问 URL
        String url = generateUrl(objectKey);
        log.info("图片上传成功: {}", url);
        return url;
    }

    /**
     * 根据 objectKey 生成访问 URL
     */
    public String generateUrl(String objectKey) {
        if (StringUtils.hasText(ossConfig.getCustomDomain())) {
            return ossConfig.getCustomDomain() + "/" + objectKey;
        } else {
            return "https://" + ossConfig.getBucketName() + "." + ossConfig.getEndpoint() + "/" + objectKey;
        }
    }

    /**
     * 从 OSS 删除图片
     * @param objectKey 文件的 OSS Key（例如 blog-picture/1/2/uuid.jpg）
     */
    public void deleteImage(String objectKey) {
        ossClient.deleteObject(ossConfig.getBucketName(), objectKey);
        log.info("图片删除成功: {}", objectKey);
    }
}