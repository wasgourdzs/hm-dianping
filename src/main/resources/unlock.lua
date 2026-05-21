--local key = "lock:order:10"
--local threadId = "uiosajkxhjuaghse-11"
--获取当前锁的ID
local id = redis.call('get', KEYS[1])
--判断是否相等
if (id == ARGV[1]) then
    return redis.call('del', KEYS[1])
end
return 0