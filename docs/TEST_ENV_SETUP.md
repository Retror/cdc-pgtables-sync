# 本地测试环境搭建：192.168.234.131 → 192.168.234.132

> 完整、最新的说明请见 [USER_GUIDE.md](USER_GUIDE.md)，本文档只作为测试环境的速查。

本文档说明如何用两台测试库验证 pglsync：

| 角色 | 地址 | 库名 / 用户 |
|---|---|---|
| 源库（写入端） | 192.168.234.131:5432 | typhoon / postgres |
| 目标库（同步端） | 192.168.234.132:5432 | typhoon / postgres |

配置文件：[config.test.json](../config.test.json)。测试数据写入工具：[tools/load_writer.py](../tools/load_writer.py)。

```mermaid
flowchart LR
    W["load_writer.py<br/>每 5 分钟写入约 1 万行/表"] --> A[("131 源库<br/>wal_level=logical")]
    A -- "复制槽 + pgoutput" --> P["pglsync run"]
    P -- "批量 upsert + 位点" --> B[("132 目标库")]
```

**pglsync 运行在哪里？** 推荐运行在 **131** 上，和生产部署方式一致。也可以在你的 Windows 主机上运行，配置不用改，但 131 必须允许主机远程连接（见 1.2 节）。

---

## 1. 131（源库）需要配置的内容

先找到配置文件的位置：

```sql
SHOW config_file;   -- postgresql.conf 的路径
SHOW hba_file;      -- pg_hba.conf 的路径
SELECT version();   -- 版本决定发布模式，见 1.6 节
```

### 1.1 postgresql.conf 参数

| 参数 | 设置值 | 改完需要 | 作用 |
|---|---|---|---|
| `wal_level` | `logical` | **重启** | **最关键的参数。** 默认值 `replica` 只记录物理变更；改成 `logical` 后，WAL 里才有逻辑解码需要的信息（行的主键或旧值等），复制槽才能把变更解码成“哪张表、哪一行、改成了什么” |
| `max_replication_slots` | `10` | **重启** | 复制槽数量上限。pglsync 只用 1 个，留余量给其他用途。默认值是 10，已经大于等于 10 可以不改 |
| `max_wal_senders` | `10` | **重启** | WAL 发送进程上限。每条复制连接占 1 个，pglsync 只占 1 个。默认值是 10 |
| `listen_addresses` | `'*'` | **重启** | 默认只监听 `localhost`。要让 132、Windows 主机或者 131 自己通过 `192.168.234.131` 连进来，必须改 |
| `max_slot_wal_keep_size` | `'10GB'` | 重新加载 | PG13 及以上才有。复制槽最多保留多少 WAL。同步进程停了以后，源库会为它一直保留 WAL；不设上限的话，测试虚拟机磁盘很容易被撑满。**按虚拟机剩余磁盘设置，比如剩余空间的一半。** 超出后复制槽失效，需要重新 `init` |
| `wal_sender_timeout` | `60s`（默认） | 重新加载 | 复制连接超过这个时间没有响应就会被断开。pglsync 每 10 秒发一次心跳，默认值够用，不要设得太小 |
| `logical_decoding_work_mem` | `64MB`（默认） | 重新加载 | PG13 及以上才有。单个事务解码时超过这个大小就写临时文件。测试工具每个事务只写 1000 行，默认值够用 |
| `shared_preload_libraries` | `'timescaledb'` | **重启** | **只在使用 TimescaleDB 时需要**，而且 131 和 132 都要配置 |

可以直接用 SQL 修改（会写入 `postgresql.auto.conf`，优先级高于 `postgresql.conf`）：

```sql
ALTER SYSTEM SET wal_level = 'logical';
ALTER SYSTEM SET max_replication_slots = 10;
ALTER SYSTEM SET max_wal_senders = 10;
ALTER SYSTEM SET listen_addresses = '*';
ALTER SYSTEM SET max_slot_wal_keep_size = '10GB';   -- PG13+，按磁盘调整
-- 使用 TimescaleDB 时（注意不要覆盖已有的其他库，多个库用逗号分隔）：
-- ALTER SYSTEM SET shared_preload_libraries = 'timescaledb';
```

