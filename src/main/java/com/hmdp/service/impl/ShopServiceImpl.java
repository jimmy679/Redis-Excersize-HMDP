package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.statement.select.KSQLWindow;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@RequiredArgsConstructor
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        //Shop shop=queryWithPassThrough(id);//缓存穿透方法：
//        Shop shop=queryMutex(id);//互斥锁解决缓存穿透
      Shop shop = queryWithLogicalExpire(id);
        if(shop == null){
            return Result.fail("查询不到该店铺信息");
        }
        return Result.ok(shop);
    }

    public Shop queryMutex(Long id) {
        String shopJSON = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if(StrUtil.isNotBlank(shopJSON)){
            Shop shop = JSONUtil.toBean(shopJSON, Shop.class);
            return shop;
        }
        if (shopJSON != null){
            return null;
        }
        //实现缓存重建
        String lockKey=RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop;
        try {
            boolean lock = tryLock(lockKey);
            if (!lock){
                Thread.sleep(1000);
                return queryMutex(id);//递归调用
            }
            String shopJSON2 = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
            if(StrUtil.isNotBlank(shopJSON2)){
                Shop shop2 = JSONUtil.toBean(shopJSON2, Shop.class);
                return shop2;
            }
            shop = getById(id);
            //模拟高重建时间
            Thread.sleep(200);
            if(shop == null){
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+ id,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }
        return shop;
    }

    public Shop queryShopPassThrough(Long id){
        String shopJSON = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if(StrUtil.isNotBlank(shopJSON)){
            Shop shop = JSONUtil.toBean(shopJSON, Shop.class);
            return shop;
        }
        if (shopJSON != null){
            return null;
        }
        Shop shop = getById(id);
        if(shop == null){
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+ id,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    public Shop queryWithPassThrough(Long id){
        String shopJSON = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if(StrUtil.isNotBlank(shopJSON)){
            Shop shop = JSONUtil.toBean(shopJSON, Shop.class);
            return shop;
        }
        if (shopJSON != null){
            return null;
        }
        Shop shop = getById(id);
        if(shop == null){
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+ id,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }



    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id){
        String shopJSON = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if(StrUtil.isBlank(shopJSON)){
            return null;
        }
        //判断缓存是否过期
        RedisData data = JSONUtil.toBean(shopJSON, RedisData.class);
        JSONObject data1 = (JSONObject) data.getData();
        Shop bean = JSONUtil.toBean(data1, Shop.class);
        LocalDateTime expireTime = data.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            return bean;
        }
        //重建缓存
        String lockKey=RedisConstants.LOCK_SHOP_KEY + id;
        boolean lock = tryLock(lockKey);
        //获取锁后务必检查Redis中是否存在，避免重复创建
        String shopJSON2 = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if(StrUtil.isNotBlank(shopJSON2)){
            Shop shop2 = JSONUtil.toBean(shopJSON2, Shop.class);
            return shop2;
        }
        if (lock){
        //引入线程池
        CACHE_REBUILD_EXECUTOR.submit(()->{
            try {
                this.saveShop2Redis(id,20L);
            } catch(Exception e){
               throw new RuntimeException(e);
            } finally{
                unlock(lockKey);
            }
        });
        }
        return bean;
    }






    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }


    public void saveShop2Redis(Long id,Long expireTime) throws InterruptedException {
        Shop shop = getById(id);
        //
        Thread.sleep(200);
        //
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }



    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id==null){
            return Result.fail("店铺信息不存在！");
        }
        updateById(shop);
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+id);
        return Result.ok();
    }
}
