--优惠劵ID
local voucherId = ARGV[1]
--用户ID
local userId = ARGV[2]
--订单ID
local orderId = ARGV[3]
--优惠劵Key
local voucherKey = 'seckill:stock:' .. voucherId
--订单Key
local orderKey = 'seckill:order:' .. voucherId
--判断库存是否充足
if (tonumber(redis.call('get', voucherKey)) <= 0 ) then
    --不足返回1
    return 1
end
--判断是否已下单 sismember
if (redis.call('sismember', orderKey, userId) == 1) then
    --存在返回2
    return 2
end
--减少库存
redis.call('incrby', voucherKey, -1)
--增加订单
redis.call('sadd', orderKey, userId)
--添加到消息队列
redis.call('xadd', 'stream.order', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId);
return 0