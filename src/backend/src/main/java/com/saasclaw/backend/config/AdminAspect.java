package com.saasclaw.backend.config;

import com.saasclaw.backend.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
public class AdminAspect {

    @Around("@within(com.saasclaw.backend.config.RequireAdmin) || @annotation(com.saasclaw.backend.config.RequireAdmin)")
    public Object checkAdmin(ProceedingJoinPoint joinPoint) throws Throwable {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attrs.getRequest();
        Integer role = (Integer) request.getAttribute("role");
        if (role == null || role != 1) {
            throw new BizException(403, "无权限，仅管理员可操作");
        }
        return joinPoint.proceed();
    }
}
