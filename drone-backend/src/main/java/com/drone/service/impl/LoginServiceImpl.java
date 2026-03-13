package com.drone.service.impl;

import com.drone.pojo.entity.User;
import com.drone.mapper.UserRepository;
import com.drone.pojo.dto.UserLoginDto;
import com.drone.service.LoginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LoginServiceImpl implements LoginService {

    @Autowired
    private UserRepository userRepository;

    @Override
    public boolean tryToLogin(UserLoginDto userLoginDto) {
        Long id = userLoginDto.getId();
        String passWord = userLoginDto.getPassword();

        if (id != null && passWord != null) {
            User user = userRepository.findByIdAndPassWord(id, passWord);
            return user != null;
        }
        return false;
    }
}