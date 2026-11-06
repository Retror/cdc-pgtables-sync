# -*- coding: utf-8 -*-
"""
init：创建复制槽并基于其导出的一致性快照做全量复制。

  1. CREATE_REPLICATION_SLOT ... LOGICAL pgoutput EXPORT_SNAPSHOT
     -> 得到 consistent_point（LSN）和快照名；
  2. 另开源库连接 SET TRANSACTION SNAPSHOT，读取到的数据恰好是 consistent_point 时刻的状态；
  3. 目标库在 **一个事务** 中 TRUNCATE + COPY 全部表，并写入位点 = consistent_point；
  4. 之后 run 从 consistent_point 开始增量同步，全量与增量之间不重不漏。

init --no-copy：目标库已经有数据（例如之前已手工同步过），不想重新全量复制时使用。
  1. 同样创建复制槽、拿到一致性快照；
  2. 用该快照读取源库，与目标库逐表比较（按时间桶的行数 + 整行哈希），
     确认目标库恰好等于 consistent_point 时刻的源库状态；
  3. 一致则直接写入位点 = consistent_point；不一致的表按 --on-mismatch 处理：
       abort（默认）：放弃初始化并删除复制槽，不做任何修改；
       copy        ：只对不一致的表用快照重新 TRUNCATE + COPY；
       ignore      ：保留差异（不推荐），直接从 consistent_point 开始同步。
  比较基于快照，所以初始化期间源库可以照常写入，不需要停业务。
"""
import logging
import os
import threading
import time

from .catalog import resolve_pk, table_columns, table_exists, unique_key_sets
from .config import ConfigError
from .reconcile import SESSION_SETTINGS, bucket_hashes
from .state import clear_table_states, ensure_state_table, read_state, write_state, write_table_state
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


def _preflight(cfg, src_cur, tgt_cur, tables=None):
    """检查表结构，返回 [(TableConfig, 源列列表, 主键)]。"""
    plan = []
    problems = []
    for table in (tables if tables is not None else cfg.tables.values()):
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
        plan.append((table, src_cols, pk))
    if problems:
        raise ConfigError('init 前置检查失败:\n  - ' + '\n  - '.join(problems))
    return plan


def _verify_against_snapshot(snap, tgt, plan, on_mismatch):
    """用快照读取源库，与目标库逐表比较。返回需要重新复制的 plan 子集。

    snap 已处于 SET TRANSACTION SNAPSHOT 之后的事务中；比较结果代表 consistent_point 时刻。
    """
    for conn in (snap, tgt):
        with conn.cursor() as cur:
            for stmt in SESSION_SETTINGS:
                cur.execute(stmt)

    print('\n%-45s %14s %14s  %s' % ('表', '源库(快照)行数', '目标库行数', '结果'))
    print('-' * 95)
    mismatched = []
    for item in plan:
        table, cols, pk = item
        started = time.time()
        s = bucket_hashes(snap, table, cols, pk)
        t = bucket_hashes(tgt, table, cols, pk)
        bad = sorted(b for b in set(s) | set(t) if s.get(b) != t.get(b))
        status = '一致' if not bad else '不一致: %d 个%s' % (len(bad), '时间桶' if table.time_column else '表')
        print('%-45s %14s %14s  %s  (%.1fs)' % (
            table.qualified, '{:,}'.format(sum(v[0] for v in s.values())),
            '{:,}'.format(sum(v[0] for v in t.values())), status, time.time() - started))
        if bad:
            mismatched.append(item)
            if table.time_column:
                for b in bad[:10]:
                    print('    %-28s 源 %10s  目标 %10s' % (
                        b, '{:,}'.format(s.get(b, (0,))[0]), '{:,}'.format(t.get(b, (0,))[0])))
                if len(bad) > 10:
                    print('    ... 另有 %d 个桶不一致' % (len(bad) - 10))
    print('-' * 95)

    if not mismatched:
        log.info('所有表与源库快照一致，直接从一致性位点开始同步')
        return []
    names = ', '.join(t.qualified for t, _, _ in mismatched)
    if on_mismatch == 'abort':
        raise ConfigError('以下表与源库不一致: %s\n'
                          '  未做任何修改。可选择：\n'
                          '  - init --no-copy --on-mismatch copy   只重新复制这些表\n'
                          '  - init（不带 --no-copy）               全部重新复制' % names)
    if on_mismatch == 'copy':
        log.warning('以下表与源库不一致，将用快照重新复制: %s', names)
        return mismatched
    log.warning('以下表与源库不一致，按 --on-mismatch ignore 保留差异: %s', names)
    return []


