package com.ioj.iojcodesandbox.security;

import java.security.Permission;

/**
 *
 */
public class DenyAllSecurityManager extends SecurityManager {

    /**
     * 检查所有的权限
     * @param perm   the requested permission.
     */
    @Override
    public void checkPermission(Permission perm) {
        System.out.println("禁用所有限制");
        super.checkPermission(perm);
        throw new SecurityException("权限异常：" + perm.toString());
    }
}
