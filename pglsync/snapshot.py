# -*- coding: utf-8 -*-
"""
init：创建复制槽并基于其导出的一致性快照做全量复制。

  1. CREATE_REPLICATION_SLOT ... LOGICAL pgoutput EXPORT_SNAPSHOT
     -> 得到 consistent_point（LSN）和快照名；
  2. 另开源库连接 SET TRANSACTION SNAPSHOT，读取到的数据恰好是 consistent_point 时刻的状态；
  3. 目标库在 **一个事务** 中 TRUNCATE + COPY 全部表，并写入位点 = consistent_point；
  4. 之后 run 从 consistent_point 开始增量同步，全量与增量之间不重不漏。
"""
import logging
import os
import threading
import time

from .catalog import resolve_pk, table_columns, table_exists, unique_key_sets
from .config import ConfigError
from .state import ensure_state_table, write_state
from .utils import int_to_lsn, lsn_to_int, qi, qname

log = logging.getLogger('pglsync.init')


def _copy_table(src_conn, tgt_cur, table, cols):
    """源库 COPY TO STDOUT -> 管道 -> 目标库 COPY FROM STDIN，流式传输不落盘。"""
    col_list = ', '.join(qi(c) for c in cols)
    out_sql = 'COPY (SELECT %s FROM %s) TO STDOUT' % (col_list, qname(table.schema, table.name))
    in_sql = 'COPY %s (%s) FROM STDIN' % (qname(table.schema, table.name), col_list)

    read_fd, write_fd = os.pipe()
    errors = []

    def produce():
        try:
            with os.fdopen(write_fd, 'wb') as writer:
                with src_conn.cursor() as cur:
                    cur.copy_expert(out_sql, writer, size=1 << 20)
        except BaseException as e:   # noqa
            errors.append(e)

    thread = threading.Thread(target=produce, name='copy-%s' % table.name)
    thread.daemon = True
    thread.start()
    try:
        with os.fdopen(read_fd, 'rb') as reader:
            tgt_cur.copy_expert(in_sql, reader, size=1 << 20)
    finally:
        thread.join()
    if errors:
        raise errors[0]
    return tgt_cur.rowcount


def _slot_exists(cur, slot):
    cur.execute('SELECT active FROM pg_replication_slots WHERE slot_name = %s', (slot,))
    row = cur.fetchone()
    return None if row is None else bool(row[0])


def _drop_slot(src_params, slot):
    import psycopg2
    conn = psycopg2.connect(**src_params)
    conn.autocommit = True
    try:
        with conn.cursor() as cur:
            cur.execute('SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots '
                        'WHERE slot_name = %s', (slot,))
    finally:
        conn.close()


def _preflight(cfg, src_cur, tgt_cur):
    """检查表结构，返回 [(TableConfig, 源列列表)]。"""
    plan = []
    problems = []
    for table in cfg.tables.values():
        if not table_exists(src_cur, table.schema, table.name):
            problems.append('源库不存在表 %s' % table.qualified)
            continue
        if not table_exists(tgt_cur, table.schema, table.name):
            problems.append('目标库不存在表 %s（请先用 pg_dump --schema-only 建表）' % table.qualified)
            continue
        src_cols = table_columns(src_cur, table.schema, table.name)
        tgt_cols = table_columns(tgt_cur, table.schema, table.name)
        missing = [c for c in src_cols if c not in tgt_cols]
        if missing:
            problems.append('目标表 %s 缺少列: %s' % (table.qualified, ', '.join(missing)))
        extra = [c for c in tgt_cols if c not in src_cols]
        if extra:
            log.warning('目标表 %s 多出列 %s（将保持默认值）', table.qualified, ', '.join(extra))
        try:
            pk = resolve_pk(src_cur, table)
        except ConfigError as e:
            problems.append(str(e))
            continue
        if frozenset(pk) not in unique_key_sets(tgt_cur, table.schema, table.name):
            problems.append('目标表 %s 上没有与主键 %s 完全一致的唯一约束（ON CONFLICT 需要）'
                            % (table.qualified, pk))
        plan.append((table, src_cols))
    if problems:
        raise ConfigError('init 前置检查失败:\n  - ' + '\n  - '.join(problems))
    return plan


