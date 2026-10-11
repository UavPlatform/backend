package com.uav.rider.controller;

import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 已下线接口 {@code /info/edit} 的兼容响应（REQ-DJIFLY-001 兼容说明；ADR-0002「后端 API 对旧版客户端短期兼容」）。
 *
 * <p>{@code POST /info/edit} 原为飞手资料占位接口（只返回成功、不落库），已由
 * {@code GET/PATCH /rider/info}（REQ-APP-001「飞手：资料维护」）替代并删除。删除后旧版 djifly/App
 * 调用只会得到 Spring 默认的 404，客户端无法区分「路径写错」与「接口已下线」；
 * 这里改为明确的 <b>410 Gone + {@code ENDPOINT_REMOVED}</b> 与中文升级提示。
 *
 * <p>契约：用 {@link Hidden} 排除出 OpenAPI，冻结规格（spec/openapi）不新增条目——
 * 已下线接口不属于对外契约，消费端不应再为它生成客户端代码。
 * 鉴权：路径已在 {@code WebMvcConfig} 中排除出 JWT 拦截，无论令牌有无/是否过期都返回 410。
 * 任意 HTTP 方法均返回 410（旧客户端只用过 POST，其余方法一并兜住）。
 */
@Hidden
@RestController
public class LegacyInfoEditController {

    static final String INFO_EDIT_REMOVED_MESSAGE =
            "该接口已下线：请将 djifly / 飞翼通 App 升级到最新版本；飞手资料请使用 GET/PATCH /rider/info";

    @RequestMapping("/info/edit")
    public void infoEditRemoved() {
        throw new BusinessException(HttpStatus.GONE, ApiErrorCode.ENDPOINT_REMOVED, INFO_EDIT_REMOVED_MESSAGE);
    }
}
