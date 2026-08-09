"""code-runner：隔离的代码执行沙箱（设计文档新增能力：代码题在线运行/调试）

安全边界（本地实验版）：
- 每种语言在独立临时目录内编译运行，进程级超时 + 资源上限（CPU时间/内存/文件大小）
- 仅支持 java / python / javascript 三种语言，输入通过 stdin 传入，输出截断上限
- 不注入任何宿主环境变量给被执行代码；容器内运行，与业务后端隔离
- 容器侧加固（docker-compose）：只挂 internal 网络（无公网出口、不可达 DB/Redis）、
  cap_drop ALL、根文件系统只读 + tmpfs /tmp、pids 上限
- 应用侧：全局并发信号量（RUN_MAX_CONCURRENCY）+ 代码/标准输入体积上限
"""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import threading
from pathlib import Path

from fastapi import FastAPI
from pydantic import BaseModel, Field

app = FastAPI(title="code-runner", version="1.0")

# 资源上限
TIMEOUT_SEC = int(os.environ.get("RUN_TIMEOUT", "6"))
MEM_LIMIT_MB = int(os.environ.get("RUN_MEM_LIMIT_MB", "256"))
OUT_LIMIT = int(os.environ.get("RUN_OUT_LIMIT", "8192"))
# 并发与体积上限：防止单实例被刷爆（编译型语言单个请求就能吃满 CPU）
MAX_CONCURRENCY = int(os.environ.get("RUN_MAX_CONCURRENCY", "2"))
MAX_CODE_BYTES = int(os.environ.get("RUN_MAX_CODE_BYTES", "200000"))
MAX_STDIN_BYTES = int(os.environ.get("RUN_MAX_STDIN_BYTES", "65536"))

SUPPORTED = {"java", "python", "javascript"}

# 全局并发槽位（非阻塞获取，忙则直接返回提示，不排队堆积）
_slots = threading.BoundedSemaphore(MAX_CONCURRENCY)


# java 代码必须含 class Main（前端 starter 模板与出题 prompt 均约定类名为 Main）


def _limits(cpu_only: bool = False):
    """preexec_fn：POSIX 资源限制（Windows 下忽略）

    cpu_only=True 用于 JVM/V8 这类会大量预留虚拟地址空间的运行时：
    不设 RLIMIT_AS（它们预留的是地址空间而非真实内存），
    物理内存上限改由 -Xmx / --max-old-space-size 控制。
    """
    try:
        import resource
        soft = TIMEOUT_SEC
        resource.setrlimit(resource.RLIMIT_CPU, (soft + 1, soft + 2))
        if not cpu_only:
            resource.setrlimit(resource.RLIMIT_AS,
                               (MEM_LIMIT_MB * 1024 * 1024, MEM_LIMIT_MB * 1024 * 1024))
        resource.setrlimit(resource.RLIMIT_FSIZE, (10 * 1024 * 1024, 10 * 1024 * 1024))
        resource.setrlimit(resource.RLIMIT_NPROC, (128, 128))
    except (ImportError, ValueError, OSError):
        pass


class RunRequest(BaseModel):
    language: str = Field(description="java|python|javascript")
    code: str
    stdin: str = ""


class RunResult(BaseModel):
    success: bool
    stdout: str = ""
    stderr: str = ""
    exit_code: int | None = None
    timed_out: bool = False
    error: str = ""


def _truncate(s: str) -> str:
    if len(s) > OUT_LIMIT:
        return s[:OUT_LIMIT] + "\n...(输出被截断)"
    return s


def _classify_lang(language: str) -> str:
    lang = (language or "").lower().strip()
    if lang in ("js", "node", "nodejs"):
        lang = "javascript"
    if lang in ("py",):
        lang = "python"
    return lang


@app.get("/health")
def health():
    langs = []
    langs.append("python" if shutil.which("python3") else None)
    langs.append("java" if (shutil.which("javac") and shutil.which("java")) else None)
    langs.append("javascript" if shutil.which("node") else None)
    return {"status": "ok", "available": [x for x in langs if x],
            "max_concurrency": MAX_CONCURRENCY}


@app.post("/run", response_model=RunResult)
def run(req: RunRequest):
    lang = _classify_lang(req.language)
    if lang not in SUPPORTED:
        return RunResult(success=False, error=f"不支持的语言: {req.language}")
    if not req.code.strip():
        return RunResult(success=False, error="代码为空")
    if len(req.code.encode("utf-8")) > MAX_CODE_BYTES:
        return RunResult(success=False, error=f"代码过长（上限 {MAX_CODE_BYTES} 字节）")
    if len((req.stdin or "").encode("utf-8")) > MAX_STDIN_BYTES:
        return RunResult(success=False, error=f"标准输入过长（上限 {MAX_STDIN_BYTES} 字节）")
    if not _slots.acquire(blocking=False):
        return RunResult(success=False,
                         error="沙箱繁忙：并发运行数已达上限，请稍后重试")
    try:
        return _execute(lang, req)
    finally:
        _slots.release()


def _execute(lang: str, req: RunRequest) -> RunResult:
    workdir = Path(tempfile.mkdtemp(prefix="rund_"))
    try:
        cpu_only = lang in ("java", "javascript")
        if lang == "python":
            target = workdir / "main.py"
            target.write_text(req.code, encoding="utf-8")
            cmd = ["python3", "main.py"]
        elif lang == "javascript":
            target = workdir / "main.js"
            target.write_text(req.code, encoding="utf-8")
            cmd = ["node", f"--max-old-space-size={MEM_LIMIT_MB}", "main.js"]
        else:  # java
            if "class Main" not in req.code:
                return RunResult(success=False,
                                 error="Java 代码必须包含 public class Main（请在 Main 类中编写 solution）")
            target = workdir / "Main.java"
            target.write_text(req.code, encoding="utf-8")
            comp = subprocess.run(["javac", "Main.java"], cwd=workdir,
                                  capture_output=True, text=True, timeout=TIMEOUT_SEC + 4,
                                  env={"PATH": os.environ.get("PATH", "")})
            if comp.returncode != 0:
                return RunResult(success=False, stderr=_truncate(comp.stderr),
                                 exit_code=comp.returncode, error="编译失败")
            cmd = ["java", "-Xmx" + str(MEM_LIMIT_MB) + "m", "-XX:+UseSerialGC", "Main"]

        proc = subprocess.run(cmd, cwd=workdir, input=req.stdin,
                              capture_output=True, text=True, timeout=TIMEOUT_SEC,
                              preexec_fn=(lambda: _limits(cpu_only)) if os.name != "nt" else None,
                              env={"PATH": os.environ.get("PATH", "")})
        return RunResult(success=proc.returncode == 0,
                         stdout=_truncate(proc.stdout),
                         stderr=_truncate(proc.stderr),
                         exit_code=proc.returncode)
    except subprocess.TimeoutExpired:
        return RunResult(success=False, timed_out=True,
                         error=f"运行超时（>{TIMEOUT_SEC}s，可能是死循环）")
    except FileNotFoundError as e:
        return RunResult(success=False, error=f"运行环境缺失: {e}")
    except Exception as e:  # 兜底，保证不 500
        return RunResult(success=False, error=f"执行异常: {e}")
    finally:
        shutil.rmtree(workdir, ignore_errors=True)
