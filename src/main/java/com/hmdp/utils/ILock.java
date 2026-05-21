package com.hmdp.utils;

public interface ILock {

    /**
     * 获取锁
     * @param timeoutSec 设置锁的超时时间，到期自动释放
     * @return 返回true则获取成功，否则获取失败
     */
    boolean tryLock (long timeoutSec);

    /*
    * 释放锁
    * */
    void unLock();
}
