# pglsync-java：Java / Spring Boot 版

这是 pglsync 的 Java 实现，基于 **Spring Boot 2.7 + JDK 8**，功能与仓库根目录的 Python 版 `pglsync` 一一对应：

- 读取 PostgreSQL 逻辑复制流（内置的 `pgoutput` 插件），按**源库提交顺序**把指定表的变更同步到目标库；
- 支持 TimescaleDB 超表：chunk 上的变更会自动映射回超表。

数据库配置、原理、表结构变更流程等通用内容，请看 [docs/USER_GUIDE.md](../docs/USER_GUIDE.md)。本文只说明 Java 版**不一样的地方**：构建、配置文件、命令写法。

---

## 1. 与 Python 版的对应关系

| 功能 | Python 版 | Java 版 |
|---|---|---|
| 检查源库 | `python3 -m pglsync -c config.json check` | `java -jar pglsync.jar check` |
| 创建发布 | `setup [--fix-replica-identity] [--force]` | `setup [--fix-replica-identity] [--force]` |
| 初始化（全量） | `init [-y] [--recreate-slot]` | `init [--yes] [--recreate-slot]` |
| 初始化（已有数据） | `init --no-copy --on-mismatch copy` | `init --no-copy --on-mismatch=copy` |
| 增量同步 | `run` | `run` |
| 单表重新复制 | `resync -t 表 -t 表` | `resync --table=表 --table=表` |
| 状态 | `status` | `status` |
| 核对 | `reconcile -t 表 --since 日期 --detail` | `reconcile --table=表 --since=日期 --detail` |
| 停用 | `drop [-y] [--keep-publication]` | `drop [--yes] [--keep-publication]` |
| 测试写入 | `python3 tools/load_writer.py --rows 10000` | `load-writer --rows=10000` |
| 配置文件 | `config.json` | `application.yml`（Spring Boot 配置） |

两点差别：

- **选项写法**：Java 版的选项一律写成 `--名称=值`；
- **数据结构完全兼容**：两个版本在目标库使用同样的 `pglsync.sync_state` / `pglsync.table_state` 位点表，同步逻辑也一致。

> [!WARNING]
> 两个版本**不要同时**向同一个目标库同步。测试配置里 Java 版用的是独立的复制槽和发布（`pglsync_java_test_slot` / `pglsync_java_test_pub`）；从 Python 版切换到 Java 版时，先停掉 Python 版并执行 `drop`。

---

## 2. 构建

需要 JDK 8 及以上和 Maven 3.6 及以上。

```bash
cd pglsync-java
mvn -B clean package          # 生成 target/pglsync.jar，同时运行单元测试（不需要数据库）
mvn -B clean package -DskipTests
```

生成的 `target/pglsync.jar` 是一个可执行 jar，所有依赖都打包在里面，拷贝到服务器后用 `java -jar` 运行即可。

服务器上只需要 **JRE 8** 及以上：

```bash
java -version
```

---

## 3. 配置文件

配置使用 Spring Boot 的 `application.yml`，前缀为 `pglsync`：

| 文件 | 用途 |
|---|---|
| [application.yml](src/main/resources/application.yml) | 默认配置（生产模板）。包含 14 张表，密码从环境变量 `PGLSYNC_SOURCE_PASSWORD` / `PGLSYNC_TARGET_PASSWORD` 读取 |
| [application-test.yml](src/main/resources/application-test.yml) | 测试环境 131 → 132，只覆盖连接信息和复制槽名，表清单继承默认配置 |

### 3.1 选择配置

```bash
# 使用 jar 内置的测试配置（131 -> 132）
java -jar pglsync.jar status --spring.profiles.active=test

# 使用外部配置文件（推荐生产使用）：外部文件中的值会覆盖 jar 内置的默认值
java -jar pglsync.jar status --spring.config.additional-location=file:/opt/pglsync-java/application.yml
```

也可以把 `application.yml` 直接放在**运行目录**或运行目录下的 `config/` 子目录里，Spring Boot 会自动加载。

### 3.2 配置项

```yaml
pglsync:
  source:                    # 源库
    host: 192.168.234.131
    port: 5432
    dbname: typhoon
    user: postgres
    password: ${PGLSYNC_SOURCE_PASSWORD:}   # 从环境变量读取
    params:                  # 可选：其他 pgjdbc 连接参数
      sslmode: prefer
  target: { ... }            # 目标库，写法同 source

  slot-name: pglsync_slot          # 复制槽名
  publication: pglsync_pub         # 发布名
  state-schema: pglsync            # 目标库中存放位点表的 schema
  publication-mode: auto           # auto / schema / all
  chunk-schemas: [_timescaledb_internal]

  tables:                          # 需要同步的表（列表）
    - name: public.base_common_dict          # pk 不填时取源表主键
    - name: public.tcrealtime
      pk: [id, datatime]
      time-column: datatime                  # reconcile 按时间分桶
      bucket: day                            # hour / day / week / month

  apply:
    batch-max-changes: 5000        # 累计多少条变更提交一次目标库事务
    batch-max-seconds: 1.0         # 或最多攒多少秒
    upsert-group-size: 500         # 每条批量 INSERT 最多多少行
    on-missing-row: warn           # warn / error
    chunk-truncate: warn           # warn / error

  stream:
    status-interval-seconds: 10
    retry-initial-seconds: 5
    retry-max-seconds: 60
    stats-interval-seconds: 60

logging:
  file:
    name: logs/pglsync.log         # 日志文件（单个 50MB，保留 10 个）
```

