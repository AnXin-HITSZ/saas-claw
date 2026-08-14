package com.saasclaw.backend.config;

import java.lang.annotation.*;

/**
 * 标记需要管理员权限的接口。
 * 由 AdminAspect 检查：方法或类上标注后，仅 role=1 的用户可访问。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireAdmin {
}
