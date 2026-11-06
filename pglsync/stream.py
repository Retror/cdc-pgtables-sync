# -*- coding: utf-8 -*-
"""
增量同步主循环：从复制槽读取 pgoutput 流，按源库提交顺序应用到目标库。

位点语义（保证不丢、不重复生效）：
  * 目标库 pglsync.sync_state.lsn 保存“最后一个已应用事务的 end_lsn”，与数据同事务提交；
  * 目标库提交成功后才向源库确认（flush_lsn），源库才会回收这之前的 WAL；
  * 重启后从目标库保存的位点继续；源库可能重发少量已应用的事务，
    通过 Begin.final_lsn < 已应用位点 识别并跳过（即使不跳过，幂等写入也能保证结果正确）。
"""
import logging
import select
import time

from . import pgoutput
from .applier import Applier
from .catalog import RelationRegistry
from .config import ConfigError
from .state import ensure_state_table, read_state, read_table_states
from .utils import int_to_lsn, pg_timestamp_to_datetime

log = logging.getLogger('pglsync.stream')


def _close_quietly(conn, rollback=False):
    if conn is None or conn.closed:
        return
    try:
        if rollback:
            conn.rollback()
    except Exception:
        pass
    try:
        conn.close()
    except Exception:
        pass


class Streamer(object):
    def __init__(self, cfg):
        self.cfg = cfg
        self._stop = False

    def request_stop(self, *_args):
        if not self._stop:
            log.info('收到停止信号，处理完当前事务后退出...')
        self._stop = True

    def should_stop(self):
        return self._stop

    def run_forever(self):
        delay = self.cfg.retry_initial
        while not self._stop:
            started = time.time()
            try:
                self._run_once()
            except ConfigError:
                raise
            except Exception:
                log.exception('同步中断')
            if self._stop:
                break
            if time.time() - started > 300:
                delay = self.cfg.retry_initial
            log.info('%.0f 秒后重新连接...', delay)
            self._sleep(delay)
            delay = min(delay * 2, self.cfg.retry_max)
        log.info('同步进程已退出')

    def _sleep(self, seconds):
        end = time.time() + seconds
        while not self._stop:
            remaining = end - time.time()
            if remaining <= 0:
                break
            time.sleep(min(0.5, remaining))

    def _check_source(self):
        import psycopg2
        conn = psycopg2.connect(**self.cfg.source)
        try:
            with conn.cursor() as cur:
                cur.execute('SELECT plugin, active, active_pid FROM pg_replication_slots '
                            'WHERE slot_name = %s AND database = current_database()', (self.cfg.slot_name,))
                row = cur.fetchone()
                if row is None:
                    raise ConfigError('源库中不存在复制槽 %s，请先执行 init' % self.cfg.slot_name)
                if row[0] != 'pgoutput':
                    raise ConfigError('复制槽 %s 的插件是 %s，需要 pgoutput' % (self.cfg.slot_name, row[0]))
                if row[1]:
                    log.warning('复制槽 %s 正被进程 %s 占用（是否有另一个同步进程在运行？）',
                                self.cfg.slot_name, row[2])
                cur.execute('SELECT 1 FROM pg_publication WHERE pubname = %s', (self.cfg.publication,))
                if cur.fetchone() is None:
                    raise ConfigError('源库中不存在发布 %s，请先执行 setup' % self.cfg.publication)
        finally:
            conn.close()

    def _run_once(self):
        import psycopg2
        from psycopg2.extras import LogicalReplicationConnection

        cfg = self.cfg
        tgt = repl = None
        registry = RelationRegistry(cfg)
        try:
            tgt = psycopg2.connect(**cfg.target)
            ensure_state_table(tgt, cfg.state_schema)
            state = read_state(tgt, cfg.state_schema, cfg.slot_name)
            if state is None:
                raise ConfigError('目标库 %s.sync_state 中没有复制槽 %s 的同步位点，请先执行 init'
                                  % (cfg.state_schema, cfg.slot_name))
            start_lsn = state[0]
            table_lsns = read_table_states(tgt, cfg.state_schema, cfg.slot_name)
            for name, lsn in sorted(table_lsns.items()):
                if lsn > start_lsn:
                    log.info('表 %s 做过 resync，位点 %s 之前的变更将被跳过', name, int_to_lsn(lsn))
            self._check_source()

            applier = Applier(cfg, tgt)
            repl = psycopg2.connect(connection_factory=LogicalReplicationConnection, **cfg.source)
            cur = repl.cursor()
            cur.start_replication(
                slot_name=cfg.slot_name,
                decode=False,
                start_lsn=start_lsn,
                status_interval=cfg.status_interval,
                options={'proto_version': '1', 'publication_names': cfg.publication},
            )
            log.info('开始增量同步: slot=%s publication=%s 起始位点=%s',
                     cfg.slot_name, cfg.publication, int_to_lsn(start_lsn))
            _Session(cfg, cur, applier, registry, start_lsn, table_lsns).loop(self.should_stop)
        finally:
            _close_quietly(repl)
            _close_quietly(tgt, rollback=True)
            registry.close()