def run_init(cfg, assume_yes=False, recreate_slot=False):
    import psycopg2
    import psycopg2.extensions
    from psycopg2.extras import LogicalReplicationConnection

    src = psycopg2.connect(**cfg.source)
    src.autocommit = True
    tgt = psycopg2.connect(**cfg.target)
    repl = snap = None
    slot_created = False
    try:
        with src.cursor() as cur:
            cur.execute('SELECT 1 FROM pg_publication WHERE pubname = %s', (cfg.publication,))
            if cur.fetchone() is None:
                raise ConfigError('源库中不存在发布 %s，请先执行 setup' % cfg.publication)
            active = _slot_exists(cur, cfg.slot_name)
            if active is not None:
                if not recreate_slot:
                    raise ConfigError('复制槽 %s 已存在。如需重新全量初始化，请加 --recreate-slot' % cfg.slot_name)
                if active:
                    raise ConfigError('复制槽 %s 正在被使用，请先停止同步进程' % cfg.slot_name)

        ensure_state_table(tgt, cfg.state_schema)
        with src.cursor() as scur, tgt.cursor() as tcur:
            plan = _preflight(cfg, scur, tcur)
        tgt.commit()

        print('\n即将对目标库 %s@%s 执行全量初始化，以下表会被 TRUNCATE 后重新复制:'
              % (cfg.target.get('dbname'), cfg.target.get('host')))
        for table, _ in plan:
            print('  - %s' % table.qualified)
        if not assume_yes:
            answer = input('\n输入 yes 继续: ').strip().lower()
            if answer != 'yes':
                print('已取消')
                return 1

        if recreate_slot:
            log.info('删除已有复制槽 %s', cfg.slot_name)
            _drop_slot(cfg.source, cfg.slot_name)

        repl = psycopg2.connect(connection_factory=LogicalReplicationConnection, **cfg.source)
        rcur = repl.cursor()
        rcur.execute('CREATE_REPLICATION_SLOT %s LOGICAL pgoutput EXPORT_SNAPSHOT' % cfg.slot_name)
        slot_created = True
        _, consistent_point, snapshot_name, _ = rcur.fetchone()
        start_lsn = lsn_to_int(consistent_point)
        log.info('已创建复制槽 %s，一致性位点 %s，快照 %s', cfg.slot_name, consistent_point, snapshot_name)

        # 注意：repl 连接在复制完成前必须保持打开且不执行其他命令，否则导出的快照失效
        snap = psycopg2.connect(**cfg.source)
        snap.set_session(isolation_level=psycopg2.extensions.ISOLATION_LEVEL_REPEATABLE_READ, readonly=True)
        with snap.cursor() as cur:
            cur.execute('SET TRANSACTION SNAPSHOT %s', (snapshot_name,))

        total_start = time.time()
        with tgt.cursor() as tcur:
            for table, cols in plan:
                started = time.time()
                tcur.execute('TRUNCATE TABLE %s' % qname(table.schema, table.name))
                rows = _copy_table(snap, tcur, table, cols)
                log.info('已复制 %-45s %12s 行  %.1fs', table.qualified,
                         '{:,}'.format(rows) if rows is not None and rows >= 0 else '?', time.time() - started)
            write_state(tcur, cfg.state_schema, cfg.slot_name, start_lsn)
        tgt.commit()
        snap.commit()
        log.info('全量复制完成，耗时 %.1fs，同步位点已设为 %s', time.time() - total_start, int_to_lsn(start_lsn))

        tgt.autocommit = True
        with tgt.cursor() as tcur:
            for table, _ in plan:
                tcur.execute('ANALYZE %s' % qname(table.schema, table.name))
        print('\n初始化完成。接下来请启动增量同步: python -m pglsync -c %s run' % (cfg.path or 'config.json'))
        return 0
    except BaseException:
        try:
            tgt.rollback()
        except Exception:
            pass
        if slot_created:
            log.error('初始化失败，删除刚创建的复制槽 %s（避免其无限保留 WAL）', cfg.slot_name)
            for conn in (snap, repl):
                if conn is not None and not conn.closed:
                    conn.close()
            try:
                _drop_slot(cfg.source, cfg.slot_name)
            except Exception:
                log.exception('删除复制槽失败，请手动执行: SELECT pg_drop_replication_slot(%r);', cfg.slot_name)
        raise
    finally:
        for conn in (snap, repl, tgt, src):
            if conn is not None and not conn.closed:
                conn.close()
