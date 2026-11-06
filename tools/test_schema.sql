-- 测试用表结构：tcrealtime / tcensemblerealtime
-- 只在拿不到生产表结构时使用；能拿到的话，请优先用 pg_dump -s 导出生产表结构（见 docs/TEST_ENV_SETUP.md 第 3 节）。
-- 需要在 131（源库）和 132（目标库）上分别执行：
--   psql -h 192.168.234.131 -U postgres -d typhoon -f tools/test_schema.sql
--   psql -h 192.168.234.132 -U postgres -d typhoon -f tools/test_schema.sql

-- 安装了 TimescaleDB（shared_preload_libraries 中已包含 timescaledb）时创建扩展，否则跳过
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS timescaledb;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE '未启用 TimescaleDB（%），将创建普通表', SQLERRM;
END $$;

CREATE TABLE IF NOT EXISTS public.tcrealtime (
    id           bigserial,
    datatime     timestamptz   NOT NULL,
    tcid         varchar(32)   NOT NULL,
    tcname       varchar(64),
    lon          numeric(8,3),
    lat          numeric(7,3),
    pressure     numeric(7,1),
    wind_speed   numeric(6,1),
    tc_level     varchar(16),
    move_dir     varchar(8),
    move_speed   numeric(6,1),
    source       varchar(32),
    create_time  timestamptz   DEFAULT now(),
    PRIMARY KEY (id, datatime)
);

CREATE TABLE IF NOT EXISTS public.tcensemblerealtime (
    id            bigserial,
    datatime      timestamptz  NOT NULL,
    tcid          varchar(32)  NOT NULL,
    member        smallint     NOT NULL,
    forecast_hour integer      NOT NULL,
    lon           numeric(8,3),
    lat           numeric(7,3),
    pressure      numeric(7,1),
    wind_speed    numeric(6,1),
    source        varchar(32),
    create_time   timestamptz  DEFAULT now(),
    PRIMARY KEY (id, datatime)
);

-- 有 TimescaleDB 时转成超表（按天分 chunk）
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN
        PERFORM create_hypertable('public.tcrealtime', 'datatime',
                                  chunk_time_interval => interval '1 day', if_not_exists => TRUE);
        PERFORM create_hypertable('public.tcensemblerealtime', 'datatime',
                                  chunk_time_interval => interval '1 day', if_not_exists => TRUE);
    END IF;
END $$;
