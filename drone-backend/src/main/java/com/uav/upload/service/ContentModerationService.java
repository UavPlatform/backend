package com.uav.upload.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.green20220302.Client;
import com.aliyun.green20220302.models.ImageModerationRequest;
import com.aliyun.green20220302.models.ImageModerationResponse;
import com.aliyun.green20220302.models.VideoModerationRequest;
import com.aliyun.green20220302.models.VideoModerationResponse;
import com.aliyun.teaopenapi.models.Config;
import com.uav.upload.config.UploadStorageConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 阿里云内容安全 v2 (green20220302) 审核服务。
 * 配置 file.storage.moderation.enabled=true 后生效。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "file.storage.moderation.enabled", havingValue = "true")
public class ContentModerationService {

    private final Client client;
    private final UploadStorageConfig config;

    public ContentModerationService(UploadStorageConfig config) throws Exception {
        this.config = config;
        UploadStorageConfig.Oss oss = config.getOss();
        Config clientConfig = new Config()
                .setAccessKeyId(oss.getAccessKeyId())
                .setAccessKeySecret(oss.getAccessKeySecret())
                .setRegionId("cn-shanghai")
                .setEndpoint("green-cip.cn-shanghai.aliyuncs.com");
        this.client = new Client(clientConfig);
    }

    /**
     * 同步审核图片。
     * @return true=通过, false=违规
     */
    public boolean scanImage(String ossObjectKey) {
        ImageModerationRequest request = new ImageModerationRequest()
                .setService("baselineCheck")
                .setServiceParameters("{\"imageUrl\":\"" + config.buildFileUrl("/" + ossObjectKey) + "\"}");

        try {
            ImageModerationResponse response = client.imageModeration(request);
            if (response.getStatusCode() != 200) {
                log.warn("内容审核请求失败, status: {}, object: {}", response.getStatusCode(), ossObjectKey);
                return true;
            }
            if (response.getBody() == null) {
                return true;
            }

            // 用 fastjson 解析，避免 SDK 内部类型不确定
            JSONObject body = JSON.parseObject(
                    com.aliyun.teautil.Common.toJSONString(response.getBody().toMap()));
            Integer code = body.getInteger("Code");
            if (code == null || code != 200) {
                log.warn("内容审核返回异常, code: {}, object: {}", code, ossObjectKey);
                return true;
            }

            JSONObject data = body.getJSONObject("Data");
            if (data == null) {
                return true;
            }

            JSONArray results = data.getJSONArray("Result");
            if (results == null || results.isEmpty()) {
                return true;
            }

            for (int i = 0; i < results.size(); i++) {
                JSONObject r = results.getJSONObject(i);
                String label = r.getString("Label");
                if (label != null && !"nonLabel".equals(label)) {
                    log.warn("内容审核拦截, object: {}, label: {}, confidence: {}",
                            ossObjectKey, label, r.getFloat("Confidence"));
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.error("内容审核异常, object: {}, 默认放行", ossObjectKey, e);
            return true;
        }
    }

    /**
     * 异步审核视频。
     */
    public void scanVideoAsync(String ossObjectKey) {
        VideoModerationRequest request = new VideoModerationRequest()
                .setService("baselineCheck")
                .setServiceParameters("{\"videoUrl\":\"" + config.buildFileUrl("/" + ossObjectKey) + "\"}");

        try {
            VideoModerationResponse response = client.videoModeration(request);
            if (response.getStatusCode() == 200) {
                log.info("视频审核任务已提交, object: {}", ossObjectKey);
            } else {
                log.warn("视频审核提交失败, status: {}, object: {}", response.getStatusCode(), ossObjectKey);
            }
        } catch (Exception e) {
            log.error("视频审核提交异常, object: {}", ossObjectKey, e);
        }
    }
}