### 1.2 pg_hba.conf：允许远程连接

在 `pg_hba.conf` **靠前的位置**加上下面这行（规则从上往下匹配，先匹配到的生效）：

```
# TYPE  DATABASE  USER      ADDRESS             METHOD
host    typhoon   postgres  192.168.234.0/24    scram-sha-256
```

- 这条规则允许 192.168.234.x 网段的机器连接：pglsync 在 131 本机通过 IP 连接、Windows 主机（通常是 192.168.234.1）连接，都在这个网段里；
- **逻辑复制连接是按数据库名（`typhoon`）匹配的，不需要写 `replication` 关键字。** `replication` 只用于物理复制和 `pg_basebackup`；
- 如果库里的密码是用 md5 加密的（`SHOW password_encryption;` 结果为 `md5`），`METHOD` 写 `md5`。

### 1.3 重启使配置生效

```bash
sudo systemctl restart postgresql        # 服务名可能是 postgresql-15、postgresql@15-main 等
# 不确定服务名时：systemctl list-units | grep -i postgres
```

只改了 `pg_hba.conf` 或者标为“重新加载”的参数时，不需要重启：

```sql
SELECT pg_reload_conf();
```

### 1.4 防火墙放行 5432 端口

```bash
# CentOS / RHEL / Rocky（firewalld）
sudo firewall-cmd --permanent --add-port=5432/tcp && sudo firewall-cmd --reload
# Ubuntu（ufw）
sudo ufw allow 5432/tcp
```

### 1.5 用户权限

测试直接用 `postgres` 超级用户即可，不需要额外授权。

> [!NOTE]
> 生产环境如果想换成专用账号：创建复制槽需要 `REPLICATION` 权限；而创建 `FOR ALL TABLES` 或 `TABLES IN SCHEMA` 类型的发布需要**超级用户**。所以 `setup` 可以先用超级用户执行一次，之后 `run` 再用专用账号运行。

### 1.6 PostgreSQL 版本与 TimescaleDB

| 情况 | 发布方式（`publication_mode: auto` 会自动选择） |
|---|---|
| 没装 TimescaleDB | 只发布配置的两张表 |
| TimescaleDB + **PG15 及以上** | 发布两张表，加上 chunk 所在的 `_timescaledb_internal` schema（推荐） |
| TimescaleDB + PG14 及以下 | `FOR ALL TABLES`：库内所有表都必须有主键；`check` 会把不符合的表列出来 |

### 1.7 验证配置

重启后在 131 上执行：

```sql
SELECT name, setting, pending_restart
FROM pg_settings
WHERE name IN ('wal_level', 'max_replication_slots', 'max_wal_senders', 'listen_addresses',
               'max_slot_wal_keep_size', 'wal_sender_timeout', 'shared_preload_libraries');
```

`wal_level` 应为 `logical`，并且所有行的 `pending_restart` 都应为 `f`（`t` 表示还需要重启才能生效）。

再从 132 或 Windows 主机测试一下能否连上：

```bash
psql -h 192.168.234.131 -p 5432 -U postgres -d typhoon -c "select 1"
```

---

## 2. 132（目标库）需要配置的内容

目标库**不需要**修改 WAL 相关参数，只需要：

1. `listen_addresses = '*'`（需要重启），以及在 `pg_hba.conf` 中允许 131 和你的主机连接：
   ```
   host    typhoon   postgres  192.168.234.0/24    scram-sha-256
   ```
2. 防火墙放行 5432 端口，方法同 1.4；
3. 如果源表是超表，132 也要安装 TimescaleDB（`shared_preload_libraries` 加上 `CREATE EXTENSION timescaledb`），并把表建成超表；
4. 建好与源库**相同结构**的表，主键约束必须存在（见第 3 节）；
5. 同步位点表 `pglsync.sync_state` 由 pglsync 自动创建，不需要手动处理。

---

## 3. 在两个库上建表

> 如果 131 和 132 上已经建好表、而且数据已经同步过，可以跳过本节，直接看第 4 节的“情况 A”。

