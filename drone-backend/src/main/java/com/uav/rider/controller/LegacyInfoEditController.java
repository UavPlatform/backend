package com.uav.rider.controller;

import com.uav.server.annotation.SkipJwt;
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
 * 鉴权：方法上标注 {@link SkipJwt}（与 {@code UserController} 等公开接口同一机制），无论令牌有无/是否过期都返回 410；
 * 只豁免精确映射的 {@code /info/edit}，{@code /INFO/EDIT}、{@code /info/edit/}、{@code /info/edit/x} 仍需鉴权。
 * 任意 HTTP 方法均返回 410（旧客户端只用过 POST，其余方法一并兜住）。
 */
@Hidden
@RestController
public class LegacyInfoEditController {

    static final String INFO_EDIT_REMOVED_MESSAGE =
            "该接口已下线：请将 djifly / 飞翼通 App 升级到最新版本；飞手资料请使用 GET/PATCH /rider/info";

    // @SkipJwt：不论是否携带/过期令牌都返回 410，旧客户端拿到明确的「请升级」提示，而不是 401 后反复重新登录。
    // 只作用于本方法精确匹配的 /info/edit；大小写不同或带尾斜杠的路径不会映射到这里，仍走 JWT 鉴权。
    @SkipJwt
    @RequestMapping("/info/edit")
    public void infoEditRemoved() {
        throw new BusinessException(HttpStatus.GONE, ApiErrorCode.ENDPOINT_REMOVED, INFO_EDIT_REMOVED_MESSAGE);
    }
}
