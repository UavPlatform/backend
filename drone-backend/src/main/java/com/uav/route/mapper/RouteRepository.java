package com.uav.route.mapper;

import com.uav.route.pojo.entity.Route;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.List;

@Repository
public interface RouteRepository extends JpaRepository<Route, Long> {

    @EntityGraph(attributePaths = "waypoints")
    List<Route> findByUserNameOrderByCreateTimeDesc(String userName);

    @EntityGraph(attributePaths = "waypoints")
    Page<Route> findByUserNameOrderByCreateTimeDesc(String userName, Pageable pageable);

    @EntityGraph(attributePaths = "waypoints")
    List<Route> findByDjiIdOrderByCreateTimeDesc(String djiId);

    @EntityGraph(attributePaths = "waypoints")
    List<Route> findByUserNameAndDjiIdOrderByCreateTimeDesc(String userName, String djiId);

    @EntityGraph(attributePaths = "waypoints")
    Optional<Route> findTopByUserNameOrderByCreateTimeDesc(String userName);

    @EntityGraph(attributePaths = "waypoints")
    Optional<Route> findByRouteNum(String routeNum);
}