### 方式 A：导出生产表结构（推荐，和生产完全一致）

```bash
pg_dump -h <生产库地址> -p 5000 -U postgres -s \
        -t public.tcrealtime -t public.tcensemblerealtime typhoon > schema.sql
```

导入前先打开 `schema.sql`，**删掉以下内容**：
- 旧 CDC 方案的触发器（`CREATE TRIGGER ... cdc...`）。测试库里没有对应的函数，导入会报错，而且新方案也不需要它们；
- 如果有 `ts_insert_blocker` 触发器，也删掉（老版本 TimescaleDB 的内部触发器，`create_hypertable` 会自动处理）。

然后在两个库上分别导入，并转成超表：

```bash
psql -h 192.168.234.131 -U postgres -d typhoon -f schema.sql
psql -h 192.168.234.132 -U postgres -d typhoon -f schema.sql
```

```sql
-- 131 和 132 都要执行（使用 TimescaleDB 时）
CREATE EXTENSION IF NOT EXISTS timescaledb;
SELECT create_hypertable('public.tcrealtime', 'datatime', migrate_data => true, if_not_exists => true);
SELECT create_hypertable('public.tcensemblerealtime', 'datatime', migrate_data => true, if_not_exists => true);
```

### 方式 B：使用示例表结构

```bash
psql -h 192.168.234.131 -U postgres -d typhoon -f tools/test_schema.sql
psql -h 192.168.234.132 -U postgres -d typhoon -f tools/test_schema.sql
```

脚本会自动判断：装了 TimescaleDB 就建成超表，没装就建普通表。

> [!NOTE]
> 写入工具会**自动读取表结构**生成数据，两种方式都能用。如果生产表上有 CHECK 约束（比如限制纬度范围），随机值可能违反约束，到时候把报错信息发给我，我来调整生成规则。

---

## 4. 启动同步

以下命令在仓库根目录执行。在 131 上运行时，先把代码拷到 131：

```bash
pip3 install -r requirements.txt     # Windows 上：pip install -r requirements.txt

python3 -m pglsync -c config.test.json check     # 1. 检查 131 的参数，必须没有“问题”才能继续
python3 -m pglsync -c config.test.json setup     # 2. 在 131 上创建发布 pglsync_test_pub
```

第 3 步按 132 的现状二选一：

### 情况 A：132 上已经有和 131 一致的数据（不想重新全量复制）

```bash
python3 -m pglsync -c config.test.json init --no-copy   # 3. 创建复制槽，核对两边数据，一致就从当前位置开始同步
```

`--no-copy` 的过程：

1. 在 131 上创建复制槽，同时得到一个**一致性快照**和对应的 WAL 位点；
2. 用这个快照读取 131，和 132 逐表比较行数和整行哈希。超表按天比较，能定位到具体日期；
3. 全部一致：不改动 132 上的任何业务数据，只记录同步位点，之后从这个位点开始增量同步；
4. 有不一致：**默认放弃初始化**，删除刚建的复制槽，不做任何修改，并列出哪些表、哪几天对不上。

有不一致时可以这样处理：

```bash
python3 -m pglsync -c config.test.json init --no-copy --on-mismatch copy   # 只重新复制不一致的表，其他表不动
python3 -m pglsync -c config.test.json init                                # 或者全部重新复制
```

> [!TIP]
> 比较用的是快照，所以初始化期间 131 可以照常写入，不需要停业务（`load_writer.py` 可以继续跑）。快照之前的写入必须已经在 132 上才算一致；快照之后的写入由增量同步补上。
>
> 只要从上次同步到现在 131 有过新的写入、而 132 还没收到，比较就会显示不一致。这时用 `--on-mismatch copy` 只补这些表即可。

数据量非常大、核对耗时太长时，可以加 `--skip-verify` 跳过核对，但要**自己确保**此刻两边完全一致，并且 131 上没有写入。否则差异会一直留在 132 上，只能靠 `reconcile` 发现。

### 情况 B：132 是空表，或者数据不可信

```bash
python3 -m pglsync -c config.test.json init      # 3. 创建复制槽，并全量复制到 132（会清空 132 上配置的这些表）
```

