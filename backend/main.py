"""视频解析 API — 多引擎：Mtotech（抖音/视频号/小红书/快手/西瓜）+ 自研微博解析 + yt-dlp（B站等）"""
import json
import os
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
TIMEOUT = 120  # 长视频解析/下载放宽
UA_PC = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"
UA_MOBILE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
WEIBO_COOKIE = os.environ.get("WEIBO_COOKIE", "")
YNVAN_BASE = "https://ynvan.com"


class ParseRequest(BaseModel):
    url: str


# ── 基础工具 ──

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
    if "kuaishou.com" in u or "chenzhongtech.com" in u:
        return "kuaishou"
    if "ixigua.com" in u:
        return "ixigua"
    if "weibo.com" in u or "weibo.cn" in u or "t.cn" in u:
        return "weibo"
    return "unknown"


def resolve_short_url(url: str) -> str:
    """解析短链（v.douyin / xhslink / t.cn 等）到最终 URL"""
    if not re.search(r'v\.douyin\.com|xhslink|t\.cn', url):
        return url
    try:
        with httpx.Client(follow_redirects=True, timeout=15, headers={"User-Agent": UA_MOBILE}) as c:
            r = c.get(url)
            return str(r.url)
    except Exception:
        return url


# ── 微博解析（需配置 WEIBO_COOKIE 环境变量） ──

def _weibo_headers() -> dict:
    return {
        "User-Agent": UA_MOBILE,
        "Referer": "https://m.weibo.cn/",
        "X-Requested-With": "XMLHttpRequest",
        "Cookie": WEIBO_COOKIE,
    }


def _mblog_to_result(mblog: dict) -> dict | None:
    """从 mblog JSON 提取统一结果"""
    title = re.sub(r"<[^>]+>", "", mblog.get("text", "")).strip()
    author = (mblog.get("user") or {}).get("screen_name", "")
    images: list[str] = []
    for pic in mblog.get("pics") or []:
        large = (pic.get("large") or {}).get("url") or pic.get("url") or ""
        if large:
            images.append(large)
    cover = images[0] if images else ""

    page_info = mblog.get("page_info") or {}
    media = page_info.get("media_info") or {}
    video_url = (
        media.get("mp4_720p_mp4") or media.get("mp4_hd_url")
        or media.get("mp4_sd_url") or media.get("mp4_ld_url")
        or media.get("playback_url") or media.get("h5_url") or ""
    )
    if "|" in video_url:
        video_url = video_url.split("|")[0]
    if not cover and isinstance(page_info.get("page_pic"), str):
        cover = page_info["page_pic"]

    if not video_url and not images:
        return None
    return {
        "ok": True,
        "platform": "weibo",
        "title": title[:200],
        "author": author,
        "cover_url": cover,
        "duration": page_info.get("duration") if isinstance(page_info.get("duration"), (int, float)) else None,
        "video_url": video_url,
        "images": images,
        "content_type": "video" if video_url else "images",
        "ext": "mp4" if video_url else "jpg",
        "source": "weibo_api",
    }


def parse_weibo(url: str) -> dict | None:
    """微博解析：优先 m.weibo.cn 页面（支持字母短ID），其次 show API（数字ID）"""
    if not WEIBO_COOKIE:
        raise HTTPException(
            501,
            detail="微博解析需要在服务器配置 WEIBO_COOKIE（登录微博后的 Cookie），否则无法访问微博接口",
        )
    headers = _weibo_headers()

    try:
        # Case A: m.weibo.cn/status/{alnum} → 页面内嵌 $render_data
        m = re.search(r"m\.weibo\.cn/(?:status|detail)/(\w+)", url)
        if m and not m.group(1).isdigit():
            r = httpx.get(m.group(0) if m.group(0).startswith("http") else f"https://m.weibo.cn/status/{m.group(1)}",
                          headers=headers, timeout=TIMEOUT, follow_redirects=True)
            m2 = re.search(r"var \$render_data\s*=\s*(\[.*?\])\[0\]", r.text, re.S)
            if m2:
                data = json.loads(m2.group(1))
                mblog = (data[0].get("data") or {}).get("mblog") if isinstance(data, list) else None
                if mblog:
                    result = _mblog_to_result(mblog)
                    if result:
                        return result

        # Case B: 数字ID → show API
        m = re.search(r"m\.weibo\.cn/(?:status|detail)/(\d+)", url) \
            or re.search(r"m\.weibo\.cn/statuses/show\?id=(\d+)", url) \
            or re.search(r"weibo\.com/\d+/(\d{9,})", url)
        if m:
            r = httpx.get(f"https://m.weibo.cn/statuses/show?id={m.group(1)}",
                          headers=headers, timeout=TIMEOUT, follow_redirects=True)
            data = r.json()
            if data.get("ok"):
                result = _mblog_to_result(data.get("data", {}))
                if result:
                    return result

        # Case C: weibo.com/tv/show/1034:{oid} → 组件接口
        m = re.search(r"tv/show/1034:(\w+)", url)
        if m:
            oid = m.group(1)
            r = httpx.get(
                f"https://weibo.com/tv/api/component?page=%2Ftv%2Fshow%2F1034%3A{oid}",
                headers={
                    "User-Agent": UA_PC,
                    "Referer": f"https://weibo.com/tv/show/1034:{oid}",
                    "Cookie": WEIBO_COOKIE,
                },
                timeout=TIMEOUT,
            )
            if r.status_code == 200:
                data = r.json()
                vids = []
                for item in data.get("data", {}).get("Component_Play_Playinfo", {}).get("urls", {}).values():
                    vids.append(item)
                if vids:
                    return {
                        "ok": True, "platform": "weibo", "title": "微博视频",
                        "author": "", "cover_url": "", "duration": None,
                        "video_url": vids[0], "images": [], "content_type": "video",
                        "ext": "mp4", "source": "weibo_component",
                    }
    except HTTPException:
        raise
    except Exception:
        pass
    raise HTTPException(404, detail="微博解析失败：Cookie 可能过期，请在服务器更新 WEIBO_COOKIE 后重试")


