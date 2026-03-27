package com.drone.service.impl;

import com.drone.mapper.UavRepository;
import com.drone.mapper.UserRecordRepository;
import com.drone.mapper.UserRepository;
import com.drone.pojo.entity.UserRecord;
import com.drone.pojo.vo.UavVo;
import com.drone.service.WebUavService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class WebUavServiceImpl implements WebUavService {

    @Autowired
    private UavRepository uavRepository;

    @Autowired
    private UserRecordRepository userRecordRepository;

    @Autowired
    private UserRepository userRepository;

    @Override
    public UavVo[] getUav() {
        return uavRepository.getAll();
    }

    @Override
    public List<UserRecord> getUserRecord(String userName) {

        List<UserRecord> records;
        if(userRepository.findByUserName(userName)!=null){
            try {
                records = userRecordRepository.findAllByUserName(userName);
                if (records.isEmpty()){
                    return null;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return records;
        }else{
            throw new RuntimeException("用户未注册");
        }
    }

    @Override
    public Page<UserRecord> getUserRecord(String userName, Pageable pageable) {
        if(userRepository.findByUserName(userName)!=null){
            try {
                return userRecordRepository.findAllByUserName(userName, pageable);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }else{
            throw new RuntimeException("用户未注册");
        }
    }
}