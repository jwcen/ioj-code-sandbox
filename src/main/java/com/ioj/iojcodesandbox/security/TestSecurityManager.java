package com.ioj.iojcodesandbox.security;

import cn.hutool.core.io.FileUtil;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class TestSecurityManager {
    public static void main(String[] args) {
        System.setSecurityManager(new SelfDefineSecurityManager());

        List<String> strings = FileUtil.readLines("/Users/jcen/projects/ioj-code-sandbox/src/main/resources/木马程序.sh", StandardCharsets.UTF_8);
        System.out.println(strings);
    }
}
