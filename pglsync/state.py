# -*- coding: utf-8 -*-
"""
目标库中的同步位点表：位点与数据在同一事务中提交，实现精确续传。

  sync_state  ：每个复制槽一行，记录最后一个已应用事务的 end_lsn；
  table_state ：单表重新复制（resync）时记录该表快照对应的位点 X。
                增量同步遇到该表在 X 之前提交的变更会跳过（这些变更已包含在复制的数据中）。
"""
from .utils import int_to_lsn, lsn_to_int, qi


def ensure_state_table(conn, schema):
    with conn.cursor() as cur:
        cur.execute('CREATE SCHEMA IF NOT EXISTS %s' % qi(schema))
        cur.execute(
            'CREATE TABLE IF NOT EXISTS %s.sync_state ('
            ' slot_name text PRIMARY KEY,'
            ' lsn pg_lsn NOT NULL,'
            ' updated_at timestamptz NOT NULL DEFAULT now())' % qi(schema))
        cur.execute(
            'CREATE TABLE IF NOT EXISTS %s.table_state ('
            ' slot_name text NOT NULL,'
            ' table_name text NOT NULL,'
            ' lsn pg_lsn NOT NULL,'
            ' updated_at timestamptz NOT NULL DEFAULT now(),'
            ' PRIMARY KEY (slot_name, table_name))' % qi(schema))
    conn.commit()


def state_upsert_sql(schema):
    return ('INSERT INTO %s.sync_state (slot_name, lsn, updated_at) VALUES (%%s, %%s::pg_lsn, now()) '
            'ON CONFLICT (slot_name) DO UPDATE SET lsn = EXCLUDED.lsn, updated_at = now()' % qi(schema))


def write_state(cur, schema, slot_name, lsn):
    """在调用方的事务内写入位点（不提交）。"""
    cur.execute(state_upsert_sql(schema), (slot_name, int_to_lsn(lsn)))


def write_table_state(cur, schema, slot_name, table_name, lsn):
    """在调用方的事务内写入单表起始位点（不提交）。"""
    cur.execute('INSERT INTO %s.table_state (slot_name, table_name, lsn, updated_at) '
                'VALUES (%%s, %%s, %%s::pg_lsn, now()) '
                'ON CONFLICT (slot_name, table_name) DO UPDATE SET lsn = EXCLUDED.lsn, updated_at = now()'
                % qi(schema), (slot_name, table_name, int_to_lsn(lsn)))


def clear_table_states(cur, schema, slot_name):
    """在调用方的事务内清除该复制槽的所有单表位点（init 后全部表起点一致，不再需要）。"""
    cur.execute('DELETE FROM %s.table_state WHERE slot_name = %%s' % qi(schema), (slot_name,))


def _exists(cur, schema, table):
    cur.execute("SELECT to_regclass(%s)", ('%s.%s' % (qi(schema), table),))
    return cur.fetchone()[0] is not None


def read_state(conn, schema, slot_name):
    """返回 (lsn:int, updated_at) 或 None。"""
    with conn.cursor() as cur:
        if not _exists(cur, schema, 'sync_state'):
            conn.commit()
            return None
        cur.execute('SELECT lsn::text, updated_at FROM %s.sync_state WHERE slot_name = %%s' % qi(schema),
                    (slot_name,))
        row = cur.fetchone()
    conn.commit()
    if row is None:
        return None
    return lsn_to_int(row[0]), row[1]


def read_table_states(conn, schema, slot_name):
    """返回 {'schema.table': lsn:int}"""
    with conn.cursor() as cur:
        if not _exists(cur, schema, 'table_state'):
            conn.commit()
            return {}
        cur.execute('SELECT table_name, lsn::text FROM %s.table_state WHERE slot_name = %%s' % qi(schema),
                    (slot_name,))
        rows = cur.fetchall()
    conn.commit()
    return dict((name, lsn_to_int(lsn)) for name, lsn in rows)


def delete_state(conn, schema, slot_name):
    with conn.cursor() as cur:
        for table in ('sync_state', 'table_state'):
            if _exists(cur, schema, table):
                cur.execute('DELETE FROM %s.%s WHERE slot_name = %%s' % (qi(schema), table), (slot_name,))
    conn.commit()
