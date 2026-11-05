# -*- coding: utf-8 -*-
"""配置加载与校验（JSON 格式，密码建议通过环境变量提供）。"""
import json
import os
import re

IDENT_RE = re.compile(r'^[a-z_][a-z0-9_]{0,62}$')
VALID_BUCKETS = ('hour', 'day', 'week', 'month')


class ConfigError(Exception):
    """配置错误或无法自动恢复的前置条件错误：进程应当退出，而不是无限重试。"""


class TableConfig(object):
    """一张需要同步的表（目标库与源库同名同结构）。"""

    def __init__(self, schema, name, pk=None, time_column=None, bucket='day'):
        self.schema = schema
        self.name = name
        self.pk = list(pk) if pk else None   # None 表示从源库主键/复制标识自动获取
        self.time_column = time_column       # reconcile 按时间分桶核对时使用
        self.bucket = bucket

    @property
    def qualified(self):
        return '%s.%s' % (self.schema, self.name)

    def __repr__(self):
        return 'TableConfig(%s, pk=%r)' % (self.qualified, self.pk)


def _require_ident(value, field):
    if not isinstance(value, str) or not IDENT_RE.match(value):
        raise ConfigError('%s 必须是由小写字母、数字、下划线组成的标识符，当前值: %r' % (field, value))
    return value


def _choice(value, field, choices):
    if value not in choices:
        raise ConfigError('%s 只能是 %s 之一，当前值: %r' % (field, '/'.join(choices), value))
    return value


def _conn_params(section, name):
    """把配置段转换为 psycopg2.connect() 参数。支持任意 libpq 参数（sslmode 等）。"""
    if not isinstance(section, dict):
        raise ConfigError('缺少配置段: %s' % name)
    params = {}
    for key, value in section.items():
        if key in ('password_env',) or key.startswith('_'):
            continue
        params[key] = value
    env = section.get('password_env')
    if env:
        password = os.environ.get(env)
        if password is None:
            raise ConfigError('环境变量 %s 未设置（%s.password_env）' % (env, name))
        params['password'] = password
    for required in ('host', 'dbname', 'user'):
        if required not in params:
            raise ConfigError('%s.%s 未配置' % (name, required))
    params.setdefault('connect_timeout', 10)
    params.setdefault('application_name', 'pglsync')
    return params


def _parse_tables(raw):
    if isinstance(raw, list):
        raw = dict((k, {}) for k in raw)
    if not isinstance(raw, dict) or not raw:
        raise ConfigError('tables 不能为空')
    tables = {}
    for key, opts in raw.items():
        if key.startswith('_'):
            continue
        opts = opts or {}
        if '.' in key:
            schema, name = key.split('.', 1)
        else:
            schema, name = 'public', key
        pk = opts.get('pk')
        if pk is not None and (not isinstance(pk, list) or not pk):
            raise ConfigError('tables.%s.pk 必须是非空列表' % key)
        bucket = _choice(opts.get('bucket', 'day'), 'tables.%s.bucket' % key, VALID_BUCKETS)
        table = TableConfig(schema, name, pk, opts.get('time_column'), bucket)
        if table.qualified in tables:
            raise ConfigError('表重复配置: %s' % table.qualified)
        tables[table.qualified] = table
    if not tables:
        raise ConfigError('tables 不能为空')
    return tables


class Config(object):
    def __init__(self, raw, path=None):
        self.path = path
        self.source = _conn_params(raw.get('source'), 'source')
        self.target = _conn_params(raw.get('target'), 'target')

        self.slot_name = _require_ident(raw.get('slot_name', 'pglsync_slot'), 'slot_name')
        self.publication = _require_ident(raw.get('publication', 'pglsync_pub'), 'publication')
        self.state_schema = _require_ident(raw.get('state_schema', 'pglsync'), 'state_schema')
        self.publication_mode = _choice(raw.get('publication_mode', 'auto'),
                                        'publication_mode', ('auto', 'schema', 'all'))
        self.chunk_schemas = list(raw.get('chunk_schemas', ['_timescaledb_internal']))
        self.tables = _parse_tables(raw.get('tables'))

        apply_cfg = raw.get('apply') or {}
        self.batch_max_changes = int(apply_cfg.get('batch_max_changes', 5000))
        self.batch_max_seconds = float(apply_cfg.get('batch_max_seconds', 1.0))
        self.upsert_group_size = int(apply_cfg.get('upsert_group_size', 500))
        self.on_missing_row = _choice(apply_cfg.get('on_missing_row', 'warn'),
                                      'apply.on_missing_row', ('warn', 'error'))
        self.chunk_truncate = _choice(apply_cfg.get('chunk_truncate', 'warn'),
                                      'apply.chunk_truncate', ('warn', 'error'))

        stream_cfg = raw.get('stream') or {}
        self.status_interval = float(stream_cfg.get('status_interval_seconds', 10))
        self.retry_initial = float(stream_cfg.get('retry_initial_seconds', 5))
        self.retry_max = float(stream_cfg.get('retry_max_seconds', 60))
        self.stats_interval = float(stream_cfg.get('stats_interval_seconds', 60))

        log_cfg = raw.get('logging') or {}
        self.log_file = log_cfg.get('file')
        self.log_level = str(log_cfg.get('level', 'INFO')).upper()

        if self.batch_max_changes <= 0 or self.upsert_group_size <= 0:
            raise ConfigError('batch_max_changes / upsert_group_size 必须大于 0')


def load_config(path):
    if not os.path.exists(path):
        raise ConfigError('配置文件不存在: %s（可参考 config.example.json）' % path)
    with open(path, 'r', encoding='utf-8') as f:
        try:
            raw = json.load(f)
        except ValueError as e:
            raise ConfigError('配置文件 JSON 格式错误: %s' % e)
    return Config(raw, path)
