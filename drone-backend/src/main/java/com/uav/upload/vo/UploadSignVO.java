package com.uav.upload.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UploadSignVO {
    /** 文件记录 ID，后续 confirm 用 */
    private Long fileId;
    /** OSS 预签名上传 URL（PUT 方法） */
    private String preSignedUrl;
    /** OSS 对象 key（相对路径） */
    private String objectKey;
    /** 签名过期时间（ISO 格式） */
    private String expiresAt;
}
