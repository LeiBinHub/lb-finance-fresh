# 美股简报

多源采集，精确去重，同一只股票的新闻过多时只留信息量最大的几条，再交给 DeepSeek 写成固定四段简报，推到微信和钉钉。

简报按北京时间的日期写在 `data/briefs/`。启动后打开 http://127.0.0.1:8092/ ，可以点「获取当前时报」拉此刻最新数据，也可以按日期打开以前的简报。不再自动跑盘前和复盘推送。

## 需要你去申请并填进 .env

复制 `.env.example` 为 `.env`。空着的项不会进仓库。

| 变量 | 是否必须 | 去哪里申请 |
| --- | --- | --- |
| `DEEPSEEK_API_KEY` | 必须 | https://platform.deepseek.com/ |
| `FINNHUB_API_KEY` | 新闻、财报日历、常规报价。免费档没有公司情绪分和经济日历，这两项会空着，新闻照常拉 | https://finnhub.io/register |
| `FRED_API_KEY` | 10 年、2 年美债。请求头用 curl 的名字，否则对方不回包 | https://fred.stlouisfed.org/docs/api/api_key.html |
| `ALPHAVANTAGE_API_KEY` | 新闻，以及 FRED 失败时的国债收益率备用。只有一把 Key。免费档大约每天 25 次 | https://www.alphavantage.co/support/#api-key |
| `ALPACA_API_KEY_ID` 和 `ALPACA_API_SECRET_KEY` | 可留空。行情账号的两把密钥，请求头发给 `data.alpaca.markets`，用来拉报价和新闻。盘前/盘中/盘后走 `ALPACA_FEED`（默认 iex），夜盘再拉 `feed=overnight`，按最新成交或报价拼成此刻价格。免费档夜盘报价实时、成交大约晚 15 分钟。不是 Alpha Vantage 的单密钥，也不是 Broker 的 OAuth | https://alpaca.markets/ |
| `DINGTALK_WEBHOOK` 和 `DINGTALK_SECRET` | 钉钉。机器人开了加签才填 SECRET | 钉钉群 → 智能群助手 → 自定义机器人 |
| `WECOM_WEBHOOK` | 企业微信群机器人，消息进微信 | 企业微信群 → 群机器人 |
| `PUSHPLUS_TOKEN` | 个人微信 | https://www.pushplus.plus/ |
| `TELEGRAM_API_ID` 和 `TELEGRAM_API_HASH` | Telegram。公开页每 2 秒拉一次；关掉网页预览的频道要用 Telethon 用户会话。手机 App 已登录不够，需本机执行 `docker compose run --rm -it telegram-listen` 输入手机号和验证码，生成 `data/telegram.session` | https://my.telegram.org |

三个推送填一个就会发，内容就是页面上这份简报。企业微信单条大约 4000 字节，超出会截断，完整内容仍在页面和 `data/briefs/`。都空着时，简报只落在 `data/briefs/`。

Redis 不用申请。推荐用下面的 `docker compose`：应用和 Redis 都在 OrbStack 里跑，`8092` / `6379` 只映射到本机回环。若仍用 IntelliJ 启动应用，`.env` 里保持：

```
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
```

## 用 OrbStack 运行（推荐）

本机已装 OrbStack（`docker context` 显示 `orbstack *`）后，在本文件夹先打 jar，再起容器：

```bash
cd lb-finance-fresh
cp .env.example .env   # 已有 .env 可跳过，把密钥填好
mvn -q -DskipTests package
docker compose up -d --build
```

浏览器打开 http://127.0.0.1:8092/ 。简报和监听都在容器里跑；`data/` 挂到本机目录，重启不丢。密钥只读本机 `.env`，不会打进镜像。改代码后重复 `mvn package` 再 `docker compose up -d --build`。

容器里的 Redis 不占用本机 6379（避免和已有 Redis 抢端口）。应用通过 compose 网络访问服务名 `redis`。若仍用 IDE 启动应用，`.env` 继续连 `127.0.0.1:6379` 上已有的那台。不要同时用 IDE 和容器占 8092。

若 Docker Hub 拉镜像超时（国内常见），用镜像站再构建：

```bash
JAVA_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:21-jre \
REDIS_IMAGE=docker.m.daocloud.io/library/redis:7-alpine \
docker compose up -d --build
```

页面上的「请求日志」会记下容器里最近的失败（FRED 维护、日历更新失败等），写在 `data/request-log.json`，重启还能看。完整堆栈仍用：

```bash
docker compose logs -f app
docker compose restart app
docker compose down          # 停掉应用和 Redis；数据卷还在
docker compose down -v       # 连 Redis 卷一起删
```

不要同时再用 IDE 占 8092；容器起来后页面和监听都走 OrbStack。

## 本机 jar 运行

工作目录必须是本文件夹，这样 `.env` 和 `data/` 才找得到。

```bash
cd lb-finance-fresh
cp .env.example .env
mvn -q -DskipTests package
java -jar target/finance-fresh.jar
```

入口类是 `com.lb.financefresh.FinanceFreshApplication`。默认只监听本机 `8092`。

看还缺哪一项：

```bash
curl http://127.0.0.1:8092/api/status
```

不等到点，打开 http://127.0.0.1:8092/ ，点「获取当前时报」会拉此刻最新数据并推送。日期用来打开已保存的简报。也可以用命令：

```bash
curl -X POST http://127.0.0.1:8092/api/briefs/now
curl "http://127.0.0.1:8092/api/archive?date=2026-10-16"
```

## 简报三段

1. 宏观与流动性。10Y / 2Y 和五项近期形势都由程序按 FRED 算出，模型不改这些数字。
2. 自选股异动归因里每只股票一张表：盘前、盘中、盘后、夜盘。缺的写暂无。超过 1% 的腿会附情绪分和一句话原因。
3. 过去 24 小时里归纳出的三条主线。

财经日历在美股简报菜单底部。中英对照保存在 `data/calendar.sqlite`。Redis 里只有一个 64 小时过期的标记：没过期就读本地库，过期后才重新请求 FRED 和 DeepSeek。页面默认显示从今天起的 5 天，可以用开始和结束日期筛选。

美联储官方讯息悬停可看中文对照和专业解读。同一篇文章只交给 DeepSeek 翻译解读一次，结果保存在 `data/fed-articles.sqlite`，下次直接读本地。

媒体文章在请求日志上方。每次抓取简报后，会再用 DeepSeek **思考模式**写中英文两份可发布财经博文，既专业也抓眼球，保存在 `data/media/`。简报正文仍用普通对话 + JSON；媒体文章单独开 `thinking.enabled` 和 `reasoning_effort`。可用 `DEEPSEEK_THINK_MODEL`、`DEEPSEEK_REASONING_EFFORT` 覆盖。

自选股在 `application.yml` 的 `fresh.watchlist`，也可以用环境变量 `WATCHLIST=NVDA,AAPL` 覆盖。原油、黄金、恐慌指数分别用 USO、GLD、VIX：前两个走 Alpaca 股票快照，VIX 是指数，用 Finnhub 常规报价。

## 去重

完全相同的链接，或去掉标点和大小写后的标题，以 SHA-256 写入 Redis，72 小时内不再推。

同一只股票在这一轮里超过 5 条时，按发布时间、情绪绝对值、正文长度排序，只把前 3 条送给模型。情绪来自 Finnhub 的公司新闻分数；单篇没有分数时记 0，排序就退回到时间和正文长度。
