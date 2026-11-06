package com.retror.pglsync.catalog;

import com.retror.pglsync.config.TableConfig;

import java.util.List;

/** 一个源库 relation（pgoutput 的 relid）及其映射到的目标表。 */
public class RelationInfo {
    public final long relid;
    public final String namespace;
    public final String name;
    public final char replident;
    public final List<String> colNames;
    public final List<String> keyCols;

    /** null 表示该表不需要同步。 */
    public TableConfig target;
    /** 目标表主键列（ON CONFLICT / WHERE 使用）。 */
    public List<String> pk;
    public boolean chunk;
    /** 校验失败原因；真正需要应用该 relation 的变更时才报错。 */
    public String invalid;

    public RelationInfo(long relid, String namespace, String name, char replident,
                        List<String> colNames, List<String> keyCols) {
        this.relid = relid;
        this.namespace = namespace;
        this.name = name;
        this.replident = replident;
        this.colNames = colNames;
        this.keyCols = keyCols;
    }

    public String sourceName() {
        return namespace + "." + name;
    }
}
