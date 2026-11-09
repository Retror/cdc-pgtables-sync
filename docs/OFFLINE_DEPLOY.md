# 离线部署：在无外网、只有 Python 2.7 的机器上运行 pglsync

## 先说结论

**不要用 Python 2.7 运行 pglsync，也不要去替换系统自带的 Python 2.7。** 推荐的做法是在目标机器上**离线安装一个 Python 3，与 Python 2.7 并存**，然后用离线安装包部署 pglsync。

为什么不用 Python 2.7：

| 原因 | 说明 |
|---|---|
| 程序本身是 Python 3 写的 | 字符串/字节处理、时区、文件编码等都依赖 Python 3，在 2.7 上无法直接运行 |
| Python 2.7 已停止维护 | 2020 年起官方不再提供任何修复，包括安全修复 |
| 驱动支持已停止 | psycopg2 最后一个支持 2.7 的版本是 2.8.6（2020 年），之后的修复都拿不到 |
| 不能替换系统 Python | CentOS/RHEL 7 的 `yum` 等系统工具依赖 `/usr/bin/python`（2.7），替换会把系统工具弄坏 |

可选方案：

| 方案 | 适用情况 | 难度 |
|---|---|---|
| **A. 离线安装 Python 3（与 2.7 并存），推荐** | 绝大多数情况 | 低 |
| B. 改用 Java 版（`pglsync.jar`） | 目标机器有 JRE 8+，或者可以离线放一个 JRE | 很低 |
| C. 把程序移植到 Python 2.7 | 目标机器**完全不允许**放任何新的运行环境 | 不推荐，需要的话可以再做 |

> 目标机器装有 Docker 的话，也可以直接用 Docker 部署，镜像里自带 Python 3 和所有依赖，见 [DOCKER_DEPLOY.md](DOCKER_DEPLOY.md)。

---

## 1. 先查清目标机器的情况

在目标机器上执行，并记下结果：

```bash
cat /etc/os-release | head -n 4     # 操作系统及版本（CentOS 7 / RHEL 7 / 麒麟 / UOS ...）
uname -m                            # CPU 架构：x86_64 或 aarch64（ARM，如鲲鹏、飞腾）
ldd --version | head -n 1           # glibc 版本，需要 >= 2.17
python3 -V 2>&1                     # 是否已有 python3（有些系统其实装了，只是默认 python 是 2.7）
java -version 2>&1 | head -n 1      # 是否有 Java（方案 B）
```

按结果选择：

