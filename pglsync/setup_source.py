# -*- coding: utf-8 -*-
"""
check / setup：检查源库逻辑复制前置条件，并创建发布（publication）。

TimescaleDB 说明：
  超表的数据实际存储在 chunk（_timescaledb_internal._hyper_x_y_chunk）中，新 chunk 随时会被自动创建。
  要让新 chunk 的变更也能被捕获，发布必须覆盖 chunk 所在的 schema：
    * PG15+：FOR TABLE <同步表...>, TABLES IN SCHEMA _timescaledb_internal   （推荐）
    * PG10~14：只能 FOR ALL TABLES
  被发布的表如果没有复制标识（主键），源库对它执行 UPDATE/DELETE 会直接报错！
  因此创建发布前必须检查覆盖范围内所有表的复制标识。
"""
import logging

from .catalog import has_timescaledb, table_exists
from .utils import qi, qname

log = logging.getLogger('pglsync.setup')

# 注意：这里的 SQL 会作为 psycopg2 查询模板使用，避免出现字面量 '%'
_SYSTEM_SCHEMA_FILTER = ("n.nspname NOT IN ('pg_catalog', 'information_schema') "
                         "AND left(n.nspname, 8) <> 'pg_toast' AND left(n.nspname, 7) <> 'pg_temp'")


def _setting(cur, name):
    cur.execute('SELECT setting FROM pg_settings WHERE name = %s', (name,))
    row = cur.fetchone()
    return row[0] if row else None


def _existing_schemas(cur, names):
    if not names:
        return []
    cur.execute('SELECT nspname FROM pg_namespace WHERE nspname = ANY(%s)', (list(names),))
    found = set(r[0] for r in cur.fetchall())
    return [n for n in names if n in found]


def check_server(cur, cfg):
    """返回 (版本号, 致命问题列表, 警告列表)"""
    problems, warnings = [], []
    ver = int(_setting(cur, 'server_version_num'))
    if ver < 100000:
        problems.append('PostgreSQL 版本过低（需要 10+ 才有 pgoutput）')

    wal_level = _setting(cur, 'wal_level')
    if wal_level != 'logical':
        problems.append("wal_level = %s，需要改为 logical：ALTER SYSTEM SET wal_level = 'logical'; "
                        "然后重启 PostgreSQL" % wal_level)

    cur.execute('SELECT count(*) FROM pg_replication_slots')
    used_slots = cur.fetchone()[0]
    cur.execute('SELECT 1 FROM pg_replication_slots WHERE slot_name = %s', (cfg.slot_name,))
    own_slot = cur.fetchone() is not None
    max_slots = int(_setting(cur, 'max_replication_slots'))
    if not own_slot and used_slots >= max_slots:
        problems.append('max_replication_slots = %d 已用完（已有 %d 个槽），请调大并重启' % (max_slots, used_slots))
    max_senders = int(_setting(cur, 'max_wal_senders'))
    if max_senders < 1:
        problems.append('max_wal_senders = %d，至少需要 1（建议 10），修改后重启' % max_senders)

    if ver >= 130000:
        keep = _setting(cur, 'max_slot_wal_keep_size')
        if keep == '-1':
            warnings.append("max_slot_wal_keep_size = -1（不限制）。同步长时间中断时复制槽会无限保留 WAL，"
                            "可能撑满磁盘。建议按磁盘容量设置上限，例如: "
                            "ALTER SYSTEM SET max_slot_wal_keep_size = '50GB'; SELECT pg_reload_conf();")
    else:
        warnings.append('PG < 13 没有 max_slot_wal_keep_size，必须监控复制槽积压（status 命令）')

    cur.execute('SELECT rolsuper, rolreplication FROM pg_roles WHERE rolname = current_user')
    rolsuper, rolrepl = cur.fetchone()
    if not (rolsuper or rolrepl):
        problems.append('当前用户既不是超级用户也没有 REPLICATION 权限：ALTER ROLE <user> REPLICATION;')
    return ver, problems, warnings


def effective_mode(cfg, ver, chunk_schemas):
    if not chunk_schemas:
        return 'tables'
    mode = cfg.publication_mode
    if mode == 'auto':
        mode = 'schema' if ver >= 150000 else 'all'
    return mode


def find_bad_replica_identity(cur, cfg, mode, chunk_schemas):
    """返回发布覆盖范围内、UPDATE/DELETE 会因缺少复制标识而报错的表 [(schema, name, replident)]。"""
    explicit = [t.qualified for t in cfg.tables.values()]
    if mode == 'all':
        where = _SYSTEM_SCHEMA_FILTER
        params = []
    else:
        where = "(n.nspname || '.' || c.relname) = ANY(%s)"
        params = [explicit]
        if mode == 'schema':
            where = '(%s OR n.nspname = ANY(%%s))' % where
            params.append(list(chunk_schemas))
    # compress_hyper_* 是 TimescaleDB 内部压缩表，只被扩展内部的堆接口写入，不经过复制标识检查
    sql = """
        SELECT n.nspname, c.relname, c.relreplident,
               EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.oid AND i.indisprimary),
               EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.oid AND i.indisreplident)
        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relkind IN ('r', 'p') AND c.relpersistence = 'p'
          AND left(c.relname, 15) <> 'compress_hyper_'
          AND {where}
        ORDER BY 1, 2
    """.format(where=where)
    cur.execute(sql, params)
    bad = []
    for schema, name, ident, has_pk, has_ri_idx in cur.fetchall():
        if ident == 'n' or (ident == 'd' and not has_pk) or (ident == 'i' and not has_ri_idx):
            bad.append((schema, name, ident))
    return bad


def _hypertable_root(cur, schema, name):
    # chunk 是超表的继承子表；用 pg_inherits 查找，不依赖 TimescaleDB 内部目录的列名
    cur.execute("""
        SELECT pn.nspname, p.relname
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_inherits i ON i.inhrelid = c.oid
        JOIN pg_class p ON p.oid = i.inhparent
        JOIN pg_namespace pn ON pn.oid = p.relnamespace
        WHERE n.nspname = %s AND c.relname = %s
    """, (schema, name))
    row = cur.fetchone()
    return (row[0], row[1]) if row else None


def fix_replica_identity(cur, bad, chunk_schemas):
    """设置 REPLICA IDENTITY FULL。chunk 同时设置其超表（TimescaleDB 会传播给新 chunk）。"""
    roots = []
    for schema, name, _ in bad:
        if schema in chunk_schemas:
            root = _hypertable_root(cur, schema, name)
            if root and root not in roots:
                roots.append(root)
    for schema, name in roots:
        print('  ALTER TABLE %s REPLICA IDENTITY FULL  (超表)' % qname(schema, name))
        cur.execute('ALTER TABLE %s REPLICA IDENTITY FULL' % qname(schema, name))
    for schema, name, _ in bad:
        print('  ALTER TABLE %s REPLICA IDENTITY FULL' % qname(schema, name))
        cur.execute('ALTER TABLE %s REPLICA IDENTITY FULL' % qname(schema, name))


def _timescale_warnings(cur, cfg):
    warnings = []
    synced = set(cfg.tables)
    try:
        cur.execute("SELECT hypertable_schema || '.' || hypertable_name FROM timescaledb_information.hypertables "
                    "WHERE compression_enabled")
        compressed = sorted(r[0] for r in cur.fetchall() if r[0] in synced)
        if compressed:
            warnings.append('超表 %s 启用了压缩。压缩/解压过程会在 WAL 中产生 chunk 行的删除和重新插入，'
                            '请在测试环境验证后再上线，并定期执行 reconcile 核对已压缩时间段。' % ', '.join(compressed))
        cur.execute("SELECT DISTINCT hypertable_schema || '.' || hypertable_name FROM timescaledb_information.jobs "
                    "WHERE proc_name = 'policy_retention'")
        retention = sorted(r[0] for r in cur.fetchall() if r[0] in synced)
        if retention:
            warnings.append('超表 %s 配置了数据保留策略。drop_chunks 删除整个 chunk 不会产生逐行变更，'
                            '目标库不会同步删除，请在目标库配置相同的保留策略。' % ', '.join(retention))
    except Exception as e:  # 视图在旧版本中可能不存在
        warnings.append('无法读取 TimescaleDB 策略信息: %s' % e)
    return warnings


def _publication_status(cur, cfg, ver):
    cur.execute('SELECT oid, puballtables FROM pg_publication WHERE pubname = %s', (cfg.publication,))
    row = cur.fetchone()
    if row is None:
        return None
    oid, all_tables = row
    cur.execute('SELECT schemaname || \'.\' || tablename FROM pg_publication_tables WHERE pubname = %s',
                (cfg.publication,))
    tables = set(r[0] for r in cur.fetchall())
    schemas = set()
    if ver >= 150000:
        cur.execute('SELECT n.nspname FROM pg_publication_namespace pn '
                    'JOIN pg_namespace n ON n.oid = pn.pnnspid WHERE pn.pnpubid = %s', (oid,))
        schemas = set(r[0] for r in cur.fetchall())
    return {'all_tables': all_tables, 'tables': tables, 'schemas': schemas}


def run_setup(cfg, apply=False, fix_ri=False, force=False):
    """apply=False 时只检查（check 命令）。返回进程退出码。"""
    import psycopg2
    conn = psycopg2.connect(**cfg.source)
    conn.autocommit = True
    ok = True
    try:
        cur = conn.cursor()
        ver, problems, warnings = check_server(cur, cfg)
        print('源库 PostgreSQL 版本号: %d' % ver)

        missing = [t.qualified for t in cfg.tables.values() if not table_exists(cur, t.schema, t.name)]
        if missing:
            problems.append('源库中不存在表: %s' % ', '.join(missing))

        timescale = has_timescaledb(cur)
        chunk_schemas = _existing_schemas(cur, cfg.chunk_schemas) if timescale else []
        mode = effective_mode(cfg, ver, chunk_schemas)
        if mode == 'schema' and ver < 150000:
            problems.append('publication_mode=schema 需要 PostgreSQL 15+，当前版本请使用 all')
        if timescale:
            warnings.extend(_timescale_warnings(cur, cfg))
        print('TimescaleDB: %s，发布模式: %s' % ('已安装' if timescale else '未安装', mode))
        if mode == 'all':
            warnings.append('FOR ALL TABLES 会发布库内所有表的变更（客户端过滤），解码开销较大，'
                            '且库内所有表都必须有复制标识。')

        bad = find_bad_replica_identity(cur, cfg, mode, chunk_schemas)
        if bad:
            print('\n以下表没有可用的复制标识（加入发布后，源库对它们的 UPDATE/DELETE 会报错！）:')
            for schema, name, ident in bad:
                print('  - %s (replident=%s)' % (qname(schema, name), ident))
            if apply and fix_ri:
                print('\n设置 REPLICA IDENTITY FULL（更推荐为这些表添加主键）:')
                fix_replica_identity(cur, bad, chunk_schemas)
                bad = find_bad_replica_identity(cur, cfg, mode, chunk_schemas)
                if bad:
                    problems.append('仍有表缺少复制标识: %s' % ', '.join('%s.%s' % b[:2] for b in bad))
            elif not force:
                problems.append('存在缺少复制标识的表。请先为其添加主键，或执行 setup --fix-replica-identity'
                                '（设置 REPLICA IDENTITY FULL），或确认这些表从不 UPDATE/DELETE 后使用 --force')

        pub = _publication_status(cur, cfg, ver)
        actions = []
        if pub is None:
            if mode == 'all':
                actions.append('CREATE PUBLICATION %s FOR ALL TABLES' % qi(cfg.publication))
            else:
                target = 'TABLE ' + ', '.join(qname(t.schema, t.name) for t in cfg.tables.values())
                if mode == 'schema':
                    target += ', TABLES IN SCHEMA ' + ', '.join(qi(s) for s in chunk_schemas)
                actions.append('CREATE PUBLICATION %s FOR %s' % (qi(cfg.publication), target))
        elif not pub['all_tables']:
            if mode == 'all':
                problems.append('已存在的发布 %s 不是 FOR ALL TABLES，请手动删除后重新 setup' % cfg.publication)
            else:
                lacking = [t for t in cfg.tables.values() if t.qualified not in pub['tables']]
                if lacking:
                    actions.append('ALTER PUBLICATION %s ADD TABLE %s' % (
                        qi(cfg.publication), ', '.join(qname(t.schema, t.name) for t in lacking)))
                if mode == 'schema':
                    lacking_s = [s for s in chunk_schemas if s not in pub['schemas']]
                    if lacking_s:
                        actions.append('ALTER PUBLICATION %s ADD TABLES IN SCHEMA %s' % (
                            qi(cfg.publication), ', '.join(qi(s) for s in lacking_s)))

        if warnings:
            print('\n警告:')
            for w in warnings:
                print('  ! ' + w)
        if problems:
            ok = False
            print('\n问题（必须解决）:')
            for p in problems:
                print('  x ' + p)

        if actions:
            print('\n需要执行的发布变更:')
            for a in actions:
                print('  ' + a + ';')
            if apply and ok:
                for a in actions:
                    cur.execute(a)
                print('已执行。')
            elif apply:
                print('存在未解决的问题，未执行任何发布变更。')
        else:
            print('\n发布 %s 已就绪。' % cfg.publication)

        if ok:
            print('\n检查通过。' + ('' if apply else '执行 setup 创建发布，然后执行 init。'))
        return 0 if ok else 1
    finally:
        conn.close()
