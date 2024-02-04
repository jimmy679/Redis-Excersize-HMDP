package com.hmdp.utils;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
public class RedisIdWorker {
    private final StringRedisTemplate stringRedisTemplate;
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    private static final int COUNT_BITS =32;
    public long nextId(String keyPrefix) {
        //时间戳
        LocalDateTime localDateTime = LocalDateTime.now();
        long second = localDateTime.toEpochSecond(ZoneOffset.UTC);
        long timestamp = second - BEGIN_TIMESTAMP;
        //序列号
        String now = localDateTime.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix +":"+ now);
        //
        return timestamp << COUNT_BITS | count;
    }


}
