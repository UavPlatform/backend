package com.drone.service.impl;

import com.drone.mapper.RouteRepository;
import com.drone.pojo.dto.RouteDto;
import com.drone.pojo.entity.Route;
import com.drone.pojo.entity.RouteWaypoint;
import com.drone.pojo.enums.ApiErrorCode;
import com.drone.server.exception.BusinessException;
import com.drone.server.handler.DroneWebSocketHandler;
import com.drone.server.util.LogMaskUtil;
import com.drone.server.util.RouteIdGenerator;
import com.drone.server.ws.handler.WsCommandAckResult;
import com.drone.service.RouteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class RouteServiceImpl implements RouteService {

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private DroneWebSocketHandler droneWebSocketHandler;

    @Transactional
    @Override
    public Route saveRoute(RouteDto routeDto, String userName) {
        if (routeDto.getRouteName() == null || routeDto.getRouteName().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "航线名称不能为空");
        }
        if (routeDto.getDjiId() == null || routeDto.getDjiId().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "无人机ID不能为空");
        }
        if (routeDto.getDefaultSpeed() == null || routeDto.getDefaultSpeed() <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "默认速度必须大于0");
        }
        if (routeDto.getDefaultHeight() == null || routeDto.getDefaultHeight() <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "默认高度必须大于0");
        }
        if (routeDto.getWaypoints() == null || routeDto.getWaypoints().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "航点列表不能为空");
        }

        Route route = new Route();
        route.setRouteNum(RouteIdGenerator.generate(userName));
        route.setRouteName(routeDto.getRouteName());
        route.setDjiId(routeDto.getDjiId());
        route.setUserName(userName);
        route.setDefaultSpeed(routeDto.getDefaultSpeed());
        route.setDefaultHeight(routeDto.getDefaultHeight());
        route.setDescription(routeDto.getDescription());

        List<RouteWaypoint> waypoints = routeDto.getWaypoints().stream()
                .map(wp -> {
                    RouteWaypoint waypoint = new RouteWaypoint();
                    waypoint.setRoute(route);
                    waypoint.setOrderIndex(wp.getOrderIndex());
                    waypoint.setLongitude(wp.getLongitude());
                    waypoint.setLatitude(wp.getLatitude());
                    waypoint.setAltitude(wp.getAltitude());
                    waypoint.setStayTime(wp.getStayTime());
                    return waypoint;
                })
                .collect(Collectors.toList());

        route.setWaypoints(waypoints);

        Route saved = routeRepository.save(route);
        log.info("航线保存成功，编号: {}, 用户: {}, 包含 {} 个航点",
                saved.getRouteNum(), LogMaskUtil.maskUserName(userName), waypoints.size());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Route> getRoutesByUser(String userName) {
        return routeRepository.findByUserNameOrderByCreateTimeDesc(userName);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Route> getRoutesByUser(String userName, int page, int size) {
        return routeRepository.findByUserNameOrderByCreateTimeDesc(userName, PageRequest.of(page, size));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Route> getRoutesByDjiId(String djiId) {
        return routeRepository.findByDjiIdOrderByCreateTimeDesc(djiId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Route> getRoutesByUserAndDjiId(String userName, String djiId) {
        return routeRepository.findByUserNameAndDjiIdOrderByCreateTimeDesc(userName, djiId);
    }

    @Transactional
    @Override
    public void deleteRoute(Long id, String userName) {
        Route route = routeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.INVALID_PARAM, "航线不存在"));
        if (!route.getUserName().equals(userName)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.INVALID_PARAM, "无权删除该航线");
        }
        routeRepository.delete(route);
        log.info("航线删除成功，编号: {}, 用户: {}",
                route.getRouteNum(), LogMaskUtil.maskUserName(userName));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Route> getAllRoutes() {
        return routeRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public Route getRouteByRouteNum(String routeNum, String userName) {
        Route route = routeRepository.findByRouteNum(routeNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND));
        if (!route.getUserName().equals(userName)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.ROUTE_NOT_FOUND, "无权查看此航线");
        }
        return route;
    }

    @Override
    @Transactional(readOnly = true)
    public WsCommandAckResult assignRouteToUav(String routeNum, String userName) {
        Route route = routeRepository.findByRouteNum(routeNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND));

        if (!route.getUserName().equals(userName)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.ROUTE_NOT_FOUND, "无权执行此航线");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("routeId", route.getId());
        data.put("routeName", route.getRouteName());
        data.put("waypoints", route.getWaypoints());
        data.put("defaultSpeed", route.getDefaultSpeed());
        data.put("defaultHeight", route.getDefaultHeight());

        WsCommandAckResult result = droneWebSocketHandler.sendCommandWithAck(
                route.getDjiId(), "EXECUTE_ROUTE", data, 60000);

        if (result.isSuccess()) {
            log.info("航线执行成功，编号: {}, 无人机: {}", routeNum, route.getDjiId());
        } else if (result.isTimedOut()) {
            log.warn("航线执行超时，编号: {}, 无人机: {}", routeNum, route.getDjiId());
        } else {
            log.warn("航线执行失败，编号: {}, 无人机: {}, 原因: {}", routeNum, route.getDjiId(), result.getMessage());
        }

        return result;
    }
}
