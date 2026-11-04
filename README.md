# Video Saver — 多平台视频保存工具

粘贴分享链接 → 解析 → 保存到手机相册 / 分享到微信

## 架构

```
Android APK (Kotlin + Compose)
  ├── 接收系统分享（ACTION_SEND）
  ├── 粘贴链接
  ├── 调用后端 API 解析
  ├── 下载视频
  ├── 保存到相册（MediaStore Movies/VideoSaver）
  └── 分享到微信（FileProvider + ACTION_SEND）
         │
         ▼ HTTPS
Backend (Python FastAPI + yt-dlp)
  ├── 解析抖音/视频号/B站/小红书/快手/西瓜视频
  └── 返回无水印视频直链
```

## 部署后端

```bash
cd backend
pip install -r requirements.txt
pip install yt-dlp
uvicorn main:app --host 0.0.0.0 --port 8080
```

## 构建 APK

```bash
cd android
./gradlew assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk
```

## 配置服务器地址

编辑 `MainActivity.kt` 中的 `baseUrl`：
```kotlin
val baseUrl = "https://video.example.com" // 替换为你的域名
```

## 用户流程

1. 在抖音 → 分享 → 复制链接
2. 打开"视频保存" APK（或从分享菜单选本应用）
3. 点击"解析" → 显示标题/作者/时长
4. 点击"保存到相册"或"分享到微信"
