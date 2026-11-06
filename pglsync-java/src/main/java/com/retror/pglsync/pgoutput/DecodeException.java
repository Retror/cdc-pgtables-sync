package com.retror.pglsync.pgoutput;

/** pgoutput 消息解码失败（协议不符合预期）。 */
public class DecodeException extends RuntimeException {
    public DecodeException(String message) {
        super(message);
    }
}
