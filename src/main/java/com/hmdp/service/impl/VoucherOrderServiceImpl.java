package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.BeanUtils;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService iSeckillVoucherService;
    @Resource
    private  StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RedisIdWorker redisIdWorker;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    private static final ExecutorService SECKILL_ORDER_EXCUTOR = Executors.newSingleThreadExecutor();
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXCUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            String queueName = "stream.orders";
            while(true){
                try{
                    //1.获取消息队列中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断消息获取是否成功
                    if (list == null || list.isEmpty()){
                        //2.1如果获取失败。说明没有消息，继续下一次循环
                        continue;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //3.如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //4.ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                }catch(Exception e){
                    //出现异常，没有被ACK，进入PendingList
                    handlePendingList();
                    log.error("订单处理异常",e);
                }
            }
        }
    }

    private void handlePendingList() {
        String queueName = "stream.orders";
        while(true){
            try{
                //1.获取PL中的订单信息
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(queueName,ReadOffset.from("0"))
                );
                //2.判断消息获取是否成功
                if (list == null || list.isEmpty()){
                    //2.1如果获取失败。说明PL没有消息,结束循环
                    break;
                }
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                //3.如果获取成功，可以下单
                handleVoucherOrder(voucherOrder);
                //4.ACK确认
                stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
            }catch(Exception e){
                //出现异常，没有被ACK，进入PendingList
                log.error("订单PL处理异常",e);
            }
        }
    }


    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1. 获取用户
        Long userId = voucherOrder.getUserId();
        //2. 创建锁对象，作为兜底方案
        RLock redisLock = redissonClient.getLock("order:" + userId);
        //3. 获取锁
        boolean isLock = redisLock.tryLock();
        //4. 判断是否获取锁成功
        if (!isLock) {
            log.error("不允许重复下单!");
            return;
        }
        try {
            //5. 使用代理对象，由于这里是另外一个线程，
            proxy.createVoucher(voucherOrder);
        } finally {
            redisLock.unlock();
        }
    }

//    @Override
//    public Result settleVoucher(Long voucherId) {
//        SeckillVoucher id = iSeckillVoucherService.getById(voucherId);
//        if (id.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始!");
//        }
//        if (id.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束!");
//        }
//        if (id.getStock() < 1) {
//            return Result.fail("秒杀已售完!");
//        }
//        Long id2 = UserHolder.getUser().getId();
//        //创建锁对象
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + id2, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + id2);
//        if (!lock.tryLock()) {
//            return Result.fail("抱歉，秒杀繁忙，请稍后再试!");
//        }
//        try {
//            IVoucherOrderService context = (
//                    IVoucherOrderService)AopContext.currentProxy();
//            return context.createVoucher(voucherId);
//        } finally {
//            lock.unlock();
//        }
//    }

    //利用Redis优化查询资格功能
    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), UserHolder.getUser().getId().toString(),String.valueOf(orderId));
        int r = result.intValue();
        if(r != 0){
            return Result.fail(r ==1 ?"库存不足":"不能重复下单");
        }
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return  Result.ok(orderId);
    }
//    public Result seckillVoucher(Long voucherId) {
//        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), UserHolder.getUser().getId().toString());
//        int r = result.intValue();
//        if(r != 0){
//            return Result.fail(r ==1 ?"库存不足":"不能重复下单");
//        }
//        long orderId = redisIdWorker.nextId("order");
//        //有购买资格，将3个Id保存到阻塞队列
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setVoucherId(voucherId);
//        voucherOrder.setUserId(UserHolder.getUser().getId());
//        voucherOrder.setId(orderId);
//        //加入到阻塞队列
//        orderTasks.add(voucherOrder);
//        //获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        return  Result.ok(orderId);
//    }

        @Transactional
        public void createVoucher(VoucherOrder voucherOrder){
            //一人一单判断
            Long id2 = voucherOrder.getId();
                Integer count = query().eq("user_id", id2).eq("voucher_id", voucherOrder.getVoucherId()).count();
                if (count > 0){
                    log.error("用户以购买过一次！");
                    return ;
                }//更新库存
                boolean update = iSeckillVoucherService.update()
                        .setSql("stock = stock- 1")
                        .eq("voucher_id",voucherOrder.getVoucherId())
                        .gt("stock",0)
                        .update();
                if (!update){
                    log.error("秒杀已售完!");
                    return ;
                }
                save(voucherOrder);
            }


}
