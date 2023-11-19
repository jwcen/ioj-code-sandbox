package com.ioj.iojcodesandbox.unsafe;

/**
 * 危害1: 无限睡眠程序 -- 阻塞程序执行
 */
public class SleepError {

    public static void main(String[] args) throws InterruptedException {
        long ONE_HOUR = 60 * 60 * 1000L;
        Thread.sleep(ONE_HOUR);
        System.out.println("睡完了");
    }

}
