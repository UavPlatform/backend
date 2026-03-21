package com.drone.mapper;

import com.drone.pojo.entity.User;
import com.drone.pojo.vo.RegisterVo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    User findByUserNameAndPassWord(String userName, String passWord);

    @Query("SELECT new com.drone.pojo.vo.RegisterVo(u.id, u.userName) FROM User u WHERE u.userName = :name")
    RegisterVo findByUserName(@Param("name") String name);

    @Query("SELECT MAX(u.id) FROM User u")
    Long findMaxId();
}
