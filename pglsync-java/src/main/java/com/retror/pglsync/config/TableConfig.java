package com.retror.pglsync.config;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一张需要同步的表（目标库与源库同名同结构）。 */
@Getter
public class TableConfig {
    private final String schema;
    private final String name;
    /** null 表示从源库主键 / 复制标识自动获取。 */
    private final List<String> pk;
    private final String timeColumn;
    private final String bucket;

    public TableConfig(String schema, String name, List<String> pk, String timeColumn, String bucket) {
        this.schema = schema;
        this.name = name;
        this.pk = pk == null ? null : Collections.unmodifiableList(new ArrayList<>(pk));
        this.timeColumn = timeColumn;
        this.bucket = bucket == null ? "day" : bucket;
    }

    public TableConfig(String schema, String name) {
        this(schema, name, null, null, "day");
    }

    public String qualified() {
        return schema + "." + name;
    }

    @Override
    public String toString() {
        return qualified();
    }
}
