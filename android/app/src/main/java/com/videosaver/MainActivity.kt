package com.videosaver

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

// ─── 配色 ───
object AppColors {
    val Primary = Color(0xFF6C5CE7)
    val PrimaryDark = Color(0xFF5A4BD1)
    val GradientStart = Color(0xFF6366F1)
    val GradientEnd = Color(0xFFA855F7)
    val Surface = Color(0xFFF8F7FC)
    val CardBg = Color.White
    val TextPrimary = Color(0xFF1A1A2E)
    val TextSecondary = Color(0xFF6B7280)
    val Success = Color(0xFF10B981)
    val Error = Color(0xFFEF4444)
}

// ─── API 响应模型 ───
data class ParseResult(
    val ok: Boolean,
    val title: String = "",
    val author: String = "",
    val coverUrl: String = "",
    val videoUrl: String = "",
    val duration: Double? = null,
    val ext: String = "mp4",
    val platform: String = "",
    val videoHeight: Int? = null
)

// ─── API 客户端 ───
class ApiClient(private val baseUrl: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun parse(url: String): ParseResult = withContext(Dispatchers.IO) {
        val body = JSONObject().put("url", url).toString()
        val req = Request.Builder()
            .url("$baseUrl/api/v1/parse")
            .post(RequestBody.create("application/json".toMediaTypeOrNull(), body))
            .build()
        val resp = client.newCall(req).execute()
        val text = resp.body?.string() ?: throw IOException("响应为空")
        val json = JSONObject(text)
        if (!resp.isSuccessful) throw IOException(json.optString("detail", "解析失败"))
        ParseResult(
            ok = json.optBoolean("ok"),
            title = json.optString("title"),
            author = json.optString("author"),
            coverUrl = json.optString("cover_url"),
            videoUrl = json.optString("video_url"),
            duration = if (json.has("duration")) json.optDouble("duration") else null,
            ext = json.optString("ext", "mp4"),
            platform = json.optString("platform"),
            videoHeight = if (json.has("height")) json.optInt("height") else null
        )
    }
}

// ─── 下载/分享（流式 + 进度回调） ───
fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / 1048576.0)
    bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / 1024.0)
    else -> "${bytes} B"
}

class VideoSaver(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    private fun streamDownload(url: String, onProgress: (Long, Long) -> Unit): java.io.InputStream {
        val req = Request.Builder().url(url).build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) throw IOException("下载失败: HTTP ${resp.code}")
        val body = resp.body ?: throw IOException("下载内容为空")
        val total = body.contentLength()
        val source = body.byteStream()
        // 包一层流，边读边回调进度
        return object : java.io.InputStream() {
            var read = 0L
            override fun read(): Int = throw IOException("use read(buf)")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = source.read(b, off, len)
                if (n > 0) {
                    read += n
                    if (total > 0) onProgress(read, total)
                }
                return n
            }
            override fun close() { source.close() }
        }
    }

    suspend fun downloadAndSave(
        url: String, title: String, ext: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Uri = withContext(Dispatchers.IO) {
        val safeName = title.replace(Regex("[\\/:*?\"<>|\n]"), "_").take(50)
        val fileName = "VS_${System.currentTimeMillis()}_$safeName.$ext"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/$ext")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoSaver")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("无法创建媒体文件")
        try {
            val input = streamDownload(url, onProgress)
            resolver.openOutputStream(uri)?.use { output ->
                input.use { it.copyTo(output, 8192) }
            } ?: throw IOException("无法写入文件")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // 失败清理残留
            throw e
        }
    }

    suspend fun downloadAndShare(
        url: String, title: String, ext: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val safeName = title.replace(Regex("[\\/:*?\"<>|\n]"), "_").take(30)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "video_${System.currentTimeMillis()}.$ext")
        val input = streamDownload(url, onProgress)
        file.outputStream().use { output ->
            input.use { it.copyTo(output, 8192) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "video/$ext"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        share.setPackage("com.tencent.mm")
        context.startActivity(Intent.createChooser(share, "分享视频"))
    }
}

