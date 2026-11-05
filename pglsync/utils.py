# -*- coding: utf-8 -*-
"""通用小工具：LSN 转换、标识符引用、时间戳转换。"""
import datetime

PG_EPOCH = datetime.datetime(2000, 1, 1, tzinfo=datetime.timezone.utc)


def lsn_to_int(text):
    """'16/B374D848' -> int"""
    hi, lo = text.split('/')
    return (int(hi, 16) << 32) | int(lo, 16)


def int_to_lsn(value):
    """int -> '16/B374D848'"""
    return '%X/%X' % (value >> 32, value & 0xFFFFFFFF)


def qi(name):
    """以双引号引用 SQL 标识符。

    生成的 SQL 会作为 psycopg2 的查询模板使用，'%' 会和参数占位符冲突，因此直接拒绝。
    """
    if not isinstance(name, str) or not name:
        raise ValueError('非法标识符: %r' % (name,))
    if '%' in name or '\x00' in name:
        raise ValueError('标识符中不允许包含 %% 或 NUL: %r' % (name,))
    return '"' + name.replace('"', '""') + '"'


def qname(schema, name):
    return '%s.%s' % (qi(schema), qi(name))


def pg_timestamp_to_datetime(microseconds):
    """pgoutput 中的时间戳（2000-01-01 起的微秒数）-> aware datetime(UTC)"""
    return PG_EPOCH + datetime.timedelta(microseconds=microseconds)
