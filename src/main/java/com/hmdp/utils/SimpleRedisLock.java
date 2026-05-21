package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private StringRedisTemplate stringRedisTemplate;

    private String name;

    private static final String KEY_PRE = "lock:";
    private static final String ID_PRE = UUID.randomUUID().toString(true) + "-";

    //lua脚本
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        //扫描位置
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        //返回类型
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取当前线程ID (拼接UUID，得到线程标识，区分不同虚拟机)
        String threadId = ID_PRE + Thread.currentThread().threadId();

        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PRE + name, threadId, timeoutSec, TimeUnit.SECONDS);

        return Boolean.TRUE.equals(aBoolean);
    }

    //使用lua脚本实现释放锁（保证原子性）
    @Override
    public void unLock() {
        //调用lua脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PRE + name),
                ID_PRE + Thread.currentThread().threadId());
    }

//    @Override
//    public void unLock() {
//        //释放锁之前判断是否是自己的锁
//        //虚拟机的锁
//        String lock = ID_PRE + Thread.currentThread().threadId();
//        //redis中的锁
//        String redisLock = stringRedisTemplate.opsForValue().get(KEY_PRE + name);
//        //是自己的锁再删除
//        if (lock.equals(redisLock)) {
//            stringRedisTemplate.delete(KEY_PRE + name);
//        }
//    }

}
