package com.hmdp.utils;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;


public class SimpleRedisLock implements ILock{
    @Resource
    private  StringRedisTemplate stringRedisTemplate;
    private String name;
    private static final String LOCK_PREFIX = "lock:";

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String name = Thread.currentThread().getName();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_PREFIX + this.name,
                name, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        stringRedisTemplate.delete(LOCK_PREFIX + this.name);
    }
}
