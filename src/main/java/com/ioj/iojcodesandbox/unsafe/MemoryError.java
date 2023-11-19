package com.ioj.iojcodesandbox.unsafe;

import java.util.ArrayList;
import java.util.List;

/**
 * 危害2: 无限占用内存 -- 浪费内存、OOM
 */
public class MemoryError {

    public static void main(String[] args) throws InterruptedException {
        List<byte[]> bytes = new ArrayList<>();
        while (true) {
            bytes.add(new byte[10000]);
        }
    }

}