# ── Mtotech（抖音/视频号/小红书/快手/西瓜） ──

async def parse_via_mtotech(url: str, platform: str) -> dict | None:
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT) as c:
            if platform == "wechat_channels":
                resp = await c.post(f"{MTOTECH_BASE}/api/resolve", json={"url": url})
            else:
                resp = await c.post(f"{MTOTECH_BASE}/api/media/resolve", json={"url": url, "platform": platform})
            data = resp.json()
            if data.get("ok"):
                d = data["data"]
                # 图文：mtotech 对抖音图集可能返回 images 数组（不同时期字段不同，尽力兼容）
                raw_images = d.get("images") or []
                images = []
                for im in raw_images:
                    u = im.get("url") if isinstance(im, dict) else im
                    if u:
                        images.append(u)
                video_url = d.get("download_url") or d.get("h264_url") or d.get("preview_url") or ""
                content_type = "video" if video_url else ("images" if images else "none")
                return {
                    "ok": True,
                    "platform": d.get("platform", platform),
                    "title": d.get("title") or d.get("description", ""),
                    "author": d.get("author", ""),
                    "cover_url": d.get("cover_url", ""),
                    "duration": d.get("duration"),
                    "video_url": video_url,
                    "images": images,
                    "content_type": content_type,
                    "ext": "mp4" if video_url else "jpg",
                    "source": "mtotech",
                }
    except Exception:
        return None
    return None




# ── Ynvan 第二引擎（抖音图集兜底） ──

async def parse_via_ynvan(url: str) -> dict | None:
    """SSE 流式接口，解析成功事件里的 result。特别用于抖音图文/图集。"""
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT) as c:
            async with c.stream(
                "POST", f"{YNVAN_BASE}/api/parse/stream",
                json={"url": url},
                headers={"Content-Type": "application/json"},
            ) as resp:
                result_payload = None
                async for line in resp.aiter_lines():
                    if not line.startswith("data: "):
                        continue
                    try:
                        evt = json.loads(line[6:])
                    except Exception:
                        continue
                    if evt.get("type") == "result":
                        result_payload = evt.get("result") or {}
                        break
                if not result_payload or result_payload.get("code") != 200:
                    return None
                d = result_payload.get("data") or {}
                images = []
                for im in d.get("images") or []:
                    u = im.get("url") if isinstance(im, dict) else im
                    if u:
                        images.append(u)
                video_url = d.get("video_url") or ""
                author = (d.get("author") or {}).get("name", "")
                if not video_url and not images:
                    return None
                return {
                    "ok": True,
                    "platform": d.get("source", "douyin"),
                    "title": d.get("title", ""),
                    "author": author,
                    "cover_url": d.get("cover_url", ""),
                    "duration": d.get("duration") if isinstance(d.get("duration"), (int, float)) else None,
                    "video_url": video_url,
                    "images": images,
                    "content_type": "video" if video_url else "images",
                    "ext": "mp4" if video_url else "jpg",
                    "source": "ynvan",
                }
    except Exception:
        return None
    return None


# ── yt-dlp（B站/YouTube等） ──

def parse_via_ytdlp(url: str) -> dict:
    cmd = [YT_DLP, "--dump-json", "--no-playlist", "--no-warnings", "--socket-timeout", str(TIMEOUT), url]
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=TIMEOUT + 30)
        if r.returncode != 0:
            err = r.stderr.strip()
            if "login" in err.lower() or "private" in err.lower():
                raise HTTPException(403, detail="该视频需要登录或为私密内容")
            raise HTTPException(500, detail=f"解析失败: {err[:200]}")
        info = json.loads(r.stdout)
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
            "images": [],
            "content_type": "video",
            "ext": (best or {}).get("ext", "mp4"),
            "source": "ytdlp",
        }
    except subprocess.TimeoutExpired:
        raise HTTPException(504, detail="解析超时")
    except json.JSONDecodeError:
        raise HTTPException(500, detail="返回格式异常")


# ── 统一入口 ──

@app.post("/api/v1/parse")
async def parse(req: ParseRequest):
    url = extract_url(req.url)
    if not url:
        raise HTTPException(400, detail="未找到有效链接，请粘贴完整分享文案")

    platform = detect_platform(url)
    url = resolve_short_url(url)

    # 微博：自研解析
    if platform == "weibo":
        result = parse_weibo(url)
        if result:
            return result
        raise HTTPException(404, detail="微博内容解析失败：可能需要登录、已删除或为受限内容")

    # 国内平台：mtotech 优先，失败后抖音走 ynvan 兜底（支持图集）
    if platform in ("douyin", "wechat_channels", "xiaohongshu", "kuaishou", "ixigua"):
        result = await parse_via_mtotech(url, platform)
        if result and (result.get("video_url") or result.get("images")):
            return result
        if platform == "douyin":
            result = await parse_via_ynvan(url)
            if result:
                return result

    # 其余走 yt-dlp
    return parse_via_ytdlp(url)


@app.get("/api/v1/health")
async def health():
    return {"ok": True}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8080)