| 查询结果 | 推荐做法 |
|---|---|
| 已有 `python3`，版本 >= 3.6 | 直接跳到 [第 3 节](#3-在有网的机器上制作离线安装包) 制作离线包 |
| CentOS/RHEL 7，没有 python3 | [2.1 节](#21-centos--rhel-7离线安装系统自带的-python36)：离线安装系统自带的 python3（3.6） |
| 其他系统，或者没有 root 权限 | [2.2 节](#22-通用做法独立版-python免编译可以不用-root)：放一个独立版 Python |
| glibc 低于 2.17（例如 CentOS 6） | 见 [第 8 节常见问题](#8-常见问题)，或者改用方案 B |
| 有 JRE 8+ | 也可以直接用方案 B（Java 版），最省事 |

---

## 2. 在目标机器上离线安装 Python 3

### 2.1 CentOS / RHEL 7：离线安装系统自带的 python3（3.6）

CentOS 7.7 及以后的官方源里自带 `python3`（3.6.8）。它安装在 `/usr/bin/python3`，**不影响** `/usr/bin/python`（2.7）。

**① 在一台有网的机器上下载 RPM 包。** 最好用同版本的 CentOS 7；没有的话，用 Docker 起一个 `centos:7` 容器：

```bash
mkdir -p rpms
docker run --rm -v "$PWD/rpms:/rpms" centos:7 bash -c '
  # CentOS 7 已停止维护，切换到归档源 vault.centos.org
  sed -i -e "s/^mirrorlist/#mirrorlist/" \
         -e "s|^#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|" /etc/yum.repos.d/CentOS-*.repo
  yum install -y yum-utils
  yumdownloader --resolve --destdir=/rpms python3 python3-pip python3-setuptools
'
ls rpms     # 应该有 python3-3.6.8-*.rpm、python3-libs-*.rpm、python3-pip-*.rpm 等
```

**② 把 `rpms/` 拷贝到目标机器，然后离线安装：**

```bash
sudo yum localinstall -y --disablerepo='*' rpms/*.rpm
# 也可以用：sudo rpm -Uvh rpms/*.rpm

python3 -V      # Python 3.6.8
python -V       # 仍然是 Python 2.7.x，系统工具不受影响
```

如果安装时提示缺少某个依赖，在有网的机器上用同样的方法把它下载下来，一起安装即可。

### 2.2 通用做法：独立版 Python（免编译，可以不用 root）

适用于麒麟、UOS、SUSE 等系统，或者没有 root 权限的情况。[python-build-standalone](https://github.com/astral-sh/python-build-standalone/releases) 提供**解压即用**的 Python 3，要求 glibc >= 2.17。

**① 在有网的机器上下载**（在 Releases 页面选择 `install_only` 版本）：

| 目标机器架构（`uname -m`） | 文件名 |
|---|---|
| x86_64 | `cpython-3.11.x+<日期>-x86_64-unknown-linux-gnu-install_only.tar.gz` |
| aarch64 | `cpython-3.11.x+<日期>-aarch64-unknown-linux-gnu-install_only.tar.gz` |

**② 拷贝到目标机器并解压：**

```bash
sudo mkdir -p /opt/python3
sudo tar -xzf cpython-3.11.*-install_only.tar.gz -C /opt/python3 --strip-components=1
/opt/python3/bin/python3 -V        # Python 3.11.x
```

不需要配置环境变量，也不会影响系统里的 `python`。后面安装 pglsync 时，用 `PYTHON=/opt/python3/bin/python3` 指定这个解释器即可。

> 也可以在目标机器上从源码编译 Python 3，但需要 gcc、openssl-devel、zlib-devel 等编译依赖，离线环境下比较麻烦，不推荐。

---

## 3. 在有网的机器上制作离线安装包

pglsync 只依赖一个第三方包 **psycopg2-binary**。它是预编译好的，**内置了 libpq**，所以目标机器不需要安装 PostgreSQL 客户端，也不需要编译器。

需要的是：**与目标机器的 Python 版本和 CPU 架构完全匹配**的 wheel 文件。

### 3.1 用脚本自动打包（Linux / macOS / WSL）

```bash
cd cdc-pgtables-sync
git checkout test/local-vm-env          # 或 feature/java-springboot，两个分支都包含 Python 版

# 参数：目标机器的 Python 版本 [平台]
bash deploy/offline/build_bundle.sh 3.6                          # CentOS 7 自带的 python3
bash deploy/offline/build_bundle.sh 3.11                         # 2.2 节的独立版 Python 3.11
bash deploy/offline/build_bundle.sh 3.6 manylinux2014_aarch64    # ARM 服务器
```

生成的文件是 `dist/pglsync-offline-py3.6-manylinux2014_x86_64.tar.gz`，里面包含：

- 程序代码和测试；
- 配置样例；
- psycopg2-binary 的 wheel；
- 安装脚本 `install.sh`；
- 全部文档。

### 3.2 手动打包（Windows）

```powershell
cd cdc-pgtables-sync
mkdir dist\pglsync-offline\wheels
# --python-version 写目标机器的版本，--platform 按 CPU 架构选择
pip download "psycopg2-binary>=2.8.6,<2.10" --only-binary=:all: `
    --platform manylinux2014_x86_64 --python-version 3.6 --implementation cp `
    -d dist\pglsync-offline\wheels

# 复制程序文件
xcopy /E /I pglsync dist\pglsync-offline\pglsync
xcopy /E /I tools   dist\pglsync-offline\tools
xcopy /E /I tests   dist\pglsync-offline\tests
xcopy /E /I docs    dist\pglsync-offline\docs
mkdir dist\pglsync-offline\deploy
copy deploy\pglsync.service dist\pglsync-offline\deploy\
copy deploy\offline\install.sh dist\pglsync-offline\
copy config.example.json dist\pglsync-offline\
copy config.test.json dist\pglsync-offline\
copy requirements.txt dist\pglsync-offline\
```

然后把 `dist\pglsync-offline` 整个目录压缩，拷贝到目标机器。

> [!NOTE]
> 在 Windows 上编辑过的 `install.sh` 可能带有 CRLF 换行，在 Linux 上会报 `/bin/bash^M: bad interpreter`。仓库的 `.gitattributes` 已经强制使用 LF；如果还是遇到这个问题，在目标机器上执行 `sed -i 's/\r$//' install.sh`。

---

## 4. 在目标机器上安装

```bash
tar -xzf pglsync-offline-py3.6-manylinux2014_x86_64.tar.gz
cd pglsync-offline

# 使用系统 python3（2.1 节）
sudo PYTHON=python3 ./install.sh /opt/pglsync

# 或者使用独立版 Python（2.2 节），装到自己的目录下不需要 root
PYTHON=/opt/python3/bin/python3 ./install.sh $HOME/pglsync
```

安装脚本会：

1. 检查 Python 版本，必须是 3.6 及以上；如果误用了 2.7，会直接报错；
2. 把程序复制到安装目录；
3. 把 psycopg2 安装到 `<安装目录>/lib`，**不写入系统 site-packages，不影响系统里的其他 Python 程序**。目标机器没有 pip 也能装，会直接解压 wheel；
4. 生成启动脚本 `bin/pglsync` 和 `bin/load_writer`，里面已经写死了解释器路径和依赖路径；
5. 自检：导入 psycopg2、打印版本、运行单元测试。

成功时的输出类似这样：

```
>>> 使用解释器: /usr/bin/python3 (Python 3.6.8)
>>> 使用 pip 安装 psycopg2_binary-2.9.5-cp36-cp36m-manylinux_2_17_x86_64.manylinux2014_x86_64.whl
>>> 自检
psycopg2 2.9.5 (dt dec pq3 ext lo64)
pglsync 1.0.0
Ran 29 tests in 0.010s
OK

安装完成: /opt/pglsync
```

安装后的目录结构：

```
/opt/pglsync/
├── bin/pglsync          同步程序启动脚本
├── bin/load_writer      测试写入启动脚本
├── lib/                 psycopg2（私有依赖目录）
├── pglsync/  tools/  tests/  docs/  deploy/
├── config.example.json  config.test.json
└── logs/
```

---

## 5. 配置与运行

用法和在虚拟机上完全一样，只是把 `python3 -m pglsync` 换成 `/opt/pglsync/bin/pglsync`：

```bash
cd /opt/pglsync
cp config.example.json config.json      # 按目标环境修改连接信息、表清单
vi config.json

export PGLSYNC_SOURCE_PASSWORD='***'    # 生产环境密码用环境变量（配置里写 password_env）
export PGLSYNC_TARGET_PASSWORD='***'

bin/pglsync -c config.json check
bin/pglsync -c config.json setup
bin/pglsync -c config.json init --no-copy     # 或 init
bin/pglsync -c config.json run

bin/load_writer -c config.json --once --rows 100    # 测试写入
```

> [!NOTE]
> 配置文件里的 `logging.file` 如果写的是相对路径（比如 `logs/pglsync.log`），它是相对于**当前工作目录**的。建议先 `cd /opt/pglsync` 再运行，或者直接改成绝对路径。

### systemd 托管

把 [deploy/pglsync.service](../deploy/pglsync.service) 中的 `ExecStart` 和 `WorkingDirectory` 改成：

```ini
WorkingDirectory=/opt/pglsync
EnvironmentFile=/opt/pglsync/pglsync.env
ExecStart=/opt/pglsync/bin/pglsync -c /opt/pglsync/config.json run
```

```bash
sudo cp /opt/pglsync/deploy/pglsync.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now pglsync
```

### 以后更新程序

在有网的机器上重新制作离线包，拷贝过去后再执行一次 `install.sh`。已有的 `config.json`、`config.test.json` 和 `logs/` **不会被覆盖**。更新完重启服务即可。

---

## 6. 方案 B：改用 Java 版

如果目标机器有 Java 8 及以上（`java -version`），Java 版是最简单的选择：只需要**一个 jar 文件**，所有依赖都打包在里面，完全不需要 Python。

```bash
# 在有网的机器上构建（feature/java-springboot 分支）
cd pglsync-java && mvn -B clean package          # 生成 target/pglsync.jar

# 拷贝 pglsync.jar 和配置文件 application.yml 到目标机器，然后：
java -jar pglsync.jar check --spring.config.additional-location=file:/opt/pglsync-java/application.yml
```

没有 Java 的话，也可以离线放一个 JRE：下载 Eclipse Temurin 8 的 Linux `tar.gz`，解压到 `/opt/jre8`，然后用 `/opt/jre8/bin/java -jar pglsync.jar ...` 运行。它同样不影响系统环境。

详见 [pglsync-java/README.md](../pglsync-java/README.md)。

---

## 7. 方案 C：移植到 Python 2.7（不推荐）

只有在**完全不允许**往目标机器上放 Python 3 或 JRE 时才考虑。需要：

- 修改字符串/字节处理、时区、文件编码、交互输入等 Python 3 写法；
- 依赖固定为 psycopg2-binary 2.8.6（cp27mu wheel），之后不会再有任何修复。

工作量不大，但会留下一个长期无人维护的运行环境。确实需要的话，可以另开分支来做。

---

## 8. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `SyntaxError` 或 `ImportError: No module named pglsync` | 用 Python 2.7 运行了 | 用 `bin/pglsync` 启动，或者用 `python3 -m pglsync`，不要用 `python` |
| `install.sh` 报“需要 Python 3.6 及以上” | `PYTHON` 指向了 2.7 | `PYTHON=python3 ./install.sh` 或 `PYTHON=/opt/python3/bin/python3 ./install.sh` |
| 制作离线包时 `No matching distribution found for psycopg2-binary` | 版本号或平台写错了 | 检查 `--python-version` 是否和目标机器的 `python3 -V` 一致、平台是否和 `uname -m` 一致 |
| 自检报 `ImportError: ... cannot open shared object file` 或 `wrong ELF class` | wheel 和目标机器不匹配（Python 版本或 CPU 架构不对） | 按目标机器的实际版本和架构重新制作离线包 |
| 自检报 `GLIBC_2.17 not found` | glibc 太旧（CentOS 6 是 2.12） | psycopg2-binary 2.8.6 提供兼容老 glibc 的 manylinux1 包：`build_bundle.sh 3.6 manylinux1_x86_64`（还需要一个能在该系统上运行的 Python 3.6）。更省事的做法是改用方案 B（Java 版） |
| `pip: No module named pip` | 目标机器的 python3 没有 pip | 不影响安装，`install.sh` 会自动直接解压 wheel |
| `/bin/bash^M: bad interpreter` | 脚本被转成了 CRLF 换行 | `sed -i 's/\r$//' install.sh` |
| 装了 python3 之后 `yum` 不能用了 | 系统默认的 `python` 被改成了 3 | 恢复 `/usr/bin/python -> python2`。**不要修改系统默认 python** |