def run_init(cfg, assume_yes=False, recreate_slot=False, copy=True, verify=True, on_mismatch='abort'):
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

        if copy:
            print('\n即将对目标库 %s@%s 执行全量初始化，以下表会被 TRUNCATE 后重新复制:'
                  % (cfg.target.get('dbname'), cfg.target.get('host')))
        else:
            print('\n即将以目标库 %s@%s 的现有数据为起点初始化（不重新全量复制）。'
                  % (cfg.target.get('dbname'), cfg.target.get('host')))
            if verify:
                print('会先用一致性快照逐表核对源库与目标库，不一致时的处理方式: %s' % {
                    'abort': '放弃初始化，不做任何修改',
                    'copy': '仅对不一致的表 TRUNCATE 后重新复制',
                    'ignore': '保留差异，直接开始同步（不推荐）'}[on_mismatch])
            else:
                print('已指定 --skip-verify：不核对数据，请自行确保两边此刻完全一致！')
            print('涉及的表:')
        for table, _, _ in plan:
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

        # 注意：repl 连接在复制/核对完成前必须保持打开且不执行其他命令，否则导出的快照失效
        snap = psycopg2.connect(**cfg.source)
        snap.set_session(isolation_level=psycopg2.extensions.ISOLATION_LEVEL_REPEATABLE_READ, readonly=True)
        with snap.cursor() as cur:
            cur.execute('SET TRANSACTION SNAPSHOT %s', (snapshot_name,))

        total_start = time.time()
        if copy:
            to_copy = plan
        elif verify:
            to_copy = _verify_against_snapshot(snap, tgt, plan, on_mismatch)
        else:
            to_copy = []

        copied = []
        with tgt.cursor() as tcur:
            for table, cols, _ in to_copy:
                started = time.time()
                tcur.execute('TRUNCATE TABLE %s' % qname(table.schema, table.name))
                rows = _copy_table(snap, tcur, table, cols)
                copied.append(table)
                log.info('已复制 %-45s %12s 行  %.1fs', table.qualified,
                         '{:,}'.format(rows) if rows is not None and rows >= 0 else '?', time.time() - started)
            write_state(tcur, cfg.state_schema, cfg.slot_name, start_lsn)
            clear_table_states(tcur, cfg.state_schema, cfg.slot_name)
        tgt.commit()
        snap.commit()
        log.info('初始化完成，耗时 %.1fs，重新复制 %d 张表，同步位点已设为 %s',
                 time.time() - total_start, len(copied), int_to_lsn(start_lsn))

        if copied:
            tgt.autocommit = True
            with tgt.cursor() as tcur:
                for table in copied:
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


# ---------------------------------------------------------------------------
# resync：在不重建主复制槽的情况下，重新全量复制指定的表
# ---------------------------------------------------------------------------

def _resolve_tables(cfg, names):
    tables, unknown = [], []
    for name in names:
        key = name if '.' in name else 'public.%s' % name
        table = cfg.tables.get(key)
        if table is None:
            unknown.append(name)
        elif table not in tables:
            tables.append(table)
    if unknown:
        raise ConfigError('表 %s 不在配置文件的 tables 中，请先把它加入配置' % ', '.join(unknown))
    return tables


