package com.saasclaw.backend.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.saasclaw.backend.common.BizException;
import com.saasclaw.backend.config.OssProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 阿里云 OSS 基础设施封装：上传 / 读取 / 删除 / 复制对象 / 拼公开读 URL。
 * bucket 公开读，fileUrl 直接用 https 域名访问。
 */
@Service
@RequiredArgsConstructor
public class OssService {

    private final OSS ossClient;
    private final OssProperties props;

    /**
     * 上传对象（流式），返回公开读 URL。
     *
     * @param key         对象 key，如 skill/1/SKILL.md
     * @param in          文件流
     * @param size        字节数
     * @param contentType 如 text/markdown
     */
    public String upload(String key, InputStream in, long size, String contentType) {
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(size);
        if (contentType != null) {
            meta.setContentType(contentType);
        }
        ossClient.putObject(props.getBucket(), key, in, meta);
        return toUrl(key);
    }

    /** 读对象内容为 UTF-8 文本（人格文件全量读取用）；key 不存在时由调用方兜底。 */
    public String read(String key) {
        OSSObject obj = ossClient.getObject(props.getBucket(), key);
        try (InputStream in = obj.getObjectContent()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException(500, "读取文件失败");
        }
    }

    /** 公开读 URL → object key（toUrl 的逆运算） */
    public String keyFromUrl(String fileUrl) {
        String prefix = "https://" + props.getBucket() + "." + props.getEndpoint() + "/";
        return fileUrl.startsWith(prefix) ? fileUrl.substring(prefix.length()) : fileUrl;
    }

    /** 复制对象（商店安装副本快照用），返回目标 key 的公开读 URL */
    public String copy(String sourceKey, String destKey) {
        ossClient.copyObject(props.getBucket(), sourceKey, props.getBucket(), destKey);
        return toUrl(destKey);
    }

    /** 删除对象（忽略不存在） */
    public void delete(String key) {
        ossClient.deleteObject(props.getBucket(), key);
    }

    /** 公开读 URL：https://{bucket}.{endpoint}/{key} */
    public String toUrl(String key) {
        return "https://" + props.getBucket() + "." + props.getEndpoint() + "/" + key;
    }
}