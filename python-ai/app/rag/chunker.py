"""文本清洗与语义分块（设计文档 §9.1：512字符/重叠50）"""
from __future__ import annotations

import re


def clean_text(text: str) -> str:
    """去除格式噪声：不可见字符、多余空白、控制字符"""
    text = text.replace("\u3000", " ").replace("\r\n", "\n")
    text = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", "", text)
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def split_text(text: str, chunk_size: int = 512, overlap: int = 50) -> list[str]:
    """递归按语义边界分块：段落 -> 句读 -> 逗号"""
    if not text:
        return []
    separators = ["\n\n", "\n", "。", "！", "？", "；", "，", "."]

    def _split(s: str, seps: list[str]) -> list[str]:
        if len(s) <= chunk_size:
            return [s.strip()] if s.strip() else []
        sep = seps[0] if seps else ""
        pieces = s.split(sep) if sep else [s[i:i + chunk_size] for i in range(0, len(s), chunk_size)]
        chunks: list[str] = []
        buf = ""
        for p in pieces:
            candidate = f"{buf}{p}{sep}" if sep else f"{buf}{p}"
            if len(candidate) <= chunk_size:
                buf = candidate
            else:
                if buf.strip():
                    chunks.append(buf.strip())
                # 单片段超长：用下一级分隔符继续拆
                if len(p) + len(sep) > chunk_size and len(seps) > 1:
                    chunks.extend(_split(p, seps[1:]))
                    buf = ""
                else:
                    buf = p + sep
        if buf.strip():
            chunks.append(buf.strip())
        return chunks

    chunks = _split(text, separators)

    # 相邻块重叠，保留上下文连续性
    if overlap > 0 and len(chunks) > 1:
        merged = []
        for i, c in enumerate(chunks):
            if i > 0:
                tail = chunks[i - 1][-overlap:]
                c = tail + c
            merged.append(c)
        chunks = merged
    return [c for c in chunks if c]