def run_resync(cfg, names, assume_yes=False):
    """
    重新全量复制指定的表（新增同步表、表结构变更、单表数据不一致时使用）。

      1. 要求主复制槽存在且没有进程在使用（run 已停止）；
      2. 创建一个 **临时** 复制槽并导出快照，得到位点 X（连接关闭后临时槽自动删除）；
      3. 目标库一个事务内：TRUNCATE + 用快照 COPY 这些表，记录 table_state(表, X)；
      4. 重新启动 run 后，主复制槽从原位点继续；这些表在 X 之前提交的变更被跳过
         （已包含在快照数据中），X 之后的变更正常应用。其他表不受影响。
    """
    import psycopg2
    import psycopg2.extensions
    from psycopg2.extras import LogicalReplicationConnection

    tables = _resolve_tables(cfg, names)
    src = psycopg2.connect(**cfg.source)
    src.autocommit = True
    tgt = psycopg2.connect(**cfg.target)
    repl = snap = None
    try:
        with src.cursor() as cur:
            cur.execute('SELECT active, active_pid FROM pg_replication_slots WHERE slot_name = %s',
                        (cfg.slot_name,))
            row = cur.fetchone()
            if row is None:
                raise ConfigError('复制槽 %s 不存在。首次部署请使用 init' % cfg.slot_name)
            if row[0]:
                raise ConfigError('复制槽 %s 正被进程 %s 使用，请先停止增量同步（run）再执行 resync'
                                  % (cfg.slot_name, row[1]))
            missing = []
            for t in tables:
                cur.execute('SELECT 1 FROM pg_publication_tables WHERE pubname = %s '
                            'AND schemaname = %s AND tablename = %s', (cfg.publication, t.schema, t.name))
                if cur.fetchone() is None:
                    missing.append(t.qualified)
            if missing:
                raise ConfigError('表 %s 不在发布 %s 中，请先执行 setup' % (', '.join(missing), cfg.publication))

        ensure_state_table(tgt, cfg.state_schema)
        if read_state(tgt, cfg.state_schema, cfg.slot_name) is None:
            raise ConfigError('目标库没有复制槽 %s 的同步位点，请先执行 init' % cfg.slot_name)
        with src.cursor() as scur, tgt.cursor() as tcur:
            plan = _preflight(cfg, scur, tcur, tables)
        tgt.commit()

        print('\n即将重新全量复制以下表（目标库中这些表会被 TRUNCATE 后用源库快照重新复制，其他表不受影响）:')
        for table, _, _ in plan:
            print('  - %s' % table.qualified)
        if not assume_yes:
            if input('\n输入 yes 继续: ').strip().lower() != 'yes':
                print('已取消')
                return 1

        temp_slot = ('%s_rs_%d' % (cfg.slot_name[:40], os.getpid()))
        repl = psycopg2.connect(connection_factory=LogicalReplicationConnection, **cfg.source)
        rcur = repl.cursor()
        rcur.execute('CREATE_REPLICATION_SLOT %s TEMPORARY LOGICAL pgoutput EXPORT_SNAPSHOT' % temp_slot)
        _, consistent_point, snapshot_name, _ = rcur.fetchone()
        lsn = lsn_to_int(consistent_point)
        log.info('已创建临时复制槽 %s，快照位点 %s', temp_slot, consistent_point)

        snap = psycopg2.connect(**cfg.source)
        snap.set_session(isolation_level=psycopg2.extensions.ISOLATION_LEVEL_REPEATABLE_READ, readonly=True)
        with snap.cursor() as cur:
            cur.execute('SET TRANSACTION SNAPSHOT %s', (snapshot_name,))

        started_all = time.time()
        with tgt.cursor() as tcur:
            for table, cols, _ in plan:
                started = time.time()
                tcur.execute('TRUNCATE TABLE %s' % qname(table.schema, table.name))
                rows = _copy_table(snap, tcur, table, cols)
                write_table_state(tcur, cfg.state_schema, cfg.slot_name, table.qualified, lsn)
                log.info('已复制 %-45s %12s 行  %.1fs', table.qualified,
                         '{:,}'.format(rows) if rows is not None and rows >= 0 else '?', time.time() - started)
        tgt.commit()
        snap.commit()
        log.info('resync 完成，耗时 %.1fs，这些表的起始位点为 %s', time.time() - started_all, int_to_lsn(lsn))

        tgt.autocommit = True
        with tgt.cursor() as tcur:
            for table, _, _ in plan:
                tcur.execute('ANALYZE %s' % qname(table.schema, table.name))
        print('\nresync 完成。请重新启动增量同步: python -m pglsync -c %s run' % (cfg.path or 'config.json'))
        return 0
    except BaseException:
        try:
            tgt.rollback()
        except Exception:
            pass
        raise
    finally:
        # 关闭 repl 连接后，临时复制槽由 PostgreSQL 自动删除
        for conn in (snap, repl, tgt, src):
            if conn is not None and not conn.closed:
                conn.close()
