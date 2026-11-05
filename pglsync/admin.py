# -*- coding: utf-8 -*-
"""status / drop 运维命令。"""
from .state import delete_state, read_state
from .utils import int_to_lsn, qi


def run_status(cfg):
    import psycopg2
    code = 0
    src = psycopg2.connect(**cfg.source)
    src.autocommit = True
    try:
        cur = src.cursor()
        cur.execute("SELECT current_setting('server_version_num')::int")
        ver = cur.fetchone()[0]
        wal_status = ', wal_status' if ver >= 130000 else ", NULL"
        cur.execute("""
            SELECT plugin, active, active_pid, restart_lsn::text, confirmed_flush_lsn::text,
                   pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)),
                   pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)),
                   pg_current_wal_lsn()::text {wal_status}
            FROM pg_replication_slots WHERE slot_name = %s
        """.format(wal_status=wal_status), (cfg.slot_name,))
        row = cur.fetchone()
        print('== 源库复制槽 %s ==' % cfg.slot_name)
        if row is None:
            print('  不存在（尚未 init？）')
            code = 1
        else:
            plugin, active, pid, restart, confirmed, retained, lag, current, wal_st = row
            print('  插件            : %s' % plugin)
            print('  是否活跃        : %s%s' % ('是' if active else '否', ' (pid %s)' % pid if active else ''))
            print('  当前 WAL 位置   : %s' % current)
            print('  已确认位点      : %s  (落后 %s)' % (confirmed, lag))
            print('  保留 WAL        : %s  (restart_lsn %s)' % (retained, restart))
            if wal_st:
                print('  wal_status      : %s' % wal_st)
                if wal_st in ('unreserved', 'lost'):
                    print('  !! WAL 已超出 max_slot_wal_keep_size，复制槽可能已失效，需要重新 init')
                    code = 1
            if not active:
                print('  !! 没有同步进程连接到该复制槽，WAL 会持续堆积')
                code = 1
        cur.execute('SELECT puballtables FROM pg_publication WHERE pubname = %s', (cfg.publication,))
        pub = cur.fetchone()
        print('== 源库发布 %s ==' % cfg.publication)
        print('  %s' % ('不存在' if pub is None else ('FOR ALL TABLES' if pub[0] else '存在')))
    finally:
        src.close()

    tgt = psycopg2.connect(**cfg.target)
    try:
        state = read_state(tgt, cfg.state_schema, cfg.slot_name)
        print('== 目标库同步位点 ==')
        if state is None:
            print('  无')
            code = 1
        else:
            print('  位点 %s，更新于 %s' % (int_to_lsn(state[0]), state[1]))
    finally:
        tgt.close()
    return code


def run_drop(cfg, assume_yes=False, keep_publication=False):
    import psycopg2
    print('将删除源库复制槽 %s%s，并清除目标库中的同步位点。'
          % (cfg.slot_name, '' if keep_publication else ' 和发布 %s' % cfg.publication))
    if not assume_yes and input('输入 yes 继续: ').strip().lower() != 'yes':
        print('已取消')
        return 1
    src = psycopg2.connect(**cfg.source)
    src.autocommit = True
    try:
        cur = src.cursor()
        cur.execute('SELECT active, active_pid FROM pg_replication_slots WHERE slot_name = %s', (cfg.slot_name,))
        row = cur.fetchone()
        if row is not None:
            if row[0]:
                print('复制槽正在被进程 %s 使用，请先停止同步服务' % row[1])
                return 1
            cur.execute('SELECT pg_drop_replication_slot(%s)', (cfg.slot_name,))
            print('已删除复制槽 %s' % cfg.slot_name)
        if not keep_publication:
            cur.execute('DROP PUBLICATION IF EXISTS %s' % qi(cfg.publication))
            print('已删除发布 %s' % cfg.publication)
    finally:
        src.close()
    tgt = psycopg2.connect(**cfg.target)
    try:
        delete_state(tgt, cfg.state_schema, cfg.slot_name)
    finally:
        tgt.close()
    return 0
