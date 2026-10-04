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

5. **按实时吞吐分配**
   每片下载都回头更新通道的实测速度，并由这把「实时速度」决定下一个分片交给谁。
   旧实现只在开始时按一次测速结果分配、之后再不更新 ——
   一旦某条通道中途慢下来（被限流、线路抖动），它手里的块就成了拖死整体进度的尾巴。

6. **指数退避 + `Retry-After`（修掉被限流后越限越死）**
   Azure 单 Blob 的目标吞吐约 **60 MiB/s 或 500 请求/秒**，超了会返回 `503 ServerBusy`。
   旧实现是无脑重试，一被限流就雪上加霜。现在识别 `429` / `503`，
   按官方建议做**指数退避**并优先遵循服务端的 `Retry-After`，
   同时把这条通道的权重降下来，把活儿分给别人。

7. **单连接自动升级为分段**
   探测不到体积（很多接口不回 `Content-Length`）或源码包这类场景，
   先发一个带 `Range` 的小请求：**只要拿到 206 就说明支持 Range**，
   立刻切到多线程分段引擎重下。旧实现一律走单连接，白白浪费带宽。

8. **完整性校验**
   每片按实际收到字节数记账，缺的那段还回队列重取；全部写完再核对总大小；
   不一致就删掉重来并抛 `Incomplete`。

9. **进度节流 + 诚实的速度**
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
4. 如需更快：在「设置」里调大并发连接数后重新下载。

下载完成的文件默认保存到系统公共目录 **`Download/ArtifactBoost/`**（文件管理器可直接查看），也可在下载卡片上点「分享」发到别处。App 私有目录 `Android/data/.../files/Artifacts/` 仅作引擎多线程落盘的暂存。

---

## 免责声明

- 智能加速与自定义通道可能让产物数据流经第三方中转（**中转的只是已签名的产物地址，不涉及你的 Token**）。私有仓库会自动强制直连。
- 请仅下载你有权限访问的内容。
- 本项目与 GitHub 无关联。
