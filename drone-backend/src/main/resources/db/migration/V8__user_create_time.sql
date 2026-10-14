-- 用户注册时间（监管端用户列表/详情；手机号本期不做）
ALTER TABLE user ADD COLUMN create_time TIMESTAMP NULL;
