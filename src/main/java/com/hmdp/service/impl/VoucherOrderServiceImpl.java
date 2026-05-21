package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWork;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWork redisIdWork;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;

    //lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //阻塞队列
//    private  BlockingQueue<VoucherOrder> ordersTask = new ArrayBlockingQueue<>(1024 * 1024);

    //异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    //设定线程任务（类初始化之后就开始执行）
    @PostConstruct //类初始化开始执行这段代码
    private void init () {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    //异步线程执行的任务 (redis消息队列方式)
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    //从消息队列获取订单 xreadgroup group g1 count 1 block 2000 streams stream.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.order", ReadOffset.lastConsumed())
                    );
                    //判断能否从队列中拿到
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    //解析得到订单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 处理阻塞队列的订单(抽取一个方法)
                    blockQueueHandler(voucherOrder);
                    //设定标识 xack
                    stringRedisTemplate.opsForStream().acknowledge("stream.order", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlerPendingList();
                }
            }
        }

        private void handlerPendingList() {
            while (true) {
                try {
                    //从pending-list获取订单 xreadgroup group g1 count 1 streams stream.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.order", ReadOffset.from("0"))
                    );
                    //判断能否从队列中拿到
                    if (list == null || list.isEmpty()) {
                        break;
                    }
                    //解析得到订单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 处理阻塞队列的订单(抽取一个方法)
                    blockQueueHandler(voucherOrder);
                    //设定标识 xack
                    stringRedisTemplate.opsForStream().acknowledge("stream.order", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }

    //异步线程执行的任务 (阻塞队列方式)
//    private class VoucherOrderHandler implements Runnable {
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    //从阻塞队列获取订单
//                    VoucherOrder voucherOrder = ordersTask.take();
//                    // 处理阻塞队列的订单(抽取一个方法)
//                    blockQueueHandler(voucherOrder);
//                } catch (Exception e) {
//                    log.error("处理订单异常", e);
//                }
//            }
//        }
//    }

    //代理对象(获取代理对象是基于ThreadLocal实现的，因此多线程时要提前获得代理对象)
    private IVoucherOrderService proxy;

    private void blockQueueHandler(VoucherOrder voucherOrder) {
        //创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + voucherOrder.getUserId());
        //获取锁
        boolean tryLock = lock.tryLock();
        //失败打印错误日志
        if (!tryLock) {
            log.error("不能重复下单");
            return;
        }
        //成功，调用创建订单函数
        try {
            proxy.createVoucherOrderWithQueue(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    /*
    * 下单秒杀卷 (redis消息队列)
    * */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //用户ID
        Long userId = UserHolder.getUser().getId();
        //生成订单ID
        long orderId = redisIdWork.getId("order");
        //使用lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId));
        //判断返回的参数，只有为0才可以购买
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        //将代理对象保存，方便后续获取使用代理对象处理事务
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);
    }

//    /*
//    * 下单秒杀卷 （阻塞队列）
//    * */
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //用户ID
//        Long userId = UserHolder.getUser().getId();
//        //使用lua脚本
//        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
//                voucherId.toString(), userId.toString());
//        //判断返回的参数，只有为0才可以购买
//        int r = result.intValue();
//        if (r != 0) {
//            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
//        //生成订单ID
//        long orderId = redisIdWork.getId("order");
//
//        //创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setVoucherId(voucherId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setId(orderId);
//        //放入阻塞队列
//        ordersTask.add(voucherOrder);
//
//        //将代理对象保存，方便后续获取使用代理对象处理事务
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//
//        return Result.ok(orderId);
//    }

//    /*
//     * 下单秒杀卷
//     * */
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //查数据库获得秒杀卷信息
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        //判断开始时间是否合适
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("活动未开始");
//        }
//        //判断结束时间是否合适
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("活动已结束");
//        }
//        //判断库存是否剩余
//        if (seckillVoucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//
//        //加锁 （悲观锁，每个用户给一把锁）（要在事务之外加锁，不然可能释放了锁事务还没提交）
////        synchronized (UserHolder.getUser().getId().toString().intern()) {   //确保拿到的是同一把锁 （同一个ID，toString会创建多个String对象，用inter加到串池中）
////            //事务是基于代理对象实现的，因此这里不能直接调用createVoucherOrder，事务会失效
////            //要先拿到当前类的代理对象，用代理对象调用，事务生效
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//
//        //分布式锁
//        //锁用户的ID
//        Long userId = UserHolder.getUser().getId();
//        //手动创建的锁
////        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        //redisson的方式获得锁 (可重入)
//       RLock lock = redissonClient.getLock("lock:order:" + userId);
//        //获取锁
////        boolean tryLock = simpleRedisLock.tryLock(1200);
//        boolean tryLock = lock.tryLock();
//        if (!tryLock) {
//            return Result.fail("已下单，不能重复下单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            //释放锁
//
//    //            simpleRedisLock.unLock();
//            lock.unlock();
//        }
//    }

    /*
     * 创建秒杀订单(通过队列)
     * */
    @Transactional
    public void createVoucherOrderWithQueue (VoucherOrder voucherOrder) {
        //一人一单（根据用户ID和优惠劵ID查）
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.error("用户已经购买过");
            return;
        }
        //扣减库存 (加乐观锁，添加条件stock > 0)
        boolean update = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!update) {
            log.error("库存不足");
            return;
        }
        save(voucherOrder);
        log.info("成功");
    }

    /*
    * 创建秒杀订单
    * */
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //一人一单（根据用户ID和优惠劵ID查）
        int count = query().eq("user_id", UserHolder.getUser().getId()).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("用户已经下单过了");
        }
        //扣减库存 (加乐观锁，添加条件stock > 0)
        boolean flag = seckillVoucherService.update().setSql("stock = stock - 1").
                eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!flag) {
            return Result.fail("库存不足");
        }
        //保存订单信息
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单ID
        long orderId = redisIdWork.getId("order");
        voucherOrder.setId(orderId);
        //下单用户ID
        voucherOrder.setUserId(UserHolder.getUser().getId());
        //下单优惠劵ID
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        //返回
        return Result.ok(orderId);
    }
}
