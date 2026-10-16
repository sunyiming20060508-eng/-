package com.hmdp.service.impl;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result getById(Long id) {
        //校验店铺id，与 update 保持一致：id 为空直接拒绝，避免拼出 cache:shop:null 这类无效 key
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        Shop shop = queryWithPassThrough(id);
        //判断店铺信息是否存在
        if(shop == null){
            return Result.fail("店铺信息不存在");
        }
        return Result.ok(shop);
    }
    private Shop queryWithPassThrough(Long id) {
        //1.查询redis缓存中是否存在
        String shopjson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断redis缓存中是否存在
        if (StrUtil.isNotBlank(shopjson)) {
            //3.存在，直接返回
            return JSONUtil.toBean(shopjson, Shop.class);
        }
        //3.1命中的是空值缓存（存的是""），说明该店铺不存在，直接拦截，防止穿透
        if (shopjson != null) {
            return null;
        }
        //4.缓存未命中，尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        String token = null;
        try {
            token = tryLock(lockKey);
            //5.获取锁失败，休眠后重试（递归查询）；此时并未持有锁，绝不能释放别人的锁
            if (token == null) {
                Thread.sleep(100); // 休眠100毫秒
                return queryWithPassThrough(id);
            }
            //6.获取锁成功，只有在持锁期间才需要在 finally 里释放
            try {
                //6.0重建缓存前先二次检查缓存是否已被别人写好
                shopjson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
                if (StrUtil.isNotBlank(shopjson)) {
                    return JSONUtil.toBean(shopjson, Shop.class);
                }
                //6.1查询数据库
                shop = getById((Serializable) id);
                if (shop == null) {
                    //6.2数据库中不存在，缓存空值，防止穿透
                    stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return null;
                }
                //6.3数据库中存在,写入redis缓存
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
            } finally {
                //7.释放锁（只有抢到锁的线程才会走到这里，且只删自己持有的锁）
                unlock(lockKey, token);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return shop;
    }
    private String tryLock(String key) {
        //锁的 value 存唯一标识，解锁时比对归属，防止删掉别人持有的锁
        String token = UUID.randomUUID().toString();
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, token, LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag) ? token : null;
    }
    private void unlock(String key, String token) {
        String value = stringRedisTemplate.opsForValue().get(key);
        //是自己的锁才删；若锁已超时自动过期并被别人重新持有，则不能动
        if (value != null && value.equals(token)) {
            stringRedisTemplate.delete(key);
        }
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
