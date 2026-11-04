"""视频解析 API — 双引擎：Mtotech API（抖音/视频号）+ yt-dlp（其他平台）"""
import json
import re
import subprocess
from pathlib import Path

import httpx
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

app = FastAPI(title="Video Saver API")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

MTOTECH_BASE = "https://v.mtotech.com"
YT_DLP = "yt-dlp"
TIMEOUT = 30

UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"


class ParseRequest(BaseModel):
    url: str


def extract_url(text: str) -> str | None:
    m = re.search(r'https?://[^\s<>"\')\]]+', text)
    return m.group(0) if m else None


def detect_platform(url: str) -> str:
    u = url.lower()
    if "douyin.com" in u or "iesdouyin.com" in u:
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


def resolve_short_url(url: str) -> str:
    """解析短链接（如 v.douyin.com）到最终URL"""
    if not re.search(r'v\.douyin\.com|xhslink', url):
        return url
    try:
        with httpx.Client(follow_redirects=True, timeout=10, headers={"User-Agent": UA}) as c:
            r = c.get(url)
            return str(r.url)
    except Exception:
        return url


async def parse_via_mtotech(url: str, platform: str) -> dict | None:
    """通过 mtotech API 解析（抖音/视频号等国内平台）"""
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT) as c:
            if platform == "wechat_channels":
                resp = await c.post(f"{MTOTECH_BASE}/api/resolve", json={"url": url})
            else:
                resp = await c.post(f"{MTOTECH_BASE}/api/media/resolve",
                                    json={"url": url, "platform": platform})
            data = resp.json()
            if data.get("ok"):
                d = data["data"]
                return {
                    "ok": True,
                    "platform": d.get("platform", platform),
                    "title": d.get("title") or d.get("description", ""),
                    "author": d.get("author", ""),
                    "cover_url": d.get("cover_url", ""),
                    "duration": d.get("duration"),
                    "video_url": d.get("download_url") or d.get("h264_url") or d.get("preview_url", ""),
                    "ext": "mp4",
                    "source": "mtotech",
                }
    except Exception:
        return None
    return None


def parse_via_ytdlp(url: str) -> dict:
    """通过 yt-dlp 解析（B站/YouTube/其他国际平台）"""
    cmd = [YT_DLP, "--dump-json", "--no-playlist", "--no-warnings", "--socket-timeout", str(TIMEOUT), url]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=TIMEOUT + 10)
        if result.returncode != 0:
            stderr = result.stderr.strip()
            if "login" in stderr.lower() or "private" in stderr.lower():
                raise HTTPException(403, detail="该视频需要登录或为私密内容")
            if "unsupported" in stderr.lower():
                raise HTTPException(404, detail="不支持该链接")
            raise HTTPException(500, detail=f"解析失败: {stderr[:200]}")
        info = json.loads(result.stdout)
        # 找最佳mp4
        best = None
        for f in reversed(info.get("formats") or []):
            if f.get("vcodec") != "none" and f.get("ext") in ("mp4", "webm"):
                if not best or (f.get("height") or 0) > (best.get("height") or 0):
                    best = f
        return {
            "ok": True,
            "platform": info.get("extractor_key", "").lower().replace(" ", "_"),
            "title": info.get("title", ""),
            "author": info.get("uploader", ""),
            "cover_url": info.get("thumbnail", ""),
            "duration": info.get("duration"),
            "width": info.get("width"),
            "height": info.get("height"),
            "video_url": (best or {}).get("url") or info.get("url", ""),
            "ext": (best or {}).get("ext", "mp4"),
            "source": "ytdlp",
        }
    except subprocess.TimeoutExpired:
        raise HTTPException(504, detail="解析超时")
    except json.JSONDecodeError:
        raise HTTPException(500, detail="返回格式异常")


@app.post("/api/v1/parse")
async def parse(req: ParseRequest):
    """统一解析入口"""
    url = extract_url(req.url)
    if not url:
        raise HTTPException(400, detail="未找到有效链接，请粘贴完整分享文案")

    platform = detect_platform(url)
    url = resolve_short_url(url)

    # 抖音/视频号/小红书/快手/西瓜 → mtotech API 优先
    if platform in ("douyin", "wechat_channels", "xiaohongshu", "kuaishou", "ixigua"):
        result = await parse_via_mtotech(url, platform)
        if result:
            return result
        # mtotech失败 → yt-dlp 兜底
        return parse_via_ytdlp(url)

    # B站/YouTube/其他 → yt-dlp
    return parse_via_ytdlp(url)


@app.get("/api/v1/health")
async def health():
    return {"ok": True}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8080)
