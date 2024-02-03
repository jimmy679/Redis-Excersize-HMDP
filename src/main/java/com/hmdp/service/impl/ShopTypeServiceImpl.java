package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.netty.util.internal.StringUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {


    private final StringRedisTemplate stringRedisTemplate;
    @Resource
    private IShopTypeService typeService;
    @Override
    public Result MyList() {
        String shopTypeList = stringRedisTemplate.opsForValue().get("shopTypeList");
        if (!StringUtil.isNullOrEmpty(shopTypeList)){
            ShopType shopType = BeanUtil.toBean(shopTypeList, ShopType.class);
            return Result.ok(shopType);
        }
        List<ShopType> typeList = typeService.query().orderByAsc("sort").list();
        if (typeList == null){
            return Result.fail("查询店铺类型失败!");
        }
        stringRedisTemplate.opsForValue().set("shop-type", JSONUtil.toJsonStr(typeList));
        return Result.ok(typeList);
    }
}
