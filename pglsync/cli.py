# -*- coding: utf-8 -*-
"""命令行入口: python -m pglsync -c config.json <命令>"""
import argparse
import logging
import logging.handlers
import os
import signal
import sys

from . import __version__
from .config import ConfigError, load_config


def _setup_logging(cfg, verbose):
    level = logging.DEBUG if verbose else getattr(logging, cfg.log_level, logging.INFO)
    handlers = [logging.StreamHandler(sys.stdout)]
    if cfg.log_file:
        log_dir = os.path.dirname(cfg.log_file)
        if log_dir:
            os.makedirs(log_dir, exist_ok=True)
        handlers.append(logging.handlers.RotatingFileHandler(
            cfg.log_file, maxBytes=50 * 1024 * 1024, backupCount=10, encoding='utf-8'))
    fmt = logging.Formatter('%(asctime)s %(levelname)-7s [%(name)s] %(message)s')
    root = logging.getLogger()
    root.setLevel(level)
    for h in handlers:
        h.setFormatter(fmt)
        root.addHandler(h)


def build_parser():
    p = argparse.ArgumentParser(prog='pglsync', description='基于 PostgreSQL 逻辑复制（pgoutput）的单向表同步工具')
    p.add_argument('-c', '--config', default=os.environ.get('PGLSYNC_CONFIG', 'config.json'),
                   help='配置文件路径（默认 config.json 或环境变量 PGLSYNC_CONFIG）')
    p.add_argument('-v', '--verbose', action='store_true', help='输出 DEBUG 日志')
    p.add_argument('--version', action='version', version='pglsync ' + __version__)
    sub = p.add_subparsers(dest='command')

    sub.add_parser('check', help='只读检查源库逻辑复制前置条件')

    s = sub.add_parser('setup', help='检查并在源库创建发布（publication）')
    s.add_argument('--fix-replica-identity', action='store_true',
                   help='对缺少复制标识的表执行 REPLICA IDENTITY FULL')
    s.add_argument('--force', action='store_true', help='忽略复制标识检查（风险自负）')

    s = sub.add_parser('init', help='创建复制槽，并以一致性快照全量复制到目标库（会 TRUNCATE 目标表）；'
                                    '目标库已有数据时用 --no-copy')
    s.add_argument('-y', '--yes', action='store_true', help='不再交互确认')
    s.add_argument('--recreate-slot', action='store_true', help='删除已存在的复制槽后重新初始化')
    s.add_argument('--no-copy', action='store_true',
                   help='不全量复制，以目标库现有数据为起点（会先用快照核对两边是否一致）')
    s.add_argument('--on-mismatch', choices=('abort', 'copy', 'ignore'), default='abort',
                   help='--no-copy 核对发现不一致时：abort 放弃（默认）/ copy 只重新复制不一致的表 / ignore 保留差异')
    s.add_argument('--skip-verify', action='store_true',
                   help='--no-copy 时跳过核对（数据量极大时使用，需自行保证两边一致）')

    sub.add_parser('run', help='启动增量同步（常驻进程）')
    sub.add_parser('status', help='查看复制槽积压与同步位点')

    s = sub.add_parser('reconcile', help='核对源库与目标库数据（行数 + 整行哈希）')
    s.add_argument('-t', '--table', action='append', help='只核对指定表（可多次指定）')
    s.add_argument('--since', help='只核对 time_column >= 该时间的数据，例如 2026-10-01')
    s.add_argument('--recheck-delay', type=float, default=10, help='不一致时等待多少秒后复查（默认 10，0 表示不复查）')
    s.add_argument('--detail', action='store_true', help='列出不一致的主键')
    s.add_argument('--limit', type=int, default=20, help='每类最多输出多少条（默认 20）')

    s = sub.add_parser('drop', help='删除复制槽与发布（停用同步时务必执行，否则源库 WAL 会持续堆积）')
    s.add_argument('-y', '--yes', action='store_true')
    s.add_argument('--keep-publication', action='store_true')
    return p


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    if not args.command:
        parser.print_help()
        return 2
    try:
        cfg = load_config(args.config)
        _setup_logging(cfg, args.verbose)
        log = logging.getLogger('pglsync')

        if args.command == 'check':
            from .setup_source import run_setup
            return run_setup(cfg, apply=False)
        if args.command == 'setup':
            from .setup_source import run_setup
            return run_setup(cfg, apply=True, fix_ri=args.fix_replica_identity, force=args.force)
        if args.command == 'init':
            from .snapshot import run_init
            if (args.skip_verify or args.on_mismatch != 'abort') and not args.no_copy:
                raise ConfigError('--skip-verify / --on-mismatch 只能与 --no-copy 一起使用')
            return run_init(cfg, assume_yes=args.yes, recreate_slot=args.recreate_slot,
                            copy=not args.no_copy, verify=not args.skip_verify, on_mismatch=args.on_mismatch)
        if args.command == 'run':
            from .stream import Streamer
            streamer = Streamer(cfg)
            signal.signal(signal.SIGINT, streamer.request_stop)
            signal.signal(signal.SIGTERM, streamer.request_stop)
            log.info('pglsync %s 启动，同步 %d 张表', __version__, len(cfg.tables))
            streamer.run_forever()
            return 0
        if args.command == 'status':
            from .admin import run_status
            return run_status(cfg)
        if args.command == 'reconcile':
            from .reconcile import run_reconcile
            return run_reconcile(cfg, only=args.table, since=args.since, recheck_delay=args.recheck_delay,
                                 detail=args.detail, limit=args.limit)
        if args.command == 'drop':
            from .admin import run_drop
            return run_drop(cfg, assume_yes=args.yes, keep_publication=args.keep_publication)
    except ConfigError as e:
        print('错误: %s' % e, file=sys.stderr)
        logging.getLogger('pglsync').error('%s', e)
        return 3
    except KeyboardInterrupt:
        return 130
    return 2
