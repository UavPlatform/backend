package com.drone.service;

import com.drone.pojo.dto.RouteDto;
import com.drone.pojo.entity.Route;
import java.util.List;

public interface RouteService {
    Route saveRoute(RouteDto routeDto, String userName);

    List<Route> getRoutesByUser(String userName);

    List<Route> getRoutesByDjiId(String djiId);

    List<Route> getRoutesByUserAndDjiId(String userName, String djiId);

    void deleteRoute(Long id, String userName);

    List<Route> getAllRoutes();

    boolean assignRouteToUav(long id);
}
