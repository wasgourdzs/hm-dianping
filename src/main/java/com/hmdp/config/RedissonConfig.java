package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient () {
        //配置对象
        Config config = new Config();
        //设置单节点服务，设置redis地址和密码
        config.useSingleServer().setAddress("redis://192.168.38.141:6379").setPassword("123456");
        //创建redisson对象
        return Redisson.create(config);
    }

}
