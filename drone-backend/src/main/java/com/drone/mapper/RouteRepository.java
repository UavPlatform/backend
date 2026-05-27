package com.drone.mapper;

import com.drone.pojo.entity.Route;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.List;

@Repository
public interface RouteRepository extends JpaRepository<Route, Long> {

    List<Route> findByUserNameOrderByCreateTimeDesc(String userName);

    Page<Route> findByUserNameOrderByCreateTimeDesc(String userName, Pageable pageable);

    List<Route> findByDjiIdOrderByCreateTimeDesc(String djiId);

    List<Route> findByUserNameAndDjiIdOrderByCreateTimeDesc(String userName, String djiId);

    Optional<Route> findByUserName(String userName);

    Optional<Route> findTopByUserNameOrderByCreateTimeDesc(String userName);

    Optional<Route> findByRouteNum(String routeNum);
}
