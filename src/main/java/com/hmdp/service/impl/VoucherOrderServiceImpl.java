package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Transactional
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService iSeckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Override
    public Result settleVoucher(Long voucherId) {
        SeckillVoucher id = iSeckillVoucherService.getById(voucherId);
        if (id.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始!");
        }
        if (id.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束!");
        }
        if(id.getStock() < 1){
            return Result.fail("秒杀已售完!");
        }
        //更新库存
        boolean update = iSeckillVoucherService.update()
                .setSql("stock = stock- 1")
                .eq("voucher_id",voucherId)
                .gt("stock",0)
                .update();
        if (!update){
            return Result.fail("秒杀已售完!");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        long id1 = redisIdWorker.nextId("order");
        voucherOrder.setId(id1);
        Long id2 = UserHolder.getUser().getId();
        voucherOrder.setUserId(id2);
        voucherOrder.setVoucherId(voucherId);
        //保存订单
        save(voucherOrder);
        return Result.ok(id1);
    }
}
