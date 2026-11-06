package com.retror.pglsync.config;

/** 配置错误或无法自动恢复的前置条件错误：进程应当退出，而不是无限重试。 */
public class ConfigException extends RuntimeException {
    public ConfigException(String message) {
        super(message);
    }
}
