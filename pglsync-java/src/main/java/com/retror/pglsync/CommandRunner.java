package com.retror.pglsync;

import com.retror.pglsync.config.ConfigException;
import com.retror.pglsync.config.PglsyncProperties;
import com.retror.pglsync.config.SyncConfig;
import com.retror.pglsync.service.AdminService;
import com.retror.pglsync.service.LoadWriter;
import com.retror.pglsync.service.ReconcileService;
import com.retror.pglsync.service.SetupService;
import com.retror.pglsync.service.SnapshotService;
import com.retror.pglsync.stream.Streamer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 命令行入口：java -jar pglsync.jar &lt;命令&gt; [--选项...]
 *
 * <pre>
 * check                                   只读检查源库逻辑复制前置条件
 * setup [--fix-replica-identity] [--force] 检查并在源库创建发布
 * init [--yes] [--recreate-slot] [--no-copy] [--on-mismatch=abort|copy|ignore] [--skip-verify]
 * run                                     增量同步（常驻进程）
 * resync --table=表 [--table=表...] [--yes] 重新全量复制指定表（需先停止 run）
 * status                                  查看复制槽积压与同步位点
 * reconcile [--table=表] [--since=2026-10-01] [--recheck-delay=10] [--detail] [--limit=20]
 * drop [--yes] [--keep-publication]       删除复制槽与发布
 * load-writer [--tables=a,b] [--rows=10000] [--interval=300] [--once] ...   测试数据写入
 * </pre>
 */
@Component
public class CommandRunner implements ApplicationRunner, ExitCodeGenerator {
    private static final Logger log = LoggerFactory.getLogger(CommandRunner.class);
    private static final List<String> COMMANDS = Arrays.asList(
            "check", "setup", "init", "run", "resync", "status", "reconcile", "drop", "load-writer");

    private final PglsyncProperties properties;
    private int exitCode;

    public CommandRunner(PglsyncProperties properties) {
        this.properties = properties;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> positional = args.getNonOptionArgs();
        if (positional.isEmpty() || !COMMANDS.contains(positional.get(0))) {
            printUsage();
            exitCode = 2;
            return;
        }
        String command = positional.get(0);
        try {
            SyncConfig cfg = SyncConfig.from(properties);
            exitCode = dispatch(command, cfg, args);
        } catch (ConfigException e) {
            System.err.println("错误: " + e.getMessage());
            log.error(e.getMessage());
            exitCode = 3;
        } catch (Exception e) {
            log.error("命令 {} 执行失败", command, e);
            exitCode = 1;
        }
    }

    private int dispatch(String command, SyncConfig cfg, ApplicationArguments a) throws Exception {
        switch (command) {
            case "check":
                return new SetupService(cfg).run(false, false, false);
            case "setup":
                return new SetupService(cfg).run(true, a.containsOption("fix-replica-identity"), a.containsOption("force"));
            case "init": {
                boolean noCopy = a.containsOption("no-copy");
                String onMismatch = opt(a, "on-mismatch", "abort");
                if (!Arrays.asList("abort", "copy", "ignore").contains(onMismatch)) {
                    throw new ConfigException("--on-mismatch 只能是 abort / copy / ignore");
                }
                if ((a.containsOption("skip-verify") || !"abort".equals(onMismatch)) && !noCopy) {
                    throw new ConfigException("--skip-verify / --on-mismatch 只能与 --no-copy 一起使用");
                }
                return new SnapshotService(cfg).init(a.containsOption("yes"), a.containsOption("recreate-slot"),
                        !noCopy, !a.containsOption("skip-verify"), onMismatch);
            }
            case "run":
                return runStreamer(cfg);
            case "resync":
                return new SnapshotService(cfg).resync(list(a, "table"), a.containsOption("yes"));
            case "status":
                return new AdminService(cfg).status();
            case "reconcile":
                return new ReconcileService(cfg).run(list(a, "table"), opt(a, "since", null),
                        Double.parseDouble(opt(a, "recheck-delay", "10")), a.containsOption("detail"),
                        Integer.parseInt(opt(a, "limit", "20")));
            case "drop":
                return new AdminService(cfg).drop(a.containsOption("yes"), a.containsOption("keep-publication"));
            case "load-writer":
                return runLoadWriter(cfg, a);
            default:
                printUsage();
                return 2;
        }
    }

