package com.retror.pglsync.config;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 校验后的运行配置（由 {@link PglsyncProperties} 生成）。 */
@Getter
public class SyncConfig {
    private static final Pattern IDENT = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");
    private static final List<String> BUCKETS = Arrays.asList("hour", "day", "week", "month");

    private final PglsyncProperties.Db source;
    private final PglsyncProperties.Db target;
    private final String slotName;
    private final String publication;
    private final String stateSchema;
    private final String publicationMode;
    private final List<String> chunkSchemas;
    private final Map<String, TableConfig> tables;

    private final int batchMaxChanges;
    private final double batchMaxSeconds;
    private final int upsertGroupSize;
    private final String onMissingRow;
    private final String chunkTruncate;

    private final double statusIntervalSeconds;
    private final double retryInitialSeconds;
    private final double retryMaxSeconds;
    private final double statsIntervalSeconds;

    private SyncConfig(PglsyncProperties p) {
        this.source = requireDb(p.getSource(), "source");
        this.target = requireDb(p.getTarget(), "target");
        this.slotName = ident(p.getSlotName(), "slot-name");
        this.publication = ident(p.getPublication(), "publication");
        this.stateSchema = ident(p.getStateSchema(), "state-schema");
        this.publicationMode = choice(p.getPublicationMode(), "publication-mode", "auto", "schema", "all");
        this.chunkSchemas = Collections.unmodifiableList(new ArrayList<>(p.getChunkSchemas()));
        this.tables = Collections.unmodifiableMap(parseTables(p.getTables()));

        PglsyncProperties.Apply a = p.getApply();
        this.batchMaxChanges = a.getBatchMaxChanges();
        this.batchMaxSeconds = a.getBatchMaxSeconds();
        this.upsertGroupSize = a.getUpsertGroupSize();
        this.onMissingRow = choice(a.getOnMissingRow(), "apply.on-missing-row", "warn", "error");
        this.chunkTruncate = choice(a.getChunkTruncate(), "apply.chunk-truncate", "warn", "error");
        if (batchMaxChanges <= 0 || upsertGroupSize <= 0) {
            throw new ConfigException("batch-max-changes / upsert-group-size 必须大于 0");
        }

        PglsyncProperties.Stream s = p.getStream();
        this.statusIntervalSeconds = s.getStatusIntervalSeconds();
        this.retryInitialSeconds = s.getRetryInitialSeconds();
        this.retryMaxSeconds = s.getRetryMaxSeconds();
        this.statsIntervalSeconds = s.getStatsIntervalSeconds();
    }

    public static SyncConfig from(PglsyncProperties p) {
        return new SyncConfig(p);
    }

    public boolean missingRowIsError() {
        return "error".equals(onMissingRow);
    }

    public boolean chunkTruncateIsError() {
        return "error".equals(chunkTruncate);
    }

    private static PglsyncProperties.Db requireDb(PglsyncProperties.Db db, String name) {
        if (db == null || isBlank(db.getHost()) || isBlank(db.getDbname()) || isBlank(db.getUser())) {
            throw new ConfigException("pglsync." + name + " 的 host / dbname / user 必须配置");
        }
        return db;
    }

    private static String ident(String value, String field) {
        if (value == null || !IDENT.matcher(value).matches()) {
            throw new ConfigException("pglsync." + field + " 必须是由小写字母、数字、下划线组成的标识符，当前值: " + value);
        }
        return value;
    }

    private static String choice(String value, String field, String... choices) {
        for (String c : choices) {
            if (c.equals(value)) {
                return value;
            }
        }
        throw new ConfigException("pglsync." + field + " 只能是 " + String.join("/", choices) + " 之一，当前值: " + value);
    }

    private static Map<String, TableConfig> parseTables(List<PglsyncProperties.Table> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new ConfigException("pglsync.tables 不能为空");
        }
        Map<String, TableConfig> tables = new LinkedHashMap<>();
        for (PglsyncProperties.Table t : raw) {
            if (isBlank(t.getName())) {
                throw new ConfigException("pglsync.tables 中存在没有 name 的表");
            }
            String schema = "public";
            String name = t.getName().trim();
            int dot = name.indexOf('.');
            if (dot >= 0) {
                schema = name.substring(0, dot);
                name = name.substring(dot + 1);
            }
            if (t.getPk() != null && t.getPk().isEmpty()) {
                throw new ConfigException("表 " + t.getName() + " 的 pk 不能是空列表");
            }
            String bucket = choice(t.getBucket() == null ? "day" : t.getBucket(),
                    "tables[" + t.getName() + "].bucket", BUCKETS.toArray(new String[0]));
            TableConfig tc = new TableConfig(schema, name, t.getPk(), t.getTimeColumn(), bucket);
            if (tables.put(tc.qualified(), tc) != null) {
                throw new ConfigException("表重复配置: " + tc.qualified());
            }
        }
        return tables;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
