package com.saasclaw.backend.common;

import lombok.Getter;

@Getter
public class BizException extends RuntimeException {

    private final int code;

    /** 可选附加数据（如 409 时回传的绑定 Agent 列表），默认 null */
    private final Object data;

    public BizException(int code, String message) {
        this(code, message, null);
    }

    public BizException(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }
}