    private int runStreamer(SyncConfig cfg) throws InterruptedException {
        Streamer streamer = new Streamer(cfg);
        Thread hook = new Thread(() -> {
            streamer.requestStop();
            try {
                streamer.awaitStopped(110);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "pglsync-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        log.info("pglsync 启动，同步 {} 张表", cfg.getTables().size());
        streamer.runForever();
        return 0;
    }

    private int runLoadWriter(SyncConfig cfg, ApplicationArguments a) throws InterruptedException {
        LoadWriter.Options o = new LoadWriter.Options();
        if (a.containsOption("tables")) {
            o.tables = Arrays.asList(opt(a, "tables", "").split(","));
        }
        o.rows = Integer.parseInt(opt(a, "rows", String.valueOf(o.rows)));
        o.interval = Double.parseDouble(opt(a, "interval", String.valueOf(o.interval)));
        o.once = a.containsOption("once");
        o.txnSize = Integer.parseInt(opt(a, "txn-size", String.valueOf(o.txnSize)));
        o.longTxnRows = Integer.parseInt(opt(a, "long-txn-rows", String.valueOf(o.longTxnRows)));
        o.updateRatio = Double.parseDouble(opt(a, "update-ratio", String.valueOf(o.updateRatio)));
        o.deleteRatio = Double.parseDouble(opt(a, "delete-ratio", String.valueOf(o.deleteRatio)));
        o.timeColumn = opt(a, "time-column", o.timeColumn);
        o.timeSpreadHours = Double.parseDouble(opt(a, "time-spread-hours", String.valueOf(o.timeSpreadHours)));
        LoadWriter writer = new LoadWriter(cfg, o);
        Runtime.getRuntime().addShutdownHook(new Thread(writer::requestStop, "load-writer-shutdown"));
        return writer.run();
    }

    private static String opt(ApplicationArguments a, String name, String def) {
        List<String> v = a.getOptionValues(name);
        return v == null || v.isEmpty() ? def : v.get(v.size() - 1);
    }

    private static List<String> list(ApplicationArguments a, String name) {
        List<String> v = a.getOptionValues(name);
        List<String> out = new ArrayList<>();
        if (v != null) {
            for (String s : v) {
                for (String part : s.split(",")) {
                    if (!part.trim().isEmpty()) {
                        out.add(part.trim());
                    }
                }
            }
        }
        return out;
    }

    private static void printUsage() {
        System.out.println(String.join("\n",
                "用法: java -jar pglsync.jar <命令> [--选项...]",
                "",
                "命令:",
                "  check                                    只读检查源库逻辑复制前置条件",
                "  setup [--fix-replica-identity] [--force] 检查并在源库创建发布",
                "  init [--yes] [--recreate-slot]           创建复制槽 + 一致性快照全量复制（会 TRUNCATE 目标表）",
                "       [--no-copy] [--on-mismatch=abort|copy|ignore] [--skip-verify]",
                "                                           --no-copy：目标库已有数据时，核对后从当前位置开始",
                "  run                                      启动增量同步（常驻进程）",
                "  resync --table=表 [--table=...] [--yes]  重新全量复制指定表（需先停止 run）",
                "  status                                   查看复制槽积压与同步位点",
                "  reconcile [--table=表] [--since=日期] [--recheck-delay=10] [--detail] [--limit=20]",
                "  drop [--yes] [--keep-publication]        删除复制槽与发布",
                "  load-writer [--tables=a,b] [--rows=10000] [--interval=300] [--once] [--txn-size=1000]",
                "              [--long-txn-rows=100] [--update-ratio=0.05] [--delete-ratio=0.01]",
                "              [--time-column=datatime] [--time-spread-hours=24]   测试数据写入",
                "",
                "配置: 默认读取 application.yml；测试环境加 --spring.profiles.active=test，",
                "      或用 --spring.config.additional-location=file:/path/to/application-xxx.yml 指定外部配置"));
    }
}
