# YouTube Music Data AAR 使用说明

本说明对应构建产物 `youtube-music-data-release.aar`；部署到 Meloora 时将其重命名为 `meloora-music-data.aar`。

- Kotlin API 包名：`com.music.player.yt.data`
- Android namespace：`com.music.player.yt.data`
- 最低 Android 版本：`minSdk 26`
- 数据范围：匿名公开目录数据与短期播放流解析
- 不包含：Media3、播放控制、下载、登录、用户歌单、Cookie 持久化或本地缓存

## 1. 复制 AAR

运行 `buildReleaseArtifacts` 后，使用根目录已生成的 `meloora-music-data.aar`，放入宿主消费者模块的 `libs/` 目录：

```text
app/
  libs/
    meloora-music-data.aar
```

本地 AAR 不携带 Maven POM，因此运行时依赖必须由宿主显式声明。

## 2. 配置 Manifest

网络请求由宿主应用发出，请在宿主 `AndroidManifest.xml` 添加网络权限：

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />

    <application />
</manifest>
```

消费者模块的 `minSdk` 必须不低于 26。

## 3. 添加依赖

确认项目仓库包含 `mavenCentral()` 或兼容的企业镜像。

Kotlin DSL，`app/build.gradle.kts`：

```kotlin
dependencies {
    implementation(files("libs/meloora-music-data.aar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
```

Groovy DSL，`app/build.gradle`：

```groovy
dependencies {
    implementation files('libs/meloora-music-data.aar')
    implementation 'org.jetbrains.kotlin:kotlin-stdlib:2.2.21'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    implementation 'org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3'
}
```

不要使用 `flatDir`。如果宿主已有更高版本的同一依赖，先通过 Gradle 依赖树和编译验证其兼容性，避免重复声明。

如需在宿主中播放 `resolveStream` 返回的资源，另行添加宿主自己的 Media3 依赖。Media3 不是 AAR 的依赖：

```kotlin
implementation("androidx.media3:media3-common:<host-version>")
implementation("androidx.media3:media3-datasource:<host-version>")
```

## 4. 启动时刷新客户端版本

`YoutubeMusicDataClient` 的内置客户端版本是兜底值。宿主可保存上一次成功的 `YoutubeClientVersions`，在创建 client 时传入；每次 App 启动后由宿主主动调用 `YoutubeVersionHelper` 刷新。AAR 不会自行联网刷新或保存版本。

```kotlin
import com.music.player.yt.data.YoutubeClientVersions
import com.music.player.yt.data.YoutubeMusicDataClient
import com.music.player.yt.data.YoutubeVersionHelper
import com.music.player.yt.data.YoutubeVersionResult

val client = YoutubeMusicDataClient(
    initialClientVersions = loadSavedClientVersions(), // 没有保存值时传 null
)
val versionHelper = YoutubeVersionHelper()

suspend fun refreshYoutubeClientVersionsAtStartup() {
    when (val result = versionHelper.fetchCurrentVersion()) {
        is YoutubeVersionResult.Success -> {
            client.updateClientVersions(result.versions)
            saveClientVersions(result.versions)
        }
        is YoutubeVersionResult.NetworkError,
        is YoutubeVersionResult.UpstreamError,
        is YoutubeVersionResult.ParseError -> {
            // 保留 client 当前版本；不要用失败结果覆盖已保存版本。
        }
    }
}
```

`webRemixVersion` 用于目录数据请求，`androidVersion` 用于播放流解析。缺失字段会使用 AAR 内置默认值。`updateClientVersions` 只影响当前 client 实例，调用方应把完整版本快照传入。

## 5. 创建与保存 Session

所有调用都必须在协程或 `suspend` 函数中执行。客户端不保存任何账号或 session 状态；宿主负责保存每次成功响应中的 `updatedSession`。

```kotlin
import com.music.player.yt.data.YoutubeDataResult
import com.music.player.yt.data.YoutubeMusicDataClient
import com.music.player.yt.data.YoutubeMusicSession

suspend fun loadHome() {
    val client = YoutubeMusicDataClient()

    val session = when (val bootstrap = client.createAnonymousSession()) {
        is YoutubeDataResult.Success -> bootstrap.updatedSession
        is YoutubeDataResult.NetworkError -> throw bootstrap.cause
        is YoutubeDataResult.ParseError -> error(bootstrap.message)
        is YoutubeDataResult.UpstreamError -> error("YouTube HTTP ${bootstrap.statusCode}")
        is YoutubeDataResult.Unavailable -> error(bootstrap.reason ?: "YouTube data is unavailable")
    }

    when (val home = client.home(session)) {
        is YoutubeDataResult.Success -> {
            val sections = home.data.items
            val refreshedSession: YoutubeMusicSession = home.updatedSession
            // 将 refreshedSession 保存到宿主自己的状态存储。
        }
        is YoutubeDataResult.NetworkError -> Unit
        is YoutubeDataResult.ParseError -> Unit
        is YoutubeDataResult.UpstreamError -> Unit
        is YoutubeDataResult.Unavailable -> Unit
    }
}
```

`YoutubeMusicSession` 只包含 `visitorData`。不同用户、不同宿主或不同测试用例各自保存自己的 session 即可，client 之间不会共享 session。

当同一个 session 可能被多个协程同时刷新时，由宿主通过 `Mutex`、串行队列或状态容器保护“读取旧 session、发起请求、写回 updatedSession”这一段操作，避免后返回的旧请求覆盖新 session。

## 6. 调用数据 API

```kotlin
val client = YoutubeMusicDataClient()

client.home(session)
client.browse(session, endpoint = BrowseEndpoint("...", params = "..."))
client.moodAndGenres(session)
client.fetchCuratedSongsPage(session, moodAndGenresItem, cursor = null, limit = 10)
client.fetchCuratedSongs(session, moodAndGenresItem)
client.search(session, query = "Daft Punk")
client.searchSuggestions(session, query = "Daft")
client.track(session, videoId = "...")
client.album(session, browseId = "...")
client.playlist(session, playlistId = "...")
client.artist(session, browseId = "...")
client.lyrics(session, videoId = "...")
client.syncedLyrics(
    session,
    videoId = "...",
    preferredLanguageCodes = listOf("zh-Hans", "en"),
)
client.resolveLyrics(
    session = session,
    videoId = "...",
    title = "Song title",
    artist = "Artist",
    durationMs = 180_000,
    preferredLanguageCodes = listOf("zh-Hans", "en"),
)
client.related(session, videoId = "...")
```

### 同步歌词

`syncedLyrics` 返回 `SyncedLyrics(lines, languageCode)`；每个 `SyncedLyricLine` 包含毫秒级 `startTimeMs` 和已合并、去除首尾空白的 `text`。歌词行按开始时间稳定升序排列，相同时间的行保留字幕源中的原始顺序。

字幕轨依次按“偏好语言人工字幕、偏好语言自动字幕、任意人工字幕、第一条可用字幕”选择。偏好语言按调用方列表顺序、忽略大小写进行完整语言码匹配，不会把 `zh` 自动匹配到 `zh-Hans`。成功结果的 `updatedSession` 来自 `player` 响应，宿主应像其他数据 API 一样保存它。

没有可用字幕轨或字幕没有有效歌词行时返回带明确消息的 `ParseError`；请求失败仍使用 `NetworkError` 或 `UpstreamError`，协程取消继续抛出 `CancellationException`。普通 `lyrics` 继续返回无时间轴的文本歌词，行为不变。

### 多来源歌词回退

`resolveLyrics` 将多个来源统一为 `ResolvedLyrics(text, syncedLines, source)`。`syncedLines` 中的 `SyncedLyricLine.startTimeMs` 是真实毫秒时间轴；只有普通文本时该字段为 `null`，AAR 不会生成时间为 0 的伪同步歌词。`source` 为 `YOUTUBE_CAPTIONS`、`LRCLIB`、`KUGOU` 或 `YOUTUBE_OFFICIAL`。

包含中文标题或歌手时，来源顺序为酷狗、YouTube 字幕、LRCLIB、YouTube 官方歌词；其他歌曲依次使用 YouTube 字幕、LRCLIB、酷狗、YouTube 官方歌词。LRCLIB 与酷狗会先用规范化名称搜索，确实无结果且名称发生变化时再用原始名称重试。YouTube 来源只使用 `videoId`，不会重复相同请求。

单个来源无结果或失败会继续尝试后续来源。全部失败后优先返回已遇到的 `NetworkError`，其次为 `UpstreamError`、`ParseError`，若所有来源均明确无结果则返回 `Unavailable`。协程取消始终继续抛出。成功结果中的 `updatedSession` 必须由宿主持久化。

AAR 不保存歌词缓存或“未找到”标记，也不依赖 Android `Context`。宿主负责缓存 `ResolvedLyrics`、决定缓存有效期，以及将普通文本转换为自身 UI 所需的展示行。

### 专辑歌曲兜底

`album` 会先加载专辑详情。若详情页没有歌曲，但 canonical URL 提供了 `album.playlistId`，AAR 会自动使用该 ID 加载首个 `VL<playlistId>` 歌曲页，并将歌曲、`nextPageToken` 与该请求的 `updatedSession` 组装到同一个 `AlbumPage` 成功结果中。详情页已有歌曲时不会增加请求；没有 playlist ID 时仍返回原有的空歌曲列表语义。

此兜底只加载首个歌曲页。宿主收到非空 `nextPageToken` 后，继续使用 fallback 成功结果的 `updatedSession` 和 `album.playlistId` 调用 `albumSongs` 加载后续页。兜底请求发生网络、上游 HTTP 或解析错误时，`album` 会直接返回该失败结果，宿主应按可重试错误处理，不能将其显示为“没有歌曲”。

### 情绪分类与策展歌曲

`moodAndGenres` 使用 YouTube Music 的 `FEmusic_moods_and_genres` Browse 页面返回实时分类；分类名称、色带颜色和请求 endpoint 都由上游提供。需要分批加载时，将返回项传给 `fetchCuratedSongsPage`。它先返回分类页直出的歌曲，再依次展开歌单和专辑；默认每批最多 10 首，重复的 `Track.id` 不占额度。

`CuratedSongsCursor` 是公开但内部状态不透明的内存游标，只能原样传回同一分类的 `fetchCuratedSongsPage`，不支持序列化或跨进程恢复。每批成功后应同时保存 `updatedSession` 和 `nextCursor`；`nextCursor == null` 表示已经到达最后一批。`limit` 必须大于 0。

```kotlin
var latestSession = moods.updatedSession
var cursor: CuratedSongsCursor? = null

do {
    when (
        val page = client.fetchCuratedSongsPage(
            session = latestSession,
            item = mood,
            cursor = cursor,
            limit = 10,
        )
    ) {
        is YoutubeDataResult.Success -> {
            appendTracks(page.data.tracks)
            latestSession = page.updatedSession
            cursor = page.data.nextCursor
        }
        else -> {
            // 保留本次调用前的 session 和 cursor，按宿主策略重试。
            return
        }
    }
} while (cursor != null)
```

分页调用在拿够 `limit` 后立即停止后续网络请求。如果展开集合时失败，本批返回对应错误且不会推进传入游标；调用方可以使用调用前的 session 和原游标重试，不会漏歌。

原 `fetchCuratedSongs` 继续保留“返回全部可解析歌曲”的契约，会展开所有集合分页并按歌曲 ID 去重；单个集合失败时仍跳过该集合并继续后续集合，适合需要一次性加载完整结果的现有调用方。

```kotlin
when (val moods = client.moodAndGenres(session)) {
    is YoutubeDataResult.Success -> {
        val mood = moods.data.firstOrNull()?.items?.firstOrNull() ?: return
        when (val songs = client.fetchCuratedSongs(moods.updatedSession, mood)) {
            is YoutubeDataResult.Success -> {
                val track = songs.data.firstOrNull() ?: return
                val latestSession = songs.updatedSession
                // 需要队列元数据时：client.track(latestSession, track.id)
                // 直接播放时：client.resolveStream(latestSession, track.id)
            }
            else -> Unit
        }
    }
    else -> Unit
}
```

每次成功响应后都要保存 `updatedSession`。AAR 不保存所选分类或策展歌曲，也不会预取或缓存结果。

搜索筛选使用 `SearchFilter`：

```kotlin
client.search(
    session = session,
    query = "Daft Punk",
    filter = SearchFilter.ALBUMS,
)
```

`home()` 的 `HomeSection.items` 可以包含 `Artist`，宿主可用 `filterIsInstance<Artist>()` 获取分区中的歌手。歌手分区可能出现在续页，也可能因上游推荐未下发；应保留分区标题和顺序，不将列表顺序当作官方排名。`home()` / `browse()` 在没有可展示内容且上游明确返回 `MUSIC_UNAVAILABLE` 时返回 `Unavailable`，普通空页面仍返回成功空列表。

分页 token 是不透明值，必须原样交回同一类 API：

```kotlin
when (val page = client.search(session, query = "Daft Punk")) {
    is YoutubeDataResult.Success -> {
        val next = page.data.nextPageToken
        if (next != null) {
            val nextPage = client.search(
                session = page.updatedSession,
                query = "Daft Punk",
                continuationToken = next,
            )
        }
    }
    else -> Unit
}
```

专辑、歌单曲目分页：

```kotlin
client.albumSongs(session, playlistId = "OLAK5uy_...", continuationToken = token)
client.playlistSongs(session, playlistId = "PL...", continuationToken = token)
```

首页分区的 `endpoint` 可为 `null`。存在时，可将其原样交给 `browse` 加载“更多”页面；响应中的分页 token 仍只应传回同一 endpoint：

```kotlin
when (val home = client.home(session)) {
    is YoutubeDataResult.Success -> {
        val section = home.data.items.firstOrNull() ?: return
        val endpoint = section.endpoint ?: return
        when (val firstPage = client.browse(home.updatedSession, endpoint)) {
            is YoutubeDataResult.Success -> {
                val next = firstPage.data.nextPageToken
                if (next != null) {
                    client.browse(firstPage.updatedSession, endpoint, continuationToken = next)
                }
            }
            else -> Unit
        }
    }
    else -> Unit
}
```

### 封面 URL 尺寸

`CatalogItem.thumbnailUrl`（以及 `Track.thumbnail`、`Album.thumbnail`、`Playlist.thumbnail`）始终保留 AAR 从上游响应解析出的原始 URL，不会自动改写尺寸。当前响应中常见的 `w544-h544` 是该 URL 的服务端请求尺寸，而不是 AAR 的固定图片尺寸。

需要在播放页或媒体通知中请求更高分辨率封面时，可显式生成一个播放专用 URL；列表和目录继续使用原始 URL：

```kotlin
import com.music.player.yt.data.resizedArtworkUrl

val listArtworkUrl = track.thumbnailUrl
val playbackArtworkUrl = listArtworkUrl?.resizedArtworkUrl(1_200)
```

`resizedArtworkUrl(size)` 仅处理 `yt3.googleusercontent.com` 或 `lh3.googleusercontent.com`，且 URL 已声明 `=w<宽>-h<高>` 尺寸参数的图片。它只替换这两个数值，保留其余路径后缀和 query 参数；其他域名或未声明该尺寸参数的 URL 原样返回。`size` 必须大于 0，否则抛出 `IllegalArgumentException`。该函数只改写 URL，不发起下载请求，也不保证上游服务器一定提供指定尺寸。

## 7. 获取 Media3 可播放的短期 URL

`resolveStream` 根据任意 YouTube `videoId` 返回可播放的媒体 `StreamInfo`，其中包含短期 URL、MIME 类型、格式信息、过期时间与请求头。它不创建播放器，也不持有 Media3 类型。返回值可以是独立音频流，也可以是含音轨的视频流，这与 SonicTube 的播放选择一致。

```kotlin
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import com.music.player.yt.data.StreamQuality
import com.music.player.yt.data.YoutubeDataResult

when (
    val result = client.resolveStream(
        session = session,
        videoId = track.id,
        quality = StreamQuality.AUTO,
        isNetworkMetered = isNetworkMetered,
    )
) {
    is YoutubeDataResult.Success -> {
        val stream = result.data
        val refreshedSession = result.updatedSession

        val mediaRequestHeaders = stream.requestHeaders.toMutableMap().apply {
            remove("X-User-Agent")?.let { put("User-Agent", it) }
        }
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mediaRequestHeaders)
        val mediaItem = MediaItem.Builder()
            .setUri(stream.url)
            .setMimeType(stream.mimeType)
            .build()

        // 保存 refreshedSession，再将 dataSourceFactory 和 mediaItem 交给宿主已有的 Media3 播放器。
    }
    is YoutubeDataResult.Unavailable -> showUnavailable(result.reason)
    is YoutubeDataResult.NetworkError -> showRetryUi(result.cause)
    is YoutubeDataResult.UpstreamError -> showServiceError(result.statusCode)
    is YoutubeDataResult.ParseError -> reportProtocolChange(result.message, result.cause)
}
```

- URL 是短期资源；在 `expiresAtEpochMillis` 到期前重新解析，或在播放得到 403/410 时用最新 session 再调用一次 `resolveStream`。
- `StreamInfo.requestHeaders` 使用 `X-User-Agent` 保存 SonicTube 兼容的媒体 UA。实际发出媒体请求前，宿主必须移除该键并以相同值设置标准 `User-Agent`；不能将 `X-User-Agent` 原样发送给流媒体服务。
- `StreamQuality.AUTO` 优先选择含音轨的视频资源，缺失时按 `isNetworkMetered` 选择音频；`DATA_SAVER` 优先低码率音频，缺失时回退低码率视频；`HIGH` 优先最高分辨率视频，缺失时回退高码率音频。
- 已配置 `YoutubeStreamOptions(pipedBaseUrl = "https://...")` 时，解析器在 TVHTML5 路径使用匹配的 Piped 音频 URL；默认不访问 Piped。端点由宿主负责选择、合规审查与可用性监控。

## 8. 处理结果与错误

每个 API 返回 `YoutubeDataResult<T>`：

```kotlin
when (val result = client.track(session, videoId)) {
    is YoutubeDataResult.Success -> render(result.data.track)
    is YoutubeDataResult.NetworkError -> showRetryUi(result.cause)
    is YoutubeDataResult.UpstreamError -> showServiceError(result.statusCode)
    is YoutubeDataResult.ParseError -> reportProtocolChange(result.message, result.cause)
    is YoutubeDataResult.Unavailable -> showUnavailable(result.reason)
}
```

- `Success`：读取 `data`，并用 `updatedSession` 替换调用前的 session。
- `NetworkError`：网络 I/O 异常，可按宿主策略重试。
- `UpstreamError`：InnerTube 返回非 2xx 状态，依据 `statusCode` 处理限流或服务端失败。
- `ParseError`：上游响应结构或 JSON 不符合当前解析规则，应记录错误并升级 AAR。
- `Unavailable`：响应有效，但请求内容或播放流不可用；包括首页/浏览页明确返回地区不可用，以及播放流受区域、版权、年龄或上游限制。
- `CancellationException`：会原样抛出，协程取消时不要捕获后转换成业务错误。

## 9. Release 与混淆

当前 AAR 本身未执行 R8 混淆。建议在宿主 App 的 release 构建中统一启用 R8，让 AAR、宿主代码和依赖一起完成裁剪与混淆：

```kotlin
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
        }
    }
}
```

统一由宿主混淆能根据最终调用关系裁剪代码，也只需维护宿主 release 输出的 `mapping.txt`。不要同时对同一个库做独立 R8 和宿主 R8，除非有单独的分发保护需求和完整的 consumer rules 验证。

## 10. 重新构建 AAR

生成两个根目录交付文件：

```powershell
.\gradlew.bat buildReleaseArtifacts
```

生成：`youtube-music-data-release.aar` 与字节内容相同的 `meloora-music-data.aar`。部署到 Meloora 时，将后者复制为 `app/libs/meloora-music-data.aar`，并使用前述文件依赖。

本次多来源歌词回退版本的三个构建/交付产物字节一致，SHA-256 为：

```text
4BDC23C11F6D908A030F3DA3FE866F1022130A701618BEEAA2B69C7AB554D8B3
```

该值不同于宿主当前 AAR 的旧校验值时，不要自动覆盖 `app/libs/meloora-music-data.aar`；由用户手动替换后，先通过公开 API 编译探针再开始宿主业务改造。

如需只调整 Android namespace：

```powershell
.\gradlew.bat buildReleaseArtifacts "-PytDataNamespace=com.example.generated.data"
```

这不会修改 Kotlin API 包名，代码中的 import 始终是 `com.music.player.yt.data`。
