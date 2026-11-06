package com.retror.pglsync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * pglsync：基于 PostgreSQL 逻辑复制（WAL 逻辑解码 / pgoutput）的单向表同步工具。
 *
 * <p>用法：java -jar pglsync.jar &lt;命令&gt; [选项]，命令见 {@link CommandRunner}。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PglsyncApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(PglsyncApplication.class);
        System.exit(SpringApplication.exit(app.run(args)));
    }
}
