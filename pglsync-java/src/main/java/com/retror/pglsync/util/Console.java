package com.retror.pglsync.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/** 控制台输出与交互确认。 */
public final class Console {

    private Console() {
    }

    public static void println(String s) {
        System.out.println(s);
    }

    public static void printf(String fmt, Object... args) {
        System.out.printf(fmt, args);
    }

    /** 要求用户输入 yes 才继续。 */
    public static boolean confirm(boolean assumeYes) {
        if (assumeYes) {
            return true;
        }
        System.out.print("\n输入 yes 继续: ");
        System.out.flush();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset()));
            String line = r.readLine();
            if (line != null && "yes".equalsIgnoreCase(line.trim())) {
                return true;
            }
        } catch (IOException ignored) {
            // treat as cancel
        }
        System.out.println("已取消");
        return false;
    }

    /** 用千分位格式化数字。 */
    public static String n(long v) {
        return String.format("%,d", v);
    }
}
