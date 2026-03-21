package com.drone.service.impl;

import com.drone.mapper.UserRepository;
import com.drone.pojo.dto.UserRegisterDto;
import com.drone.pojo.entity.User;
import com.drone.pojo.vo.RegisterVo;
import com.drone.service.RegisterService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegisterServiceImpl implements RegisterService {

    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RegisterVo tryToRegister(UserRegisterDto userRegisterDto) {
        String name = userRegisterDto.getUserName();
        String password = userRegisterDto.getPassword();

        if (name != null && password != null) {
            RegisterVo registerVo = userRepository.findByUserName(name);
            if (registerVo != null && registerVo.getId() != null) {
                throw new RuntimeException("用户名已存在");
            } else {
                Long maxId = userRepository.findMaxId();
                User user = new User();
                user.setId(maxId == null ? 1L : maxId + 1);
                user.setUserName(name);
                user.setPassWord(password); 
                user.setStatus(1);
                entityManager.persist(user);
                entityManager.flush();
                
                RegisterVo result = new RegisterVo();
                result.setId(user.getId());
                result.setUserName(user.getUserName());
                return result;
            }
        }
        throw new RuntimeException("用户名或密码为空");
    }
}
