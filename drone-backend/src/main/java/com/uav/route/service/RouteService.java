package com.uav.route.service;

import com.uav.route.pojo.dto.RouteDto;
import com.uav.route.pojo.entity.Route;
import com.uav.server.ws.handler.WsCommandAckResult;
import org.springframework.data.domain.Page;

import java.util.List;

public interface RouteService {
    Route saveRoute(RouteDto routeDto, String userName);

    List<Route> getRoutesByUser(String userName);

    Page<Route> getRoutesByUser(String userName, int page, int size);

    List<Route> getRoutesByDjiId(String djiId);

    List<Route> getRoutesByUserAndDjiId(String userName, String djiId);

    void deleteRoute(Long id, String userName);

    List<Route> getAllRoutes();

    Route getRouteByRouteNum(String routeNum, String userName);

    WsCommandAckResult assignRouteToUav(String routeNum, String userName);
}
