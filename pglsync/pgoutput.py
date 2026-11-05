# -*- coding: utf-8 -*-
"""
pgoutput 逻辑复制协议解码（proto_version = 1，文本格式列值）。

协议文档: https://www.postgresql.org/docs/current/protocol-logicalrep-message-formats.html

列值保持为 PostgreSQL 的 **文本输出格式**（str），写入目标库时作为未知类型字面量交给
PostgreSQL 按目标列类型解析，因此不存在 JSON 往返导致的精度/类型丢失问题。
"""
import struct
from collections import namedtuple

# 元组中每一列的种类
NULL = 'n'        # NULL
UNCHANGED = 'u'   # 未变化的 TOAST 值（UPDATE 时可能出现，值未随消息发送）
TEXT = 't'        # 文本格式值
BINARY = 'b'      # 二进制格式（proto v1 不会出现）

KEY_FLAG = 1      # Relation 中列 flags 的“属于复制标识（主键）”位

Begin = namedtuple('Begin', 'final_lsn commit_ts xid')
Commit = namedtuple('Commit', 'flags commit_lsn end_lsn commit_ts')
Origin = namedtuple('Origin', 'lsn name')
Column = namedtuple('Column', 'flags name type_oid typmod')
Relation = namedtuple('Relation', 'relid namespace name replident columns')
TypeInfo = namedtuple('TypeInfo', 'oid namespace name')
Insert = namedtuple('Insert', 'relid new')
Update = namedtuple('Update', 'relid old_kind old new')   # old_kind: None / 'K'(仅键列) / 'O'(整行)
Delete = namedtuple('Delete', 'relid old_kind old')
Truncate = namedtuple('Truncate', 'options relids')         # options: 1=CASCADE 2=RESTART IDENTITY
LogicalMessage = namedtuple('LogicalMessage', 'flags lsn prefix content')

TRUNCATE_CASCADE = 1
TRUNCATE_RESTART_IDENTITY = 2


class DecodeError(Exception):
    pass


class _Reader(object):
    __slots__ = ('buf', 'pos')

    def __init__(self, buf):
        self.buf = bytes(buf)
        self.pos = 0

    def take(self, n):
        end = self.pos + n
        if n < 0 or end > len(self.buf):
            raise DecodeError('消息被截断: 需要 %d 字节, 位置 %d, 总长 %d' % (n, self.pos, len(self.buf)))
        data = self.buf[self.pos:end]
        self.pos = end
        return data

    def _unpack(self, fmt, size):
        if self.pos + size > len(self.buf):
            raise DecodeError('消息被截断: 位置 %d, 总长 %d' % (self.pos, len(self.buf)))
        value = struct.unpack_from(fmt, self.buf, self.pos)[0]
        self.pos += size
        return value

    def u8(self):
        return self._unpack('!B', 1)

    def i16(self):
        return self._unpack('!h', 2)

    def i32(self):
        return self._unpack('!i', 4)

    def u32(self):
        return self._unpack('!I', 4)

    def i64(self):
        return self._unpack('!q', 8)

    def u64(self):
        return self._unpack('!Q', 8)

    def char(self):
        return chr(self.u8())

    def cstr(self):
        end = self.buf.find(b'\x00', self.pos)
        if end < 0:
            raise DecodeError('字符串缺少结束符')
        value = self.buf[self.pos:end].decode('utf-8')
        self.pos = end + 1
        return value

    def tuple_data(self):
        count = self.i16()
        values = []
        for _ in range(count):
            kind = self.char()
            if kind == NULL or kind == UNCHANGED:
                values.append((kind, None))
            elif kind == TEXT:
                values.append((kind, self.take(self.i32()).decode('utf-8')))
            elif kind == BINARY:
                values.append((kind, self.take(self.i32())))
            else:
                raise DecodeError('未知的列数据类型标记: %r' % kind)
        return values


def decode(payload):
    """解码一条 pgoutput 消息，返回上面定义的 namedtuple 之一。"""
    r = _Reader(payload)
    tag = r.char()

    if tag == 'B':
        return Begin(r.u64(), r.i64(), r.u32())
    if tag == 'C':
        return Commit(r.u8(), r.u64(), r.u64(), r.i64())
    if tag == 'O':
        return Origin(r.u64(), r.cstr())
    if tag == 'R':
        relid = r.u32()
        namespace = r.cstr()
        name = r.cstr()
        replident = r.char()
        ncols = r.i16()
        columns = []
        for _ in range(ncols):
            flags = r.u8()
            col_name = r.cstr()
            type_oid = r.u32()
            typmod = r.i32()
            columns.append(Column(flags, col_name, type_oid, typmod))
        return Relation(relid, namespace, name, replident, columns)
    if tag == 'Y':
        oid = r.u32()
        namespace = r.cstr()
        return TypeInfo(oid, namespace, r.cstr())
    if tag == 'I':
        relid = r.u32()
        marker = r.char()
        if marker != 'N':
            raise DecodeError('INSERT 消息格式错误: %r' % marker)
        return Insert(relid, r.tuple_data())
    if tag == 'U':
        relid = r.u32()
        marker = r.char()
        old_kind = old = None
        if marker in ('K', 'O'):
            old_kind = marker
            old = r.tuple_data()
            marker = r.char()
        if marker != 'N':
            raise DecodeError('UPDATE 消息格式错误: %r' % marker)
        return Update(relid, old_kind, old, r.tuple_data())
    if tag == 'D':
        relid = r.u32()
        marker = r.char()
        if marker not in ('K', 'O'):
            raise DecodeError('DELETE 消息格式错误: %r' % marker)
        return Delete(relid, marker, r.tuple_data())
    if tag == 'T':
        count = r.u32()
        options = r.u8()
        relids = [r.u32() for _ in range(count)]
        return Truncate(options, relids)
    if tag == 'M':
        flags = r.u8()
        lsn = r.u64()
        prefix = r.cstr()
        content = r.take(r.u32())
        return LogicalMessage(flags, lsn, prefix, content)
    raise DecodeError('未知的 pgoutput 消息类型: %r' % tag)
