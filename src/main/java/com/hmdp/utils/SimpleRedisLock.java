package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;


public class SimpleRedisLock implements ILock{
    @Resource
    private  StringRedisTemplate stringRedisTemplate;
    private String name;
    private static final String LOCK_PREFIX = "lock:";
    private static final String THREAD_PREFIX= UUID.randomUUID().toString(true)+"-";
    private static final DefaultRedisScript<Long> unlock;
    static{
        unlock=new DefaultRedisScript<>();
        unlock.setLocation(new ClassPathResource("unlock.lua"));
        unlock.setResultType(Long.class);
    }
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String name = THREAD_PREFIX+Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_PREFIX + this.name,
                name, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
//        String name = THREAD_PREFIX+Thread.currentThread().getId();
//        String s = stringRedisTemplate.opsForValue().get(LOCK_PREFIX + this.name);
//        if(name.equals(s)){
//            stringRedisTemplate.delete(LOCK_PREFIX + this.name);
//        }
        //调用Lua脚本
        stringRedisTemplate.execute(unlock,
                Collections.singletonList(LOCK_PREFIX + this.name),
                THREAD_PREFIX+Thread.currentThread().getId()
                );
    }
}
