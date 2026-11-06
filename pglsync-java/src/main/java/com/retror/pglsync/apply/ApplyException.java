package com.retror.pglsync.apply;

/** 应用变更失败（数据层面的问题）。 */
public class ApplyException extends RuntimeException {
    public ApplyException(String message) {
        super(message);
    }
}
