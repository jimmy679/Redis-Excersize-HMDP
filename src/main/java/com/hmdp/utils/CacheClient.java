package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;


    //1.将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
    public  void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    //2.将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
    public  void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData=new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //3.根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
    public <R,ID> R queryShopPassThrough(String keyPrefix, ID id, Class<R> type,
                                         Function<ID,R> dbFallback,Long time, TimeUnit unit){
        String key=keyPrefix+id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(json)){
            R bean = JSONUtil.toBean(json, type);
            return bean;
        }
        if (json != null){
            return null;
        }
        R r= dbFallback.apply(id);
        if(r == null){
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        set(key,r,time,unit);
        return r;
    }
    //4.根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type,
                                        Function<ID,R> dbFallback,Long time, TimeUnit unit){
        String key=keyPrefix+id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(json)){
            return null;
        }
        //判断缓存是否过期
        RedisData data = JSONUtil.toBean(json, RedisData.class);
        JSONObject data1 = (JSONObject) data.getData();
        R r = JSONUtil.toBean(data1,type);
        LocalDateTime expireTime = data.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        //重建缓存
        String lockKey=RedisConstants.LOCK_SHOP_KEY + id;
        boolean lock = tryLock(lockKey);
        //获取锁后务必检查Redis中是否存在，避免重复创建
        if (lock){
            //引入线程池
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch(Exception e){
                    throw new RuntimeException(e);
                } finally{
                    unlock(lockKey);
                }
            });
        }
        return r;
    }


    //互斥锁
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
