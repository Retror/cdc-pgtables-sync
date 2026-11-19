# cdc-pgtables-sync

PostgreSQL 数据单向同步工具 **pglsync**。它基于 PostgreSQL 逻辑复制（WAL / pgoutput），按源库提交顺序把指定表的变更同步到目标库，并支持 TimescaleDB 超表。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/USER_GUIDE.md](docs/USER_GUIDE.md) | **完整使用手册**：数据库配置、同步程序运行、测试写入脚本、排错 |
| [docs/DEPLOYMENT_GUIDE.md](docs/DEPLOYMENT_GUIDE.md) | 生产上线手册：从旧触发器方案切换、监控、回退 |
| [docs/LOGICAL_SYNC.md](docs/LOGICAL_SYNC.md) | 方案原理、与旧方案的对比、限制 |
| [docs/TEST_ENV_SETUP.md](docs/TEST_ENV_SETUP.md) | 测试环境（131 → 132）速查 |

> Java 版（Spring Boot 2.7 + JDK 8）在 `feature/java-springboot` 分支的 `pglsync-java/` 目录。

### 运维与专题

| 文档 | 内容 |
|---|---|
| [docs/USER_GUIDE.md 第 8 节](docs/USER_GUIDE.md#8-新增同步表与表结构变更) | 新增同步表、**去掉同步表**（8.4 节）、修改表结构 |
| [docs/REPLICATION_SLOT.md](docs/REPLICATION_SLOT.md) | 复制槽原理、风险与监控 |
| [docs/SWITCH_TARGET.md](docs/SWITCH_TARGET.md) | 更换 / 新增目标库，旧复制槽怎么处理 |
| [docs/SQL_OPERATIONS.md](docs/SQL_OPERATIONS.md) | MERGE、ON CONFLICT、级联删除等各类 SQL 操作的同步情况 |
| [docs/COPY_AND_BULK_LOAD.md](docs/COPY_AND_BULK_LOAD.md) | COPY 与批量导入 |
| [docs/CASE_LARGE_DELETE.md](docs/CASE_LARGE_DELETE.md) | 案例复盘：大批量删除后目标库迟迟没有同步 |
| [docs/VERSION_COMPAT.md](docs/VERSION_COMPAT.md) | 源库与目标库 PostgreSQL 版本不一致 |
| [docs/OFFLINE_DEPLOY.md](docs/OFFLINE_DEPLOY.md) | 无外网 / 只有 Python 2.7 的机器上部署 |
| [docs/DOCKER_DEPLOY.md](docs/DOCKER_DEPLOY.md) | Docker 部署 |

## 快速开始

```bash
pip3 install -r requirements.txt
python3 -m pglsync -c config.test.json check            # 检查源库条件
python3 -m pglsync -c config.test.json setup            # 创建发布
python3 -m pglsync -c config.test.json init --no-copy   # 目标库已有数据：核对后从当前位置开始（目标库为空时用 init）
python3 -m pglsync -c config.test.json run              # 增量同步

python3 tools/load_writer.py -c config.test.json        # 测试：每 5 分钟向两张超表各写入约 1 万行
```