fun platformLabel(p: String): String = when (p) {
    "douyin" -> "抖音"
    "wechat_channels" -> "视频号"
    "bilibili" -> "B站"
    "xiaohongshu" -> "小红书"
    "kuaishou" -> "快手"
    "ixigua" -> "西瓜视频"
    else -> p
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val baseUrl = "http://150.109.83.73:8080"
        val api = ApiClient(baseUrl)
        val saver = VideoSaver(this)
        var sharedText = ""
        if (intent?.action == Intent.ACTION_SEND) {
            sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        } else if (intent?.action == Intent.ACTION_VIEW) {
            sharedText = intent.data?.toString() ?: ""
        }
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = AppColors.Primary,
                    background = AppColors.Surface,
                    surface = AppColors.CardBg
                )
            ) {
                AppScreen(api, saver, initialText = sharedText)
            }
        }
    }
}

@Composable
fun AppScreen(api: ApiClient, saver: VideoSaver, initialText: String = "") {
    var inputText by remember { mutableStateOf(initialText) }
    var isLoading by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadInfo by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ParseResult?>(null) }
    var statusMsg by remember { mutableStateOf("") }
    var statusIsError by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Surface)
            .verticalScroll(rememberScrollState())
    ) {
        // ── 渐变头部 ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(AppColors.GradientStart, AppColors.GradientEnd)
                    )
                )
                .padding(vertical = 36.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White.copy(alpha = 0.2f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("📥", fontSize = 28.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("一诺秒存", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text(
                    "粘贴链接 · 秒存相册 · 一键分享微信",
                    fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(24.dp))

            // ── 输入卡片 ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.CardBg),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("粘贴抖音 / 视频号 / B站分享链接…", fontSize = 14.sp, color = AppColors.TextSecondary) },
                        minLines = 1,
                        maxLines = 4,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppColors.Primary,
                            unfocusedBorderColor = Color(0xFFE5E7EB)
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 粘贴
                        OutlinedButton(
                            onClick = {
                                clipboard.getText()?.let {
                                    inputText = it.text
                                    statusMsg = ""
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.Primary)
                        ) { Text("粘贴", fontSize = 14.sp, maxLines = 1, softWrap = false) }

                        // 清空
                        OutlinedButton(
                            onClick = {
                                inputText = ""
                                result = null
                                statusMsg = ""
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.TextSecondary)
                        ) { Text("清空", fontSize = 14.sp, maxLines = 1, softWrap = false) }

                        // 解析
                        Button(
                            onClick = {
                                scope.launch {
                                    isLoading = true
                                    result = null
                                    statusMsg = "正在解析…"
                                    statusIsError = false
                                    try {
                                        result = api.parse(inputText)
                                        statusMsg = if (result?.ok == true) "✅ 解析成功" else "❌ 解析失败"
                                        statusIsError = result?.ok != true
                                    } catch (e: Exception) {
                                        statusMsg = "❌ ${e.message}"
                                        statusIsError = true
                                    }
                                    isLoading = false
                                }
                            },
                            enabled = !isLoading && inputText.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1.2f),
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("解析中", fontSize = 14.sp, color = Color.White, maxLines = 1, softWrap = false)
                            } else {
                                Text("解析", fontSize = 14.sp, color = Color.White, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }

            // ── 支持平台 ──
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("抖音", "视频号", "小红书", "B站", "快手", "西瓜").forEachIndexed { i, name ->
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = AppColors.Primary.copy(alpha = 0.07f)
                    ) {
                        Text(
                            name,
                            fontSize = 11.sp,
                            color = AppColors.Primary.copy(alpha = 0.85f),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    if (i < 5) Spacer(Modifier.width(6.dp))
                }
            }

            // ── 状态消息 ──
            if (statusMsg.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    statusMsg,
                    fontSize = 14.sp,
                    color = when {
                        statusIsError -> AppColors.Error
                        else -> AppColors.Success
                    },
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── 空状态引导 ──
            if (result == null && statusMsg.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = AppColors.Primary.copy(alpha = 0.08f),
                        modifier = Modifier.size(88.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("🎬", fontSize = 40.sp)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "粘贴分享链接，一键保存视频",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppColors.TextSecondary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "支持抖音 / 视频号 / 小红书 / B站等",
                        fontSize = 13.sp,
                        color = AppColors.TextSecondary.copy(alpha = 0.7f)
                    )
                }
            }

            // ── 结果卡片 ──
            result?.let { r ->
                if (r.ok) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = AppColors.CardBg),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column {
                            // 封面图
                            if (r.coverUrl.isNotBlank()) {
                                AsyncImage(
                                    model = r.coverUrl,
                                    contentDescription = "封面",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 9f)
                                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }

                            Column(Modifier.padding(16.dp)) {
                                // 平台标签
                                if (r.platform.isNotBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = AppColors.Primary.copy(alpha = 0.1f)
                                    ) {
                                        Text(
                                            platformLabel(r.platform),
                                            fontSize = 12.sp,
                                            color = AppColors.Primary,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }

                                // 标题
                                Text(
                                    r.title,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppColors.TextPrimary,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    lineHeight = 21.sp
                                )

                                // 作者
                                if (r.author.isNotBlank()) {
                                    Spacer(Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = CircleShape,
                                            color = AppColors.Primary.copy(alpha = 0.15f),
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text("👤", fontSize = 12.sp)
                                            }
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            r.author,
                                            fontSize = 13.sp,
                                            color = AppColors.TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                // 时长（有值才显示）
                                r.duration?.takeIf { it > 0 }?.let {
                                    Spacer(Modifier.height(4.dp))
                                    Text("⏱ ${it.toInt()} 秒", fontSize = 13.sp, color = AppColors.TextSecondary)
                                }

                                // 画质（有值才显示）
                                r.videoHeight?.takeIf { it > 0 }?.let {
                                    Text("📺 ${it}p · ${r.ext.uppercase()}", fontSize = 13.sp, color = AppColors.TextSecondary)
                                }

                                Spacer(Modifier.height(16.dp))

                                // 下载进度条
                                if (isDownloading) {
                                    Column {
                                        LinearProgressIndicator(
                                            progress = { downloadProgress },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(8.dp)
                                                .clip(RoundedCornerShape(4.dp)),
                                            color = AppColors.Primary,
                                            trackColor = AppColors.Primary.copy(alpha = 0.12f),
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                "正在下载…",
                                                fontSize = 12.sp,
                                                color = AppColors.Primary,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                downloadInfo,
                                                fontSize = 12.sp,
                                                color = AppColors.TextSecondary
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(12.dp))
                                }

                                // 操作按钮
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                isDownloading = true
                                                downloadProgress = 0f
                                                downloadInfo = "准备下载…"
                                                statusMsg = "正在下载…"
                                                statusIsError = false
                                                try {
                                                    saver.downloadAndSave(r.videoUrl, r.title, r.ext) { read, total ->
                                                        downloadProgress = read.toFloat() / total
                                                        downloadInfo = "${formatBytes(read)} / ${formatBytes(total)}"
                                                    }
                                                    statusMsg = "✅ 已保存到相册"
                                                } catch (e: Exception) {
                                                    statusMsg = "❌ 保存失败: ${e.message}"
                                                    statusIsError = true
                                                }
                                                isDownloading = false
                                                downloadProgress = 0f
                                                downloadInfo = ""
                                            }
                                        },
                                        enabled = !isDownloading && r.videoUrl.isNotBlank(),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary)
                                    ) {
                                        Text("💾 保存到相册", fontSize = 14.sp, color = Color.White)
                                    }

                                    Button(
                                        onClick = {
                                            scope.launch {
                                                isDownloading = true
                                                downloadProgress = 0f
                                                downloadInfo = "准备下载…"
                                                statusMsg = "正在下载…"
                                                statusIsError = false
                                                try {
                                                    saver.downloadAndShare(r.videoUrl, r.title, r.ext) { read, total ->
                                                        downloadProgress = read.toFloat() / total
                                                        downloadInfo = "${formatBytes(read)} / ${formatBytes(total)}"
                                                    }
                                                    statusMsg = "✅ 已打开分享面板"
                                                } catch (e: Exception) {
                                                    statusMsg = "❌ 分享失败: ${e.message}"
                                                    statusIsError = true
                                                }
                                                isDownloading = false
                                                downloadProgress = 0f
                                                downloadInfo = ""
                                            }
                                        },
                                        enabled = !isDownloading && r.videoUrl.isNotBlank(),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Success)
                                    ) {
                                        Text("💬 分享微信", fontSize = 14.sp, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            // ── 底部说明 ──
            Text(
                "仅保存你有权使用的公开内容\n请遵守平台服务条款与法律法规",
                fontSize = 12.sp,
                color = AppColors.TextSecondary.copy(alpha = 0.7f),
                lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
