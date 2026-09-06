"""Skills 模块化加载（设计文档 §7.2）

目录约定：skills/<skill-name>/SKILL.md，结构：
# 标题
## 领域提示        -> 注入 Interviewer/Evaluator 的领域Prompt模板
## 考察点清单      -> 列表
## 难度分级标准    -> 各难度级别描述列表
## 题库索引        -> 该Skill关联的题库 skill_name 列表
新增技能领域只需新增目录，无需修改Agent核心逻辑。
"""
from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from pathlib import Path

from app.core.config import get_settings

logger = logging.getLogger(__name__)


@dataclass
class SkillContext:
    name: str
    domain_prompt: str = ""
    inspect_points: list[str] = field(default_factory=list)
    difficulty_levels: list[str] = field(default_factory=list)
    question_bank_skills: list[str] = field(default_factory=list)

    def as_prompt_block(self) -> str:
        parts = []
        if self.domain_prompt:
            parts.append(f"【领域知识】{self.domain_prompt}")
        if self.inspect_points:
            parts.append("【考察点清单】" + "；".join(self.inspect_points))
        if self.difficulty_levels:
            parts.append("【难度分级标准】" + "；".join(self.difficulty_levels))
        return "\n".join(parts)


def _parse_md(text: str) -> dict[str, list[str]]:
    sections: dict[str, list[str]] = {}
    current = "_head"
    for line in text.splitlines():
        m = re.match(r"^##\s+(.+)$", line)
        if m:
            current = m.group(1).strip()
            sections.setdefault(current, [])
            continue
        if line.strip():
            sections.setdefault(current, []).append(line.strip().lstrip("-* ").strip())
    return sections


_cache: dict[str, tuple[float, SkillContext]] = {}


def _read_skill(skill_name: str) -> tuple[float, SkillContext]:
    """解析 SKILL.md 并返回 (mtime, 上下文)；文件不存在时 mtime 记 0（行为不变）"""
    ctx = SkillContext(name=skill_name)
    path = Path(get_settings().skills_dir) / skill_name / "SKILL.md"
    if path.exists():
        sections = _parse_md(path.read_text(encoding="utf-8"))
        ctx.domain_prompt = " ".join(sections.get("领域提示", []))
        ctx.inspect_points = sections.get("考察点清单", [])
        ctx.difficulty_levels = sections.get("难度分级标准", [])
        ctx.question_bank_skills = sections.get("题库索引", []) or [skill_name]
        logger.info("已加载Skill: %s", skill_name)
        return (path.stat().st_mtime, ctx)
    ctx.question_bank_skills = [skill_name]
    logger.debug("未找到Skill文件 %s，使用默认上下文", path)
    return (0.0, ctx)


def load_skill(skill_name: str) -> SkillContext:
    """按技能名动态加载 Skill；不存在时返回空上下文（Agent核心逻辑不感知）

    热加载：每次 stat SKILL.md，mtime 变化即重解析（宿主改文件即时生效）。
    """
    skill_name = (skill_name or "").lower()
    cached = _cache.get(skill_name)
    if cached is not None:
        path = Path(get_settings().skills_dir) / skill_name / "SKILL.md"
        try:
            mtime = path.stat().st_mtime if path.exists() else 0.0
        except OSError:
            mtime = 0.0
        if mtime == cached[0]:
            return cached[1]
    entry = _read_skill(skill_name)
    _cache[skill_name] = entry
    return entry[1]


def reload_skills() -> list[str]:
    """清空缓存强制下次访问重解析，返回当前可用 Skills 清单"""
    _cache.clear()
    return list_skills()


def list_skills() -> list[str]:
    root = Path(get_settings().skills_dir)
    if not root.exists():
        return []
    return sorted(p.name for p in root.iterdir() if (p / "SKILL.md").exists())
