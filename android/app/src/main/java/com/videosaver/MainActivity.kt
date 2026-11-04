package com.videosaver

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

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

// ─── 下载并保存到相册 ───
class VideoSaver(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    /**
     * 下载视频并保存到系统相册（MediaStore Movies）
     * 返回保存后的 Uri
     */
    suspend fun downloadAndSave(url: String, title: String, ext: String): Uri = withContext(Dispatchers.IO) {
        // 1. 下载
        val req = Request.Builder().url(url).build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) throw IOException("下载失败: HTTP ${resp.code}")
        val bytes = resp.body?.bytes() ?: throw IOException("下载内容为空")

        // 2. 文件名
        val safeName = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(50)
        val fileName = "VS_${System.currentTimeMillis()}_$safeName.$ext"

        // 3. 保存到 MediaStore（相册）
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/$ext")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoSaver")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("无法创建媒体文件")

        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IOException("无法写入文件")

        // 4. 标记完成（相册可见）
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        uri
    }

    /**
     * 下载到临时文件并分享到微信
     */
    suspend fun downloadAndShare(url: String, title: String, ext: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) throw IOException("下载失败: HTTP ${resp.code}")
        val bytes = resp.body?.bytes() ?: throw IOException("下载内容为空")

        val safeName = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(30)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "video_${System.currentTimeMillis()}.$ext")
        file.writeBytes(bytes)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "video/$ext"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, safeName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // 优先微信
        val wechat = context.packageManager.getLaunchIntentForPackage("com.tencent.mm")
        if (wechat != null) {
            share.setPackage("com.tencent.mm")
            share.action = Intent.ACTION_SEND
        }
        context.startActivity(Intent.createChooser(share, "分享视频"))
    }
}

// ─── Compose UI ───
class MainActivity : ComponentActivity() {
    private lateinit var api: ApiClient
    private lateinit var saver: VideoSaver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // TODO: 替换为你的服务器地址
        val baseUrl = "http://150.109.83.73:8080" // 生产服务器
        api = ApiClient(baseUrl)
        saver = VideoSaver(this)

        // 处理系统分享/链接跳转
        var sharedText = ""
        if (intent?.action == Intent.ACTION_SEND) {
            sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        } else if (intent?.action == Intent.ACTION_VIEW) {
            sharedText = intent.data?.toString() ?: ""
        }

        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                MainScreen(api, saver, initialText = sharedText)
            }
        }
    }
}

@Composable
fun MainScreen(api: ApiClient, saver: VideoSaver, initialText: String = "") {
    var inputText by remember { mutableStateOf(initialText) }
    var isLoading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ParseResult?>(null) }
    var statusMsg by remember { mutableStateOf("") }
    var downloadedUri by remember { mutableStateOf<Uri?>(null) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        Text("视频保存", fontSize = 28.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("粘贴分享链接，保存到相册或分享到微信", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))

        // 输入框
        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("粘贴抖音/视频号/B站分享链接或文案…") },
            minLines = 2,
            maxLines = 4,
            shape = MaterialTheme.shapes.medium
        )
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    clipboard.getText()?.let {
                        inputText = it.text
                        statusMsg = ""
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("粘贴") }

            OutlinedButton(
                onClick = {
                    inputText = ""
                    result = null
                    statusMsg = ""
                    downloadedUri = null
                },
                modifier = Modifier.weight(1f)
            ) { Text("清空") }

            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        result = null
                        downloadedUri = null
                        statusMsg = "正在解析…"
                        try {
                            result = api.parse(inputText)
                            statusMsg = if (result?.ok == true) "解析成功" else "解析失败"
                        } catch (e: Exception) {
                            statusMsg = "解析失败: ${e.message}"
                        }
                        isLoading = false
                    }
                },
                enabled = !isLoading && inputText.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) { Text(if (isLoading) "解析中…" else "解析") }
        }

        Spacer(Modifier.height(16.dp))

        // 状态信息
        if (statusMsg.isNotEmpty()) {
            Text(statusMsg, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
        }

        // 解析结果
        result?.let { r ->
            if (r.ok) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(r.title, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        if (r.author.isNotBlank()) {
                            Text("by ${r.author}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        r.duration?.takeIf { it > 0 }?.let {
                            Text("时长: ${it.toInt()}秒", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        r.videoHeight?.takeIf { it > 0 }?.let {
                            Text("画质: ${it}p ${r.ext.uppercase()}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // 操作按钮
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                statusMsg = "正在下载…"
                                try {
                                    downloadedUri = saver.downloadAndSave(r.videoUrl, r.title, r.ext)
                                    statusMsg = "已保存到相册（Movies/VideoSaver）"
                                } catch (e: Exception) {
                                    statusMsg = "保存失败: ${e.message}"
                                }
                            }
                        },
                        enabled = !isLoading && r.videoUrl.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("保存到相册") }

                    Button(
                        onClick = {
                            scope.launch {
                                statusMsg = "正在下载…"
                                try {
                                    saver.downloadAndShare(r.videoUrl, r.title, r.ext)
                                    statusMsg = "已打开分享面板"
                                } catch (e: Exception) {
                                    statusMsg = "分享失败: ${e.message}"
                                }
                            }
                        },
                        enabled = !isLoading && r.videoUrl.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("分享到微信") }
                }
            }
        }
    }
}