> [!NOTE]
> 使用外部配置文件覆盖 `tables` 时，列表会**整体替换**，不会和 jar 内置的列表合并。

---

## 4. 运行

下文以测试环境为例。为了简洁，先定义一个别名：

```bash
cd /opt/pglsync-java
alias pgl='java -jar /opt/pglsync-java/pglsync.jar --spring.profiles.active=test'
```

### 4.1 首次启动

```bash
pgl check                                   # 1. 只读检查源库参数
pgl setup                                   # 2. 创建发布
pgl init --no-copy                          # 3a. 132 已有一致数据：核对后从当前位置开始
pgl init --no-copy --on-mismatch=copy       #     核对不一致时，只重新复制不一致的表
pgl init                                    # 3b. 132 为空：全量复制（会 TRUNCATE 目标表）
pgl run                                     # 4. 增量同步（前台运行，Ctrl+C 停止）
```

### 4.2 后台运行（systemd）

```bash
sudo cp deploy/pglsync-java.service /etc/systemd/system/
# 按实际情况修改 WorkingDirectory、jar 路径和配置文件路径；测试环境可以把
# --spring.config.additional-location=... 换成 --spring.profiles.active=test
sudo systemctl daemon-reload
sudo systemctl enable --now pglsync-java
journalctl -u pglsync-java -f
```

收到 `SIGTERM`（`systemctl stop`）后，程序会处理完当前事务、提交位点后再退出。

### 4.3 日常运维

```bash
pgl status                                           # 复制槽状态、积压、同步位点
pgl reconcile                                        # 核对全部表
pgl reconcile --table=public.tcrealtime --since=2026-11-01 --detail
pgl resync --table=public.new_table                  # 单表重新复制（先停止 run）
pgl drop --yes                                       # 停用同步（删除复制槽和发布）
```

各命令的输出、日志含义、退出码都与 Python 版相同，详见 [USER_GUIDE.md 第 5 节](../docs/USER_GUIDE.md#5-同步程序的运行方式)。新增同步表和修改表结构的步骤见 [USER_GUIDE.md 第 8 节](../docs/USER_GUIDE.md#8-新增同步表与表结构变更)，把命令换成 Java 版写法即可。

### 4.4 测试写入

```bash
pgl load-writer --once --rows=100                    # 先小量试一轮
pgl load-writer                                      # 每 5 分钟一轮，两张超表各写约 1 万行
pgl load-writer --rows=5000 --interval=60 --txn-size=500
nohup java -jar pglsync.jar load-writer --spring.profiles.active=test > logs/load-writer.out 2>&1 &
```

| 参数 | 默认值 | 说明 |
|---|---|---|
| `--tables=a,b` | `public.tcrealtime,public.tcensemblerealtime` | 要写入的表 |
| `--rows` | 10000 | **每张表**每轮写入行数（上下浮动 10%） |
| `--interval` | 300 | 每轮间隔秒数 |
| `--once` | — | 只写一轮 |
| `--txn-size` | 1000 | 每个事务多少行 |
| `--long-txn-rows` | 100 | 每轮“晚提交长事务”的行数，0 表示不模拟 |
| `--update-ratio` / `--delete-ratio` | 0.05 / 0.01 | 更新和删除的比例 |
| `--time-column` | datatime | 时间列名 |
| `--time-spread-hours` | 24 | 时间列取值范围 |

退出码：`0` 成功；`1` 执行失败；`2` 有不一致（reconcile）或用法错误；`3` 配置或前置条件错误。

---

## 5. 代码结构

```
src/main/java/com/retror/pglsync/
├── PglsyncApplication.java        Spring Boot 入口
├── CommandRunner.java             命令行分发（ApplicationRunner）
├── config/                        配置（PglsyncProperties → 校验后的 SyncConfig）
├── pgoutput/PgOutput.java         pgoutput 协议解码
├── catalog/                       relation 缓存、chunk → 超表映射、元数据查询
├── apply/Applier.java             幂等写入目标库（批量 upsert、主键变更、TOAST、TRUNCATE）
├── stream/                        增量同步主循环（Streamer / Session）
├── state/StateStore.java          目标库位点表
└── service/                       init / resync / setup / status / drop / reconcile / load-writer
```

实现要点：

- **复制连接**使用 pgjdbc 的 `PGReplicationStream`；
- **一致性快照**通过在复制连接上执行 `CREATE_REPLICATION_SLOT ... EXPORT_SNAPSHOT` 获得；
- **全量复制**用 pgjdbc `CopyManager`，源库 `COPY TO` 经管道流式写入目标库 `COPY FROM`，不落盘；
- **列值类型**：列值始终保持 PostgreSQL 文本格式，连接参数 `stringtype=unspecified` 让目标库按列类型解析，不会丢精度；
- **单元测试**：协议解码、幂等写入、主键变更、TOAST、TRUNCATE、重放跳过、resync 跳过逻辑都有覆盖，运行 `mvn test` 即可，不需要数据库。
