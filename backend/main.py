"""视频解析 API — yt-dlp 封装"""
import asyncio
import json
import re
import subprocess
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

app = FastAPI(title="Video Saver API")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

YT_DLP = "yt-dlp"
TIMEOUT = 30


class ParseRequest(BaseModel):
    url: str


def extract_url(text: str) -> str | None:
    """从分享文案中提取URL"""
    m = re.search(r'https?://[^\s<>"\')\]]+', text)
    return m.group(0) if m else None


def detect_platform(url: str) -> str:
    """识别平台"""
    u = url.lower()
    if "douyin.com" in u or "v.douyin" in u or "iesdouyin.com" in u:
        return "douyin"
    if "weixin.qq.com/sph" in u or "channels.weixin" in u:
        return "wechat_channels"
    if "bilibili.com" in u or "b23.tv" in u:
        return "bilibili"
    if "xiaohongshu.com" in u or "xhslink" in u:
        return "xiaohongshu"
    if "kuaishou.com" in u:
        return "kuaishou"
    if "ixigua.com" in u:
        return "ixigua"
    return "unknown"


def run_ytdlp(url: str) -> dict:
    """调用 yt-dlp 解析视频信息"""
    cmd = [
        YT_DLP, "--dump-json", "--no-playlist", "--no-warnings",
        "--socket-timeout", str(TIMEOUT), url
    ]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=TIMEOUT + 10)
        if result.returncode != 0:
            stderr = result.stderr.strip()
            if "login" in stderr.lower() or "private" in stderr.lower():
                raise HTTPException(403, detail="该视频需要登录或为私密内容")
            if "unsupported" in stderr.lower() or "no video" in stderr.lower():
                raise HTTPException(404, detail="不支持该链接或未找到视频")
            raise HTTPException(500, detail=f"解析失败: {stderr[:200]}")
        return json.loads(result.stdout)
    except subprocess.TimeoutExpired:
        raise HTTPException(504, detail="解析超时，请稍后重试")
    except json.JSONDecodeError:
        raise HTTPException(500, detail="解析返回格式异常")


@app.post("/api/v1/parse")
async def parse(req: ParseRequest):
    """解析视频链接，返回下载地址和元信息"""
    url = extract_url(req.url)
    if not url:
        raise HTTPException(400, detail="未找到有效链接，请粘贴完整分享文案")

    platform = detect_platform(url)
    info = run_ytdlp(url)

    # 选择最佳格式
    formats = info.get("formats") or []
    best = None
    for f in reversed(formats):
        if f.get("vcodec") != "none" and f.get("ext") in ("mp4", "webm"):
            if not best or (f.get("height") or 0) > (best.get("height") or 0):
                best = f
    video_url = (best or {}).get("url") or info.get("url") or ""

    return {
        "ok": True,
        "platform": platform,
        "title": info.get("title", ""),
        "author": info.get("uploader", ""),
        "cover_url": info.get("thumbnail", ""),
        "duration": info.get("duration"),
        "width": info.get("width"),
        "height": info.get("height"),
        "video_url": video_url,
        "ext": (best or {}).get("ext", "mp4"),
        "filesize": (best or {}).get("filesize") or info.get("filesize"),
    }


@app.get("/api/v1/health")
async def health():
    return {"ok": True}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8080)
