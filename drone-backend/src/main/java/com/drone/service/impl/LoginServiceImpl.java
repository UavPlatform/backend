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
    public User tryToLogin(UserLoginDto userLoginDto) {
        String userName = userLoginDto.getUserName();
        String passWord = userLoginDto.getPassword();

        if (userName != null && passWord != null) {
            return userRepository.findByUserNameAndPassWord(userName, passWord);
        }
        return null;
    }
}
