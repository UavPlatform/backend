package com.drone.mapper;

import com.drone.pojo.entity.Route;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface RouteRepository extends JpaRepository<Route, Long> {

    List<Route> findByUserNameOrderByCreateTimeDesc(String userName);

    List<Route> findByDjiIdOrderByCreateTimeDesc(String djiId);

    List<Route> findByUserNameAndDjiIdOrderByCreateTimeDesc(String userName, String djiId);

    Optional<Route> findByUserName(String userName);
}
