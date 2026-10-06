# ArtifactBoost（Android）

GitHub Actions 产物加速下载器。**多线程 + HTTP Range 分段并发 + 多通道并行**，把动手下载产物、构建日志、Release 附件、源码包这件事跑满带宽。

这是 [ArtifactBoost](https://github.com/yitenchen123/ArtifactBoost)（Swift / SwiftUI iOS 版）的 **Kotlin / Jetpack Compose 安卓移植版**，算法与行为 1:1 对齐。

---

## 功能

| 能力 | 说明 |
| --- | --- |
| **仓库列表** | 分页拉取当前 Token 可见的仓库，支持关键字过滤 |
| **仓库搜索** | 按关键字搜索 GitHub 仓库，可按最佳匹配 / 星标 / 更新时间排序；也可直接粘贴 `owner/repo` 打开 |
| **仓库详情** | 概览（README）/ 构建 / 正式版 / 源码 四个标签页 |
| **构建详情** | 构建日志 + 该次运行的全部产物，一键盘下载 |
| **正式版详情** | Release 附件 + 对应 tag 的源码包 |
| **下载管理** | 进度、实时速度、通道说明、取消 / 重试 / 完成后导出分享 |
| **前台服务** | 下载在通知栏持续运行，锁屏 / 切后台不中断 |

---

## 加速原理（核心算法，与 iOS 版一致）

1. **拦截 302 重定向**
   GitHub 的下载接口会 302 到签好名的 Azure Blob / codeload 地址，用 `followRedirects(false)` 的客户端读出 `Location` 头拿到真正的地址。

2. **探测是否支持分段**
   发一个 `Range: bytes=0-0` 探测请求：
   - 返回 `206` + `Content-Range` → 支持 Range，走分段并发；
   - 返回 `200` → 不支持，回退单连接下载。

3. **滑动窗口 + 分片续做（v2 重写，修掉「下到后面掉回几十 KB」）**
   旧实现在下载开始时把文件一次性切成 N 块、一块一个协程死磕到底。
   问题在于**最后阶段**：其它块都下完了，只剩一两个慢块还在爬，
   于是几乎所有连接都闲着，界面速度就从峰值跌到几十 KB。

   现在改成持久 worker 池 + 中央区间队列：

   - 每个 worker 只取**一小片**（`sliceTarget`，128KB ~ 4MB 动态调整），
     取完立刻接着取，绝不死磕一大块；
   - 池子空了、而还有连接闲着时，就从末尾那段区间**再砍一刀**（work stealing），
     空闲连接立刻有活干 —— 收尾阶段所有连接一起把剩下的数据吃完；
   - 剩余数据越多，单片越小（让所有连接都分得到收尾的活儿）；
   - 区间只记 `(start, end)`，砍分不需要重编号，写盘可以按偏移乱序并发。

4. **多 Session**
   `sessionCount = min(4, max(1, 并发数 / 8))`，每个 Session 一份独立的 OkHttp 连接池 —— 避免所有请求挤在同一条 HTTP/2 TCP 连接上被复用天花板限速。

5. **AIMD 自适应并发（v3 新增，修掉「128 并发被服务端限流」）**
   用户设的并发数从此只是**上限**而不是**目标值**：
   - 引擎内部维护一个 AIMD 窗口（起始 16，每成功一片加性增、命中 429/503 立刻乘性减半）；
   - 另有一个**每秒请求数速率闸**配合压 QPS —— 光管并发管不住 429，两者一起才压得住；
   - 于是实际并发会自己爬到「服务器愿意给的那个档位」（常见 24~64）然后稳在那里，
     而不是硬顶着 128 撞墙。

   这也是「设了 128 反而更稳」的原因：128 只是更高的天花板，不是 128 条连接一起冲。

6. **卡住看门狗（v3 新增，修掉「时不时卡住」）**
   CDN 的心跳字节会不断重置 OkHttp 的 `readTimeout`（idle 计时），连接就「看着在动、
   实际一动不动」地挂着。引擎改用**「有没有真的写进文件」**判定：12 秒没有任何字节落盘，
   就主动掐掉所有在飞 Call 并用新连接重新派活，同时把窗口砍一刀。

7. **失败分片退避重派（v3 新增，修掉「限流后疯狂撞墙」）**
   失败的区间以前插回队首，下一个空闲 worker 立刻又捞走重试 —— 服务器正在限流时，
   这就成了吞吐为零的死循环。现在失败区间带 `notBefore` 退避时间，
   在到点前对取活/切分都不可见，调度器自然会去干别的活儿。

8. **通道并发探测 + 5 分钟缓存（v3 新增，修掉「半天才开始」）**
   以前按设置里的固定顺序串行先用第一条通道，4 个镜像里前 3 个是死的就得白等三次超时。
   现在同时探测所有候选（各拉 32KB），可用的先上岗、死的立刻剔除，
   并把实测速度作为初始权重喂给引擎；同一 host 5 分钟内复用排序，第二次下载基本瞬发。

9. **按实时吞吐分配**
   每片下载都回头更新通道的实测速度，并由这把「实时速度」决定下一个分片交给谁。
   旧实现只在开始时按一次测速结果分配、之后再不更新 ——
   一旦某条通道中途慢下来（被限流、线路抖动），它手里的块就成了拖死整体进度的尾巴。

10. **指数退避 + `Retry-After`**
   Azure 单 Blob 的目标吞吐约 **60 MiB/s 或 500 请求/秒**，超了会返回 `503 ServerBusy`。
   旧实现是无脑重试，一被限流就雪上加霜。现在识别 `429` / `503`，
   按官方建议做**指数退避**并优先遵循服务端的 `Retry-After`，
   同时把这条通道的权重降下来，把活儿分给别人。

11. **单连接自动升级为分段**
   探测不到体积（很多接口不回 `Content-Length`）或源码包这类场景，
   先发一个带 `Range` 的小请求：**只要拿到 206 就说明支持 Range**，
   立刻切到多线程分段引擎重下。旧实现一律走单连接，白白浪费带宽。

12. **体积探测并发化（v3 新增）**
   `probeSize` 以前逐条通道串行试，第一条是死镜像就得白等 15s 超时。
   现在并发打所有通道（超时收紧到 8s），谁先给出确定答案就用谁的。

13. **签名地址解析提速（v3 新增）**
   `resolveDownloadUrl` 改用 **HEAD** 请求（失败自动退回 GET），
   配合 `followRedirects = false`，拿到 3xx 就立刻结束 ——
   产物正文动辄几百 MB，以前要把整个响应读完才拿到 `Location`。

14. **字节覆盖校验 + 缺口自动修复（v3 重做）**
   老实现的完整性校验只有一句 `written == total` —— 那只是**总量**校验。
   「重复写了一段 + 漏写了一段」的总量完全可能相等，最后文件大小对得上、
   内容却是错的 —— 症状正是「能下完但解压不了」。
   现在用 `WriteLedger` 记账：每次成功写盘登记区间，收尾核对
   「每个字节恰好被覆盖一次」。发现缺口就自动把缺的区间重新派下去补
   （最多 3 轮），能救回来的就不让用户重下几百 MB。

15. **Content-Range 严格校验（v3 新增）**
   有些镜像/CDN 对 Range 支持不完整（忽略部分区间、或从自己理解的偏移开始返回），
   返回码仍是 206。不校验 `Content-Range` 的话，取到的数据会被写到错误偏移上
   —— 下载"成功"、文件大小也对，但内容错了。现在起止偏移必须与请求完全一致，
   否则换线重试。

16. **单连接路径的截断校验（v3 新增）**
   单连接下载以前从不检查「到底下完了没有」：连接中途断流时读取循环会安静退出，
   文件被截断却照样报告「下载完成」。现在比对服务端声明的 `Content-Length`。

17. **超时全面收紧（v3 新增）**
   `readTimeout` 是 idle 计时，CDN 心跳字节会不断重置它 —— 以前设 120s 且
   `callTimeout = 0`（不设总时长上限），一条挂住的连接能占住一个 worker 两分钟。
   现在 `readTimeout` 收到 30s，**每个分片请求另设总时长上限**
   （按分片大小推算，20s ~ 80s），最坏情况只是丢掉这一片、换线重试。
   线程池也留出富余线程，避免「卡住被掐掉后新活儿排不进线程」的二次卡死。

18. **进度不虚涨**
   进度按「**新覆盖的字节**」推进，而不是「收到的字节」——
   重复写入不该让进度条虚涨（否则会出现进度 100% 但校验不过）。

19. **进度节流 + 诚实的速度**
   进度回调 250ms 一次，速度做 0.6 / 0.4 滑动平均；
   但**连续 3 拍零增长时会把平滑值往下压** ——
   否则界面会一直挂着峰值速度、而实际早就掉下去了。

10. **私有仓库强制直连**
    私有仓库的签名地址绝不交给第三方镜像。

---

## 下载通道（设置 → 下载源，一点即切换并保存）

- **官方源**：直接连 GitHub 存储，最安全，但国内通常很慢。
- **镜像加速**：直连与内置公共镜像（gh-proxy.com / slink.ltd / hk.gh-proxy.com / moeyy.xyz）多通道并行，带宽叠加。
- **自建中转**：打开开关后填你自己搭建的中转前缀（Cloudflare Worker / 反向代理）；前缀为空时回退直连。

> 说明：镜像加速与自建中转会让产物数据经过第三方中转（**只中转已签名的产物地址，不接触你的 Token**）。私有仓库始终强制直连。

---

## 从 iOS 版到安卓的对应关系

| iOS | Android |
| --- | --- |
| SwiftUI View | Jetpack Compose Composable |
| `URLSession` / `URLSessionConfiguration` | OkHttp `OkHttpClient` + 连接池 |
| `URLSession.shared.data(for:)` | `suspend fun` + `Dispatchers.IO` |
| `Keychain` | `EncryptedSharedPreferences`（Keystore 不可用时降级明文，避免崩溃） |
| `UserDefaults` | `SharedPreferences` |
| `NavigationStack` | `androidx.navigation:navigation-compose` |
| `AsyncImage` | Coil `AsyncImage` / `SubcomposeAsyncImage` |
| `ShareLink` | `FileProvider` + `Intent.ACTION_SEND` |
| `UIBackgroundTask` | 前台服务（`foregroundServiceType="dataSync"`） |
| `@Published` / `ObservableObject` | `StateFlow` + `collectAsStateWithLifecycle` |
| Swift `Codable` | `kotlinx.serialization` |
| `AttributedString` Markdown | Compose `AnnotatedString` + 手写 `MarkdownParser` |

---

## 项目结构

```
app/src/main/java/com/artifactboost/app/
├─ ArtifactBoostApp.kt        # Application：持有 session / downloads
├─ MainActivity.kt            # 单 Activity，按登录态切换界面
├─ data/
│  ├─ GitHubModels.kt         # @Serializable 数据模型
│  ├─ GitHubClient.kt         # REST 调用 + 302 解析（resolveDownloadUrl）
│  ├─ DownloadRoute.kt        # 通道定义 / 加速设置
│  ├─ DownloadItem.kt         # 可下载项与来源类型
│  ├─ TokenStore.kt           # 加密存储 Token
│  └─ SessionManager.kt       # 登录态
├─ download/
│  ├─ DownloadEngine.kt       # ★ 分段并发下载核心
│  ├─ AdaptiveLimiter.kt      # ★ AIMD 自适应并发窗口 + 速率闸
│  ├─ RouteProbe.kt           # ★ 通道并发探测 + 5 分钟缓存
│  ├─ DownloadManager.kt      # 任务调度 / 通道选择 / 重试
│  └─ DownloadService.kt      # 前台服务 + 通知进度
├─ ui/
│  ├─ theme/Theme.kt          # GitHub Primer 配色（浅色/深色）
│  ├─ components/             # 通用组件（卡片 / 胶囊 / 骨架屏 …）
│  └─ screens/                # 各页面 + Markdown 渲染
└─ util/Formatters.kt         # 大小 / 速度 / 相对时间 / ISO8601
```

---

## 构建

### 环境要求

- JDK 17
- Android SDK：platform 35、build-tools 35.0.0
- Gradle 8.9（仓库自带 wrapper）

### 命令行

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk

./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

./gradlew :app:assembleRelease   # 需要配置签名
```

> 国内构建时仓库已默认指向阿里云镜像（`settings.gradle.kts`），Gradle 发行版走腾讯云镜像。

### GitHub Actions

仓库内置 `.github/workflows/build.yml`，push / PR 时自动产出 debug APK，也可以在 Actions 页手动触发。

---

## 使用

1. 打开 app，粘贴一个 GitHub Personal Access Token。
   - 只读公开仓库：**无需任何 scope**；
   - 需要看私有仓库 / Actions 产物：勾选 `repo` + `actions:read`。
   - 快速创建：https://github.com/settings/tokens/new?scopes=repo,workflow&description=ArtifactBoost
2. Token 只保存在本机（加密存储），所有请求直连 `api.github.com`。
3. 进「仓库」选一个仓库 → 切到「构建 / 正式版 / 源码」→ 点下载。
4. 如需更快：在「设置」里调大并发**上限**后重新下载。
   注意这是上限而不是固定连接数 —— 引擎用 AIMD 自适应算法自己决定实际开多少条连接
   （服务器撑得住才往上爬，一遇限流立刻砍半）。一般 16~32 就够，自建中转可以拉到 64~128。

下载完成的文件默认保存到系统公共目录 **`Download/ArtifactBoost/`**（文件管理器可直接查看），也可在下载卡片上点「分享」发到别处。App 私有目录 `Android/data/.../files/Artifacts/` 仅作引擎多线程落盘的暂存。

---

## 免责声明

- 智能加速与自定义通道可能让产物数据流经第三方中转（**中转的只是已签名的产物地址，不涉及你的 Token**）。私有仓库会自动强制直连。
- 请仅下载你有权限访问的内容。
- 本项目与 GitHub 无关联。