class _Session(object):
    """一次复制连接的生命周期。"""

    def __init__(self, cfg, cur, applier, registry, start_lsn, table_lsns=None):
        self.cfg = cfg
        self.cur = cur
        self.applier = applier
        self.registry = registry
        self.table_lsns = dict(table_lsns or {})   # resync 过的表 -> 快照位点
        self.txn_lsn = 0                # 当前事务的提交位点（Begin.final_lsn）
        self.last_end = start_lsn       # 已处理（可能尚未提交到目标库）的最后一个事务 end_lsn
        self.flushed = start_lsn        # 已确认给源库的位点
        self.in_txn = False
        self.skip_txn = False
        self.pending_end = None         # 已处理但未提交的批次的 end_lsn
        self.batch_started = None
        self.batch_changes = 0
        self.last_commit_ts = None
        now = time.time()
        self.last_idle_feedback = now
        self.last_stats = now
        self.stats = {'txns': 0, 'changes': 0, 'skipped_txns': 0, 'skipped_table_changes': 0}

    # ------------------------------------------------------------ 主循环
    def loop(self, should_stop):
        cur = self.cur
        while True:
            if should_stop() and not self.in_txn:
                break
            msg = cur.read_message()
            if msg is not None:
                self.handle(msg.payload)
                continue

            now = time.time()
            if (self.pending_end is not None and not self.in_txn
                    and now - self.batch_started >= self.cfg.batch_max_seconds):
                self.flush()
            if self.pending_end is None and not self.in_txn:
                self._idle_feedback(now)
            self._maybe_log_stats(now)

            timeout = 1.0
            if self.pending_end is not None:
                timeout = max(0.0, min(timeout, self.batch_started + self.cfg.batch_max_seconds - now))
            select.select([cur], [], [], timeout)

        if self.pending_end is not None:
            self.flush()

    def handle(self, payload):
        m = pgoutput.decode(payload)
        kind = type(m)

        if kind is pgoutput.Relation:
            self.registry.register(m)
        elif kind is pgoutput.Begin:
            if self.in_txn:
                raise pgoutput.DecodeError('收到嵌套的 BEGIN')
            self.in_txn = True
            self.txn_lsn = m.final_lsn
            self.skip_txn = m.final_lsn < self.last_end
            if self.skip_txn:
                self.stats['skipped_txns'] += 1
                log.debug('跳过已应用的事务 xid=%d commit_lsn=%s', m.xid, int_to_lsn(m.final_lsn))
            elif self.batch_started is None:
                self.batch_started = time.time()
        elif kind is pgoutput.Commit:
            if not self.in_txn:
                raise pgoutput.DecodeError('收到没有 BEGIN 的 COMMIT')
            self.in_txn = False
            if self.skip_txn:
                self.skip_txn = False
                return
            self.last_end = m.end_lsn
            self.pending_end = m.end_lsn
            self.last_commit_ts = m.commit_ts
            self.stats['txns'] += 1
            if (self.batch_changes >= self.cfg.batch_max_changes
                    or time.time() - self.batch_started >= self.cfg.batch_max_seconds):
                self.flush()
        elif kind in (pgoutput.Insert, pgoutput.Update, pgoutput.Delete, pgoutput.Truncate):
            if not self.in_txn:
                raise pgoutput.DecodeError('收到事务之外的数据变更消息')
            if self.skip_txn:
                return
            n = self._apply(m)
            self.batch_changes += n
            self.stats['changes'] += n
        # Origin / Type / LogicalMessage：忽略

    def _wanted(self, info):
        """该 relation 的变更是否需要应用。"""
        if info.target is None:
            return False
        table_lsn = self.table_lsns.get(info.target.qualified)
        if table_lsn is not None and self.txn_lsn < table_lsn:
            # 该表做过 resync，快照位点之前提交的变更已包含在复制的数据中
            self.stats['skipped_table_changes'] += 1
            return False
        if info.invalid:
            raise ConfigError(info.invalid)
        return True

    def _apply(self, m):
        if type(m) is pgoutput.Truncate:
            infos = [i for i in (self.registry.get(r) for r in m.relids) if self._wanted(i)]
            if infos:
                self.applier.truncate(infos, m.options)
            return len(infos)
        info = self.registry.get(m.relid)
        if not self._wanted(info):
            return 0
        if type(m) is pgoutput.Insert:
            self.applier.insert(info, m.new)
        elif type(m) is pgoutput.Update:
            self.applier.update(info, m.old_kind, m.old, m.new)
        else:
            self.applier.delete(info, m.old_kind, m.old)
        return 1

    # ------------------------------------------------------------ 提交与确认
    def flush(self):
        end = self.pending_end
        if self.batch_changes > 0:
            self.applier.commit(end)
        # 目标库已提交，才向源库确认，源库此后可以回收这之前的 WAL
        self.cur.send_feedback(write_lsn=end, flush_lsn=end, apply_lsn=end)
        self.flushed = end
        log.debug('已提交批次: %d 条变更, 位点 %s', self.batch_changes, int_to_lsn(end))
        self.pending_end = None
        self.batch_started = None
        self.batch_changes = 0
        self.last_idle_feedback = time.time()

    def _idle_feedback(self, now):
        """空闲时把确认位点推进到服务端 WAL 末尾。

        PG15+ 会跳过与发布无关的空事务，如果只在收到相关事务时确认，
        同步表长时间无变更时复制槽会一直保留 WAL。此时所有已发送的数据都已处理完，
        推进到 wal_end 是安全的（PostgreSQL 自带的订阅端也是这样做的）。
        """
        if now - self.last_idle_feedback < self.cfg.status_interval:
            return
        self.last_idle_feedback = now
        wal_end = getattr(self.cur, 'wal_end', 0) or 0
        if wal_end > self.flushed:
            self.cur.send_feedback(write_lsn=wal_end, flush_lsn=wal_end, apply_lsn=wal_end)
            self.flushed = wal_end

    def _maybe_log_stats(self, now):
        if now - self.last_stats < self.cfg.stats_interval:
            return
        self.last_stats = now
        lag = ''
        if self.last_commit_ts is not None and self.stats['txns']:
            delay = time.time() - pg_timestamp_to_datetime(self.last_commit_ts).timestamp()
            lag = ', 最近事务延迟 %.1fs' % max(0.0, delay)
        extra = ''
        if self.stats['skipped_table_changes']:
            extra = ', 跳过 resync 前变更 %d' % self.stats['skipped_table_changes']
        log.info('统计: 事务 %d, 变更 %d, 跳过重放事务 %d, 目标缺行 %d, 确认位点 %s%s%s',
                 self.stats['txns'], self.stats['changes'], self.stats['skipped_txns'],
                 self.applier.missing_rows, int_to_lsn(self.flushed), lag, extra)
        self.stats = dict.fromkeys(self.stats, 0)