### 启动增量同步

```bash
python3 -m pglsync -c config.test.json run       # 4. 前台运行增量同步，Ctrl+C 停止
```

`run` 会一直占用终端，所以后面的写入和验证命令请另开一个终端执行。日志同时写入 `logs/pglsync-test.log`。

> [!NOTE]
> [config.test.json](../config.test.json) 已配置全部 14 张表。如果 131 上的某些表还没有和 132 对齐，可以直接用 `--on-mismatch copy`，或者先把这些表从 `tables` 里删掉。

---

## 5. 定时写入测试数据

```bash
python3 tools/load_writer.py -c config.test.json
```

默认行为：**每 5 分钟一轮，每轮向 `tcrealtime` 和 `tcensemblerealtime` 各写入约 1 万行**（上下浮动 10%）。每轮的具体操作：

1. **INSERT**：每 1000 行一个事务；
2. **晚提交的长事务**：每轮先开一个事务写入 100 行但不提交，等其他批次都提交后它才提交。**旧的触发器方案正是在这种情况下丢数据的**，用来验证 pglsync 不会丢；
3. **UPDATE**：随机更新本轮写入数据的 5%；
4. **DELETE**：随机删除本轮写入数据的 1%。

`datatime` 的值随机分布在最近 24 小时内，会落到多个 chunk 中。

常用参数：

| 参数 | 默认值 | 说明 |
|---|---|---|
| `--rows` | 10000 | 每张表每轮写入多少行 |
| `--interval` | 300 | 每轮间隔多少秒 |
| `--once` | — | 只写一轮就退出 |
| `--txn-size` | 1000 | 每个事务多少行 |
| `--long-txn-rows` | 100 | 长事务行数，0 表示不模拟 |
| `--update-ratio` / `--delete-ratio` | 0.05 / 0.01 | 更新和删除的比例 |
| `-t` | 两张超表 | 要写入的表，逗号分隔 |
| `--time-spread-hours` | 24 | 时间列取值范围（往前多少小时） |

先用 `--once --rows 100` 试一轮，确认没有报错后再常驻运行。在 Linux 上后台常驻运行：

```bash
nohup python3 tools/load_writer.py -c config.test.json > logs/load_writer.out 2>&1 &
```

也可以改用 crontab 每 5 分钟执行一次：

```bash
*/5 * * * * cd /path/to/cdc-pgtables-sync && python3 tools/load_writer.py -c config.test.json --once >> logs/load_writer.out 2>&1
```

---

## 6. 验证同步结果

```bash
python3 -m pglsync -c config.test.json status                      # 复制槽是否活跃、落后多少
python3 -m pglsync -c config.test.json reconcile --recheck-delay 15  # 行数 + 整行哈希核对
python3 -m pglsync -c config.test.json reconcile --detail          # 有差异时列出具体主键
```

建议依次验证以下场景：

- [ ] 写入几轮后执行 `reconcile`，全部显示“一致”；
- [ ] **断点续传**：在写入过程中按 Ctrl+C 停掉 `run`，等写入工具再跑一两轮，重新启动 `run`，然后 `reconcile` 仍然一致；
- [ ] **重启源库**：重启 131 的 PostgreSQL，pglsync 会自动重连，之后数据一致；
- [ ] **目标库断开**：停掉 132 一段时间再启动，pglsync 会自动恢复，数据一致；
- [ ] **TRUNCATE**：在 131 上执行 `TRUNCATE public.tcrealtime;`，132 上这张表也会被清空；
- [ ] 观察日志中每分钟一次的统计，“最近事务延迟”应该在几秒以内。

---

## 7. 清理测试环境

```bash
# 先停掉 load_writer.py 和 pglsync run
python3 -m pglsync -c config.test.json drop -y     # 删除 131 上的复制槽和发布，以及 132 上的同步位点
```

> [!WARNING]
> 测试结束后**一定要执行 `drop`**。否则 131 会一直为复制槽保留 WAL，直到达到 `max_slot_wal_keep_size` 的上限，或者把磁盘写满。
