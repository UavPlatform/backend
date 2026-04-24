package com.drone.service.impl;

import com.alibaba.fastjson.JSON;
import com.drone.mapper.RouteRepository;
import com.drone.pojo.dto.RouteDto;
import com.drone.pojo.dto.WaypointDto;
import com.drone.pojo.entity.Route;
import com.drone.pojo.entity.RouteWaypoint;
import com.drone.pojo.dto.WsEnvelope;
import com.drone.server.exception.ApiErrorCode;
import com.drone.server.exception.BusinessException;
import com.drone.server.handler.DroneWebSocketHandler;
import com.drone.service.RouteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
        route.setRouteName(routeDto.getRouteName());
        route.setDjiId(routeDto.getDjiId());
        route.setUserName(userName);
        route.setDefaultSpeed(routeDto.getDefaultSpeed());
        route.setDefaultHeight(routeDto.getDefaultHeight());
        route.setDescription(routeDto.getDescription());
        route.setCreateTime(LocalDateTime.now());
        route.setUpdateTime(LocalDateTime.now());

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
        log.info("用户 {} 保存航线 {} 成功，包含 {} 个航点", userName, routeDto.getRouteName(), waypoints.size());
        return saved;
    }

    @Override
    public List<Route> getRoutesByUser(String userName) {
        return routeRepository.findByUserNameOrderByCreateTimeDesc(userName);
    }

    @Override
    public List<Route> getRoutesByDjiId(String djiId) {
        return routeRepository.findByDjiIdOrderByCreateTimeDesc(djiId);
    }

    @Override
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
        log.info("用户 {} 删除航线 {} 成功", userName, route.getRouteName());
    }

    @Override
    public List<Route> getAllRoutes() {
        return routeRepository.findAll();
    }

    @Override
    public boolean assignRouteToUav(long id) {
        Route route = routeRepository.findById((long) id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.INVALID_PARAM, "航线不存在"));
        
        // websocket
        WsEnvelope envelope = new WsEnvelope();
        envelope.setType("command");
        envelope.setName("EXECUTE_ROUTE");
        envelope.setDeviceId(route.getDjiId());
        envelope.setTimestamp(System.currentTimeMillis());
        
        Map<String, Object> data = new HashMap<>();
        data.put("routeId", route.getId());
        data.put("routeName", route.getRouteName());
        data.put("waypoints", route.getWaypoints());
        data.put("defaultSpeed", route.getDefaultSpeed());
        data.put("defaultHeight", route.getDefaultHeight());
        envelope.setData(data);
        

        boolean success = droneWebSocketHandler.sendMessage(route.getDjiId(), JSON.toJSONString(envelope));
        
        if (success) {
            log.info("航线执行命令发送成功，航线ID: {}, 无人机ID: {}", route.getId(), route.getDjiId());
        } else {
            log.warn("航线执行命令发送失败，航线ID: {}, 无人机ID: {}", route.getId(), route.getDjiId());
        }
        
        return success;
    }
}
