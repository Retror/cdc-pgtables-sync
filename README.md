# cdc-pgtables-sync

PostgreSQL 数据单向同步工具 **pglsync**。它基于 PostgreSQL 逻辑复制（WAL / pgoutput），按源库提交顺序把指定表的变更同步到目标库，并支持 TimescaleDB 超表。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/USER_GUIDE.md](docs/USER_GUIDE.md) | **完整使用手册**：数据库配置、同步程序运行、测试写入脚本、排错 |
| [docs/DEPLOYMENT_GUIDE.md](docs/DEPLOYMENT_GUIDE.md) | 生产上线手册：从旧触发器方案切换、监控、回退 |
| [docs/LOGICAL_SYNC.md](docs/LOGICAL_SYNC.md) | 方案原理、与旧方案的对比、限制 |
| [docs/TEST_ENV_SETUP.md](docs/TEST_ENV_SETUP.md) | 测试环境（131 → 132）速查 |

## 快速开始

```bash
pip3 install -r requirements.txt
python3 -m pglsync -c config.test.json check            # 检查源库条件
python3 -m pglsync -c config.test.json setup            # 创建发布
python3 -m pglsync -c config.test.json init --no-copy   # 目标库已有数据：核对后从当前位置开始（目标库为空时用 init）
python3 -m pglsync -c config.test.json run              # 增量同步

python3 tools/load_writer.py -c config.test.json        # 测试：每 5 分钟向两张超表各写入约 1 万行
```
