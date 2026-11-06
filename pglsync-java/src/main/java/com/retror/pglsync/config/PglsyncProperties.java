package com.retror.pglsync.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** application.yml 中 pglsync.* 配置。 */
@Data
@ConfigurationProperties(prefix = "pglsync")
public class PglsyncProperties {

    /** 源库连接。 */
    private Db source = new Db();
    /** 目标库连接。 */
    private Db target = new Db();

    private String slotName = "pglsync_slot";
    private String publication = "pglsync_pub";
    /** 目标库中存放同步位点表的 schema。 */
    private String stateSchema = "pglsync";
    /** auto / schema / all。 */
    private String publicationMode = "auto";
    /** TimescaleDB chunk 所在的 schema。 */
    private List<String> chunkSchemas = new ArrayList<>(Collections.singletonList("_timescaledb_internal"));
    /** 需要同步的表。 */
    private List<Table> tables = new ArrayList<>();

    private Apply apply = new Apply();
    private Stream stream = new Stream();

    @Data
    public static class Db {
        private String host;
        private int port = 5432;
        private String dbname;
        private String user;
        private String password;
        /** 其他 pgjdbc 连接参数，例如 ssl / sslmode。 */
        private Map<String, String> params = new LinkedHashMap<>();
    }

    @Data
    public static class Table {
        /** schema.table，省略 schema 时为 public。 */
        private String name;
        /** 主键列；不填时取源表主键。 */
        private List<String> pk;
        /** reconcile 按时间分桶核对时使用。 */
        private String timeColumn;
        /** hour / day / week / month。 */
        private String bucket = "day";
    }

    @Data
    public static class Apply {
        private int batchMaxChanges = 5000;
        private double batchMaxSeconds = 1.0;
        private int upsertGroupSize = 500;
        /** warn / error。 */
        private String onMissingRow = "warn";
        /** warn / error。 */
        private String chunkTruncate = "warn";
    }

    @Data
    public static class Stream {
        private double statusIntervalSeconds = 10;
        private double retryInitialSeconds = 5;
        private double retryMaxSeconds = 60;
        private double statsIntervalSeconds = 60;
    }
}
