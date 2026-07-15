# FF 经济日历 · 安卓桌面小组件

桌面小组件，数据源为 ForexFactory 经济日历，只显示**本周**的**红色新闻（High）**和**假期（Holiday）**。

## 数据来源

官方每周导出端点（已自动限定本周）：

```
https://nfs.faireconomy.media/ff_calendar_thisweek.json
```

字段：`title / country / date(含时区偏移) / impact / forecast / previous`
`impact` 取值：`High`(红字) / `Holiday`(假期) / `Medium` / `Low`，本组件只保留 High 与 Holiday。

注意：ForexFactory 限制每 IP 每 5 分钟最多 2 次请求。组件设为**每小时自动刷新一次**，并带手动"刷新"按钮，避免被封。

### 国内网络兜底（镜像）
ForexFactory 的 CDN 在国内手机常被墙/超时。组件拉取逻辑为：**先试主源，失败自动改用 GitHub 镜像** `ff_data.json`（由 `.github/workflows/update-data.yml` 在每次 push 和每周一自动抓取提交）。若主源与镜像都失败，列表会显示「加载失败，请检查网络后点刷新」，而不是永远「加载中」。

列表在缓存为空时会**自己直接拉取一次**，不依赖后台定时任务，可绕过部分国产 ROM 对后台网络的限制。

## 显示规则

- 只显示本周（端点本身即本周数据，再加客户端按时间排序）
- 只显示 High（左侧红条）和 Holiday（左侧橙条）
- 时间固定显示**美国东部时间（EDT）**，与 ForexFactory 官网一致
- 每天以「周几 日期」分隔条分组，每天之间有间隔
- 标题显示「本周红色新闻与假期」
- 假期/银行假日在数据源中带时间戳，小组件里显示为「全天」
- 若今日（美东）有 CPI / NFP / FOMC 等重大新闻，组件顶部显示红色警告横幅「今日有重大新闻，禁止交易！！！」
- 国家代码显示为三字母：`US->USA`、`CA->CAD`，其余用标准 ISO alpha-3（FRA / GBR / DEU 等）
- 点击任意条目 -> 打开 forexfactory.com 日历页

## 构建与安装（一次性的事）

需要一台装了 Android Studio 的电脑。

1. 电脑安装 [Android Studio](https://developer.android.com/studio)（含 Android SDK）。
2. 打开本项目：`File -> Open -> 选择 ForexFactoryWidget 文件夹`。若提示缺少 Gradle wrapper，允许 Android Studio 自动创建即可。
3. 手机：设置 -> 关于手机 -> 连点"版本号"7 次开启开发者模式 -> 开启"USB 调试"，用数据线连电脑并允许授权。
4. Android Studio 顶部点绿色三角 `Run`（或 `Build -> Build APK(s)` 拿到 apk 后传到手机安装）。
5. 安装后**先点一次 App 图标打开它**（这一步会激活应用，否则小部件可能不出现在列表里）。App 里点"打开 ForexFactory 日历"按钮可跳官网。
6. 回到手机桌面长按空白处 -> "窗口小工具" -> 找到 **FF 经济日历** -> 拖到桌面。
7. 首次会显示"加载中"，联网后约几秒出现本周红字+假期列表。

## 自定义

- 改颜色：`app/src/main/res/values/colors.xml`（`high` 红、`holiday` 橙、`widget_bg` 背景等）。
- 改刷新频率：`app/src/main/java/com/ffwidget/app/FFWidgetProvider.kt` 的 `scheduleRefresh`（当前 1 小时）。
- 改尺寸：`app/src/main/res/xml/ff_widget_info.xml` 的 `minWidth/minHeight`。

## 目录结构

```
ForexFactoryWidget/
├── build.gradle.kts / settings.gradle.kts   # 工程配置
└── app/src/main/
    ├── AndroidManifest.xml
    ├── java/com/ffwidget/app/
    │   ├── EventModel.kt          # 数据模型
    │   ├── TimeUtils.kt           # 时区转换
    │   ├── FFRepository.kt        # 拉取+筛选+缓存
    │   ├── CalendarWorker.kt      # 后台定时拉取
    │   ├── FFWidgetProvider.kt    # 小组件主逻辑
    │   ├── EventWidgetService.kt  # 列表数据源
    │   └── EventRemoteViewsFactory.kt
    └── res/                       # 布局/颜色/字符串/小组件配置
```

## 实现细节

- 数据源 `date` 字段带 `-04:00` 这类时区偏移，为了兼容 Android 6（API 23），`TimeUtils` 先把冒号去掉再用 `Z` 格式解析，而非直接用 `X`/`XXX`。
- 所有时间格式化固定使用 `America/New_York`，与 ForexFactory 官网一致。
- 假期在 JSON 中仍带时间戳，渲染时替换为「全天」。

## 没有电脑编译？

如果你有 GitHub 账号，我可以再补一个 GitHub Actions 工作流：推送代码后在云端自动编译出 APK，你直接下载安装，无需本地 Android Studio。需要的话告诉我。

如果你有 GitHub 账号，我可以再补一个 GitHub Actions 工作流：推送代码后在云端自动编译出 APK，你直接下载安装，无需本地 Android Studio。需要的话告诉我。
