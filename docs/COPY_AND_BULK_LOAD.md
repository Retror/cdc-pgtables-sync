# COPY 与批量导入：会不会同步？要注意什么？

## 结论

**会同步。** 用 `COPY ... FROM` 或 psql 的 `\copy` 往源库写入的数据，和 INSERT 写入的一样，会被逻辑复制捕获并同步到目标库。**COPY 也是有事务的。**

需要注意的只有两点：

1. 一次 COPY 很多行，就是一个**大事务**，目标库要等它全部应用完才一次性看到（第 3 节）；
2. 少数**绕过 WAL** 的写入方式不会被同步（第 4 节）。

---

## 1. COPY 的事务语义

| 执行方式 | 事务行为 |
|---|---|
| 单独执行（自动提交模式） | 一条 `COPY ... FROM` 就是**一个事务**：整个文件要么全部写入，要么中途出错时整体回滚，一行都不写入 |
| 放在 `BEGIN ... COMMIT` 中 | 是这个事务的一部分，和同一事务里的其他语句一起提交或回滚 |
| psql 的 `\copy` | 在客户端读文件，执行的仍然是服务器端的 COPY，事务行为相同 |
| PG17 的 `COPY ... WITH (ON_ERROR ignore)` | 跳过格式错误的行，其余的行仍在同一个事务里提交 |

`COPY ... TO`（导出数据）不修改数据，与同步无关。

---

## 2. 为什么 COPY 能被同步

逻辑复制不关心数据是用哪种 SQL 写进来的，它读取的是 **WAL**：

```mermaid
flowchart LR
    A["源库 COPY FROM<br/>批量写入"] --> B["WAL<br/>按批记录插入"]
    B --> C["逻辑解码<br/>还原为逐行 INSERT"]
    C --> D["pglsync<br/>每 500 行合并为一条<br/>INSERT ... ON CONFLICT"]
    D --> E["目标库"]
```

- COPY 写入时，PostgreSQL 同样把每一行记录到 WAL（为了效率是成批记录的）；
- `wal_level = logical` 时，PostgreSQL **不会**为了加速而跳过 WAL（只有 `wal_level = minimal` 才会，这和逻辑复制本来就不兼容）；
- 源库逻辑解码时，把这些记录还原成逐行的 INSERT 发给 pglsync；
- **往 TimescaleDB 超表 COPY 也一样**：数据被路由到对应的 chunk，chunk 的变更同样会被捕获，并映射回超表；
- COPY 会触发行级触发器。旧的触发器方案也能捕获 COPY，但新方案不依赖触发器。

---

## 3. 大批量 COPY 就是一个大事务

一次 COPY 几百万行，表现和[大批量 DELETE](CASE_LARGE_DELETE.md) 完全相同：

| 现象 | 原因 |
|---|---|
| 目标库要等整个 COPY 的数据都应用完，才一次性看到 | 逻辑复制以事务为单位，提交后才整体发送；pglsync 在目标库也用一个事务应用，全部完成才提交 |
| 这期间目标库同步位点不动，后面的变更排队 | 同步严格按提交顺序进行 |
| 源库保留大量 WAL，并写解码临时文件（`pg_stat_replication_slots.spill_bytes` 增长） | 解码时超过 `logical_decoding_work_mem` 的部分会写盘 |
| 日志中出现“正在应用大事务 xid=…：已处理 N 条变更” | 每 10 万条变更输出一次进度 |

pglsync 会把连续的 INSERT 合并成多行语句（每批 `upsert_group_size` 行，默认 500），所以写入目标库的速度比逐行快得多。

### 建议

**① 希望目标库逐步看到数据**：把大文件拆成多个文件，分多次 COPY，每次一个事务。

```bash
# 按 50 万行拆分（CSV 有表头时，先去掉表头再拆）
tail -n +2 data.csv | split -l 500000 - part_

for f in part_*; do
  psql -h 192.168.234.131 -U postgres -d typhoon -c "\copy tcensemblerealtime FROM '$f' WITH (FORMAT csv)"
done
```

代价：失去“要么全部导入、要么全不导入”的保证。中途失败时，已经导入的文件会保留。

**② 经常大批量导入**：

- 源库 `logical_decoding_work_mem` 调到 256MB，减少解码临时文件；
- 配置中 `apply.upsert_group_size` 调到 1000，进一步减少写入目标库的往返次数（改完需要重启 pglsync）。

原理见 [CASE_LARGE_DELETE.md 第 6 节](CASE_LARGE_DELETE.md#6-优化建议及其原理)。

**③ 主键冲突**：COPY 本身不支持 `ON CONFLICT`，源库遇到重复主键会报错并整体回滚，这种情况下什么都不会同步。目标库这边 pglsync 用的是 `INSERT ... ON CONFLICT DO UPDATE`，即使目标库已经有同主键的行，也会被更新成源库的值，不会报错。

---

## 4. 真正同步不过去的写入方式

| 写入方式 | 原因 | 处理 |
|---|---|---|
| 写入 **UNLOGGED 表** | 这种表的数据不写 WAL，本身也不能加入发布 | 需要同步的表不要建成 UNLOGGED |
| 使用 **pg_bulkload 等直接写数据文件的导入工具**（direct 模式） | 绕过了 WAL | 改用 COPY；或者导入后对这张表执行 `resync` |
| TimescaleDB 开启了**写入时直接压缩**之类的选项（较新版本才有，默认关闭） | 数据直接写进压缩 chunk，不产生逐行变更 | 保持默认关闭；如果开启了，导入后执行 `resync` |
| `drop_chunks` / 数据保留策略 | 删除整个 chunk 表，不产生逐行删除 | 在目标库也执行同样的操作 |
| 源库导入时设置了 `session_replication_role = replica` | 只会让触发器不执行，**数据照样会被同步** | 不影响新方案（旧的触发器方案会因此漏数据） |

---

## 5. 动手验证

在源库用 COPY 写几条测试数据，再到目标库查询（列名请按实际表结构调整；表上不允许为空、又没有默认值的列都要写进列清单）：

```sql
-- 源库（131）
COPY tcensemblerealtime (id, datatime, tcid, member, forecast_hour) FROM STDIN WITH (FORMAT csv);
900000001,2026-11-18 10:00:00+08,TEST,1,0
900000002,2026-11-18 10:00:00+08,TEST,2,0
900000003,2026-11-18 10:00:00+08,TEST,3,0
\.
```

```sql
-- 几秒后在目标库（132）查询，应能查到这 3 条
SELECT id, datatime, tcid FROM tcensemblerealtime WHERE id BETWEEN 900000001 AND 900000003;

-- 验证完在源库删除测试数据（删除同样会同步到目标库）
DELETE FROM tcensemblerealtime WHERE id BETWEEN 900000001 AND 900000003;
```

大批量导入完成后，用 `reconcile -t 表名` 核对两边是否一致。
