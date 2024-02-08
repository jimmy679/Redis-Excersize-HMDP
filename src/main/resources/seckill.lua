-- 订单id
local voucherId = ARGV[1]
-- 用户id
local userId = ARGV[2]
-- 新增orderId，但是变量名用id就好，因为VoucherOrder实体类中的orderId就是用id表示的
local id = ARGV[3]
-- 优惠券key
local stockKey = 'seckill:stock:' .. voucherId
-- 订单key
local orderKey = 'seckill:order:' .. voucherId
-- 判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
    return 1
end
-- 判断用户是否下单
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end
-- 扣减库存
redis.call('incrby', stockKey, -1)
-- 将userId存入当前优惠券的set集合
redis.call('sadd', orderKey, userId)
-- 将下单数据保存到消息队列中
redis.call("sadd", 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', id)
return 0