package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        String shopType = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
        if(StrUtil.isNotBlank(shopType)){
            List<ShopType> sort = JSONUtil.toList(shopType, ShopType.class);
            return Result.ok(sort);
        } 
        List<ShopType> sort = query().orderByAsc("sort").list();
        if(sort == null || sort.size() == 0){
            return Result.fail("查询失败或数据为空");
        }
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(sort),CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        return Result.ok(sort);
    }

    @Override
    @Transactional
    public Result update(ShopType shopType) {
        Long id = shopType.getId();
        if (id == null) {
            return Result.fail("商铺类型id不能为空");
        }
        //1.先更新数据库
        updateById(shopType);
        //2.再删除整体列表缓存，下次查询时回源重建，保证缓存与数据库一致
        stringRedisTemplate.delete(CACHE_SHOP_TYPE_KEY);
        return Result.ok();
    }
}
