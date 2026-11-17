#!/usr/bin/env python3
"""DiLink-Auto 双手机真机 E2E — L2/L3 探索 runner（explore.py）。

设计基准：
  - .plan/e2e-harness-design.md §1.5（本文件契约）/ §1.6.4（python→bash 委派取舍）/
    §3.1-3.3（op 注册表 schema）/ §3.4（L2/L3 遍历伪代码）/ §2.5-2.7（假阳性防范
    与产物目录结构）
  - .plan/e2e-dual-device-impl.md D1-D7（偏差记录；单一实现/断言串等以此为准）
  - docs/e2e-scenarios.md §5（不变量清单）/ §6（L3 权重 70:30）

职责边界（§1.5.1，最重要的一条）：**python 只做编排**——序列生成、状态跟踪、预算
记账、看门狗、现场冻结触发、L3→L1 导出。所有设备交互经 bash 委派：
    bash scripts/lib/ops.sh run <id>              # 动作层（唯一可改设备状态的层）
    bash scripts/lib/asserts.sh probe <what>      # 断言层 CLI probe（复合态采集）
    bash -c 'source asserts.sh; <原语> ...'       # 断言层 source 委派（退出码判定）
python 不直连 adb、不实现断言串、不重写等待逻辑（P3 单一实现：L1/L2/L3 跑同一份字节）。

本机硬约束（2026-10-16 实测，违反即全盘失败）：
  1. python 的 subprocess 起 MSYS/Git Bash 时 env= 里加的自定义变量到不了脚本——
     跨进程变量写命令行赋值前缀（外部命令形态）或脚本内 export 行（source 形态），
     值经 shlex.quote。注意：`VAR=val source f` 的前缀赋值对特殊内建 **不持久**
     （实测 source 后 $VAR 即 unbound，devices.sh 的 set -u 直接报错）——
     source 委派一律用 export 行；外部命令（bash script.sh）用赋值前缀（实测可用）。
  2. 一切等待按 time.monotonic() 真实经过时间（P1）；禁「迭代次数 × sleep」。
  3. Windows 路径进 bash 前统一转正斜杠（bs()），防反斜杠被当转义符吃掉。

退出码：0=无违反；1=有违反（violations.jsonl 非空/暂停上报）；2=用法/环境/中止；
130=SIGINT 中断；143=SIGTERM 中断。
"""

from __future__ import annotations

import argparse
import itertools
import json
import os
import random
import re
import shlex
import shutil
import signal
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Sequence

# ── 模块级常量 ────────────────────────────────────────────────────────────

REPO = Path(__file__).resolve().parents[2]   # scripts/lib/explore.py → 仓库根
LIB = REPO / "scripts" / "lib"
REGISTRY_PATH = LIB / "ops-registry.json"
OPS_SH = "scripts/lib/ops.sh"            # 相对 REPO（bash 委派 cwd=REPO）
ASSERTS_SH = "scripts/lib/asserts.sh"
SCEN_DIR = REPO / "scripts" / "scenarios"
DEBUG_ROOT = REPO / "debug-logs" / "e2e"

# asserts.sh source 链（顺序契约：logging → devices → asserts → ops，§1.1）
SOURCE_CHAIN = (
    "source scripts/lib/logging.sh && source scripts/lib/devices.sh"
    " && source scripts/lib/asserts.sh && source scripts/lib/ops.sh"
)

# 复合态向量（与 registry state_model.vector 一致；Registry.validate 强制对齐）
VECTOR = ("car_state", "phone_state", "engine", "vd", "sentinel")
# probe state 输出的键序（与 VECTOR 一一对应）
PROBE_KEYS = ("car", "phone", "engine", "vd", "sentinel")
CAR_STATES = {"IDLE", "CONNECTING", "CONNECTED", "STREAMING", "ERROR", "UNKNOWN"}
PHONE_STATES = {"IDLE", "WAITING", "CONNECTED", "STREAMING", "UNKNOWN"}
ENGINE_VD = {"absent", "present"}
SENTINEL = {"off", "on"}
COMPONENT_VOCAB = {
    "car_state": CAR_STATES,
    "phone_state": PHONE_STATES,
    "engine": ENGINE_VD,
    "vd": ENGINE_VD,
    "sentinel": SENTINEL,
}

# 双端状态一致的可接受组合（docs §5「会话状态一致」；UNKNOWN 不判定）
CONSISTENT_PAIRS = frozenset({
    ("STREAMING", "STREAMING"),
    ("CONNECTED", "CONNECTED"),
    ("CONNECTING", "WAITING"),   # A 连接中 / B 等待中
    ("IDLE", "IDLE"),
    ("IDLE", "WAITING"),         # kill_server 后 B 回落 WAITING（registry 语义）
})

# 不变量档位与稳定 code（§3.4；violations.jsonl / export-scenario 消费这些码）
INV_LIGHT = ("engine-single", "port-9637", "stream-alive")
INV_FULL_EXTRA = ("sentinel", "vd-size", "no-anr", "no-force-kill", "no-tex0",
                  "state-consistent")

# L3 故障注入概率与 check-registry 权重目标（docs §6 / registry notes）
FAULT_RATIO = 0.30
WEIGHT_TARGET = 0.70
WEIGHT_TOL = 0.10

# op target → expect.state_after_any 裸分量条目约束的复合态分量
TARGET_COMPONENT = {
    "car": ("car_state",),
    "phone": ("phone_state",),
    "pc": ("car_state",),                 # usb_pull：会话在 A/B 侧继续
    "both": ("car_state", "phone_state"),
}

DEFAULT_SEED = 42
DEFAULT_SAMPLE = 0.1
PLAN_DISPLAY_CAP = 50          # plan 输出最多展示的轮数（其余只计数）
OP_SEQ_SLICE = 8               # L3 violation 记录的 op 序列切片长度
OP_TIMEOUT_SLACK = 60          # 单 op 硬超时 = settle_s + 60（§1.5.4）
PROBE_TIMEOUT_S = 120          # probe state 的 python 侧超时
REACH_TIMEOUT_S = 300          # reach_streaming 委派超时（connect 90s 预算包抖动）
FREEZE_TIMEOUT_S = 240         # freeze_scene 委派超时
RECONNECT_BUDGET_S = 30        # 设备离线后 kill-server 重连甄别预算
DEFAULT_WATCHDOG_S = 3600      # run 全局看门狗默认（1h）

NEUTRAL_STATE = ("IDLE", "IDLE", "absent", "absent", "off")

# export-scenario 的不变量码 → 断言骨架（参数/判据留人工补全）
ASSERT_SKELETON: dict[str, str] = {
    "engine-single": "  assert_engine_single",
    "port-9637": "  wait_port_established phone 9637 30\n  wait_port_free phone 9637 30",
    "stream-alive": "  wait_stream_alive 20",
    "sentinel": "  assert_sentinel phone 2147483647 30",
    "vd-size": "  # TODO: W/H 取 vd_handshake_size 实测量\n  # assert_vd_size_eq <W> <H> 15",
    "no-anr": "  # TODO: 先 logcat_dump 双端落盘\n  # assert_no_anr \"$RUN/logcat-phone.txt\" \"$RUN/logcat-car.txt\"",
    "no-force-kill": "  # TODO: 先 pull_vdlog 归档\n  # assert_no_force_kill \"$RUN/vd-server.log\"",
    "no-tex0": "  vdlog_scan 'tex=0' --expect-zero",
    "state-consistent": "  # TODO: 目标态按场景定（STREAMING 示例）\n  # wait_state_eq car STREAMING 60\n  # wait_state_eq phone STREAMING 60",
}
# 非不变量违反码 → 骨架里的人工排查提示
NON_INVARIANT_HINT: dict[str, str] = {
    "expect-rc": "op 的 rc 不符 expect.rc——核对 ops.sh 该 op 的失败路径",
    "unexpected-pass": "按设计应失败却成功——预期过期或产品缺陷",
    "state-after": "settle 后复合态不在 expect.state_after_any——核对产品收敛行为",
    "op-timeout": "单 op 硬超时（settle_s+60）——先排除环境卡顿再判产品",
    "round-overrun": "轮超预算——排查某步异常慢",
    "restore-failed": "restore neutral 失败——环境坏，先修环境",
    "consecutive-fail": "同 op 连续 3 次同失败码（pause_and_report）",
    "fail-rate-high": "最近 10 op 失败率 >50%——疑似构建/环境坏（§2.5）",
    "env-broken": "neutralize 不过——环境坏",
    "device-offline": "设备离线（已冻结 + 重连甄别）",
}

SCENARIO_TEMPLATE = '''#!/bin/bash
# DiLink-Auto 双手机真机 E2E — 场景骨架（**未经人工确认不得当绿用例**）
#
# ⚠️ 假阴性防护（harness §2.5）：本文件由 explore.py export-scenario 从 L2/L3
#    violation 自动生成，仅是一条**线索**，不是用例。必须人工复核（确认产品缺陷
#    → 修复 → 复验通过）后才可转为正式绿用例；未确认的 violation 禁止当绿用例。
{provenance}
#
# 契约（harness §1.3）：setup 失败 return 1；ops 只许 run_op；asserts 逐条记账；
#    restore 与 setup 同目标（中立态），编排保证无论成败必跑。

# @id: {sid}
# @name: {name}
# @pre: (IDLE, IDLE, absent, absent, off)
# @post: TODO(人工补全：本场景的目标终态)
# @budget_s: 180
# @priority: P1
# @expect: pass
# @invariants: full
# @ops: {ops}
# @refs: {refs}

scenario_setup() {{
  run_op neutral || return 1
  # TODO(人工)：需要会话前置时取消下一行注释（内部 launch_client → connect → 双端 STREAMING）
  # reach_streaming || return 1
  return 0
}}

scenario_ops() {{
{ops_body}
  return 0
}}

scenario_asserts() {{
  # 软等待（wait_*，有预算）与硬断言（assert_*，禁重试）边界见 harness §2.1
{asserts_body}
  return 0
}}

scenario_restore() {{
  run_op neutral
}}
'''


# ── bash 委派基础设施 ─────────────────────────────────────────────────────

def bs(value: Any) -> str:
    """Windows 路径/值进 bash 前统一转 MSYS 形式。

    反斜杠一律转正斜杠；盘符路径 `C:\\x` → `/c/x`。`C:/x` 的解释随 bash 实现
    而异（Git bash 当绝对、WSL 当相对——2026-10-16 实证：WSL bash 把
    `C:/Users/...` 当相对路径，在仓库根造出 `C:` 残树）；`/c/x` 在 MSYS 侧
    是唯一明确的绝对形。干净环境（相对路径/已 MSYS 化）是无害 no-op。
    """
    s = str(value).replace("\\", "/")
    m = re.match(r"^([A-Za-z]):(?:/(.*))?$", s)
    if m:
        s = "/" + m.group(1).lower() + "/" + (m.group(2) or "")
    return s


_BASH_CACHE: str | None = None


def _resolve_bash() -> str:
    """定位 MSYS/Git bash 绝对路径——**不要**裸名 "bash"（2026-10-16 实证）。

    Windows CreateProcess 的搜索序里 System32 优先于 PATH：裸名 "bash" 会命中
    `C:\\Windows\\System32\\bash.exe`（WSL 启动器）而不是 PATH 里的 Git bash。
    WSL 语义与 harness 全面不符（见 bs() 注释），且本机策略已拉黑 wsl.exe →
    裸名 bash 直接不可用。E2E_BASH 环境变量可覆盖。
    """
    override = os.environ.get("E2E_BASH", "")
    if override and Path(override).is_file():
        return override
    exe = "bash.exe" if os.name == "nt" else "bash"
    for seg in os.environ.get("PATH", "").split(os.pathsep):
        seg = seg.strip()
        if not seg or "system32" in seg.lower():   # WSL 启动器所在，跳过
            continue
        cand = Path(seg) / exe
        if cand.is_file():
            return str(cand)
    return shutil.which("bash") or "bash"


def bash_exe() -> str:
    """懒解析 + 缓存（消毒后首次调用时定格）。"""
    global _BASH_CACHE
    if _BASH_CACHE is None:
        _BASH_CACHE = _resolve_bash()
    return _BASH_CACHE


def sanitize_session_env() -> None:
    """WorkBuddy 会话环境消毒（2026-10-16 实测三件套；干净环境无害 no-op）。

    1) BASH_ENV + BASH_FUNC_*：给每个 bash 子进程注入 safe-bin shim
       （rm/rmdir/unlink → broker IPC，单次 rm 慢至 15s）；
    2) PATH 残段：被拆碎的 Windows 风格 `C` / `\\Users\\...\\safe-bin` 段；
    3) PYTHONPATH：vendor shim 目录（sitecustomize 把删除改道回收站）。
    """
    os.environ.pop("BASH_ENV", None)
    for key in [k for k in os.environ if k.upper().startswith("BASH_FUNC_")]:
        os.environ.pop(key, None)
    for var in ("PATH", "PYTHONPATH"):
        raw = os.environ.get(var)
        if not raw:
            continue
        sep = ";" if os.name == "nt" else os.pathsep
        kept = []
        for seg in raw.split(sep):
            low = seg.lower()
            if (not seg or seg.startswith("\\")
                    or (len(seg) == 1 and seg.upper() == "C")
                    or (len(seg) == 2 and seg.endswith(":"))):
                continue
            if "safe-bin" in low:
                continue
            if var == "PYTHONPATH" and "vendor" in low and "shim" in low:
                continue
            kept.append(seg)
        os.environ[var] = sep.join(kept)


def bash(args: Sequence[str], env_extra: dict[str, str] | None = None,
         timeout: float | None = None, cwd: Path | None = None) -> subprocess.CompletedProcess:
    """委派执行外部 bash 命令（ops.sh / asserts.sh CLI / adb 在线核验等）。

    硬约束 #1：python subprocess 起 MSYS/Git Bash 时 env= 的自定义变量到不了
    脚本——所有跨进程变量写成命令行赋值前缀（VAR=val cmd，对**外部命令**实测
    可用，进入其环境），值经 shlex.quote 防反斜杠/空格失真。
    """
    pre = "".join(f"{k}={shlex.quote(bs(v))} " for k, v in (env_extra or {}).items())
    cmd = pre + " ".join(shlex.quote(bs(a)) for a in args)
    return subprocess.run([bash_exe(), "-c", cmd], capture_output=True, text=True,
                          errors="replace", timeout=timeout, cwd=str(cwd or REPO))


def bash_script(script: str, env_extra: dict[str, str] | None = None,
                timeout: float | None = None) -> subprocess.CompletedProcess:
    """执行一段 bash 脚本（asserts.sh source 委派 / reach_streaming / timeout 包裹）。

    硬约束 #1 的 source 形态：`VAR=val source f` 的前缀赋值对特殊内建 **不持久**
    （2026-10-16 实测：source 后 $VAR 即 unbound，devices.sh 的 set -u 直接报错），
    因此 env 用脚本内 export 行传递——在 bash 进程内生效，sourced 文件与后续函数
    调用均可见，也不受 MSYS 环境清洗影响。
    """
    exports = "".join(f"export {k}={shlex.quote(bs(v))}\n"
                      for k, v in (env_extra or {}).items())
    return subprocess.run([bash_exe(), "-c", exports + script], capture_output=True,
                          text=True, errors="replace", timeout=timeout, cwd=str(REPO))


def _expired_text(exc: subprocess.TimeoutExpired) -> str:
    """TimeoutExpired 的残存输出（bytes/str 兼容）转文本。"""
    parts = []
    for chunk in (exc.stdout, exc.stderr):
        if not chunk:
            continue
        parts.append(chunk.decode("utf-8", "replace") if isinstance(chunk, bytes)
                     else str(chunk))
    parts.append(f"<python 侧硬超时 {exc.timeout}s>")
    return "\n".join(parts)


def clean_out(text: str) -> str:
    """剔除本机 python→MSYS bash 启动时必然出现的 WSL localhost 代理警告。

    2026-10-16 实测：python spawn 的每个 bash 都会往 stderr 写一段 132B 混合
    UTF-16/UTF-8 编码的「wsl: 将 localhost 解析为……」警告（bash 直起时没有）。
    判据：合法 bash 输出不会含 NUL/替换字符——含之即噪声段，整段剔除。
    只用于证据日志清洗；判定逻辑本就只用 stdout 与退出码，不受影响。
    """
    if not text:
        return text
    kept = [ln for ln in text.splitlines()
            if ln.strip() and "\x00" not in ln and "\ufffd" not in ln]
    return "\n".join(kept) + ("\n" if kept else "")


# ── 日志（stdout + <run-dir>/explore.log，utf-8）──────────────────────────

class Logger:
    """日志同时进 stdout 与运行目录的 explore.log（与仓库 bash 脚本同款中文信息）。"""

    def __init__(self, run_dir: Path | None = None):
        self._fh = None
        if run_dir is not None:
            run_dir.mkdir(parents=True, exist_ok=True)
            self._fh = (run_dir / "explore.log").open("a", encoding="utf-8")

    def _emit(self, level: str, msg: str) -> None:
        line = f"[{time.strftime('%H:%M:%S')}] {level:<4} {msg}"
        print(line, flush=True)
        if self._fh is not None:
            self._fh.write(line + "\n")
            self._fh.flush()

    def info(self, msg: str) -> None: self._emit("INFO", msg)
    def ok(self, msg: str) -> None: self._emit("OK", msg)
    def warn(self, msg: str) -> None: self._emit("WARN", msg)
    def err(self, msg: str) -> None: self._emit("ERR", msg)


# ── 注册表 ────────────────────────────────────────────────────────────────

OP_REQUIRED_KEYS = ("id", "target", "desc", "settle_s", "weight", "class",
                    "impl", "requires", "ops", "expect", "l1_refs")
OP_CLASSES = {"normal", "fault", "internal", "manual"}
OP_TARGETS = {"phone", "car", "both", "pc"}


@dataclass(frozen=True)
class Round:
    """L2 一轮：起点复合态 + 有序 op 序列 + 深度。"""
    start: tuple[str, ...]
    ops: tuple[str, ...]
    depth: int


class Registry:
    """ops-registry.json 的消费视图（只读；explore.py 不改注册表）。"""

    def __init__(self, data: dict[str, Any], path: Path):
        self.data = data
        self.path = path
        self.defaults: dict[str, Any] = data.get("defaults") or {}
        sm = data.get("state_model") or {}
        self.vector: tuple[str, ...] = tuple(sm.get("vector") or VECTOR)
        self.ops: dict[str, dict[str, Any]] = {o["id"]: o for o in data.get("ops") or []}
        self.allowed_map: dict[str, list[str]] = sm.get("allowed") or {}
        self._starts = [tuple(s.strip("()").split(","))
                        for s in sm.get("start_states") or []]

    @classmethod
    def load(cls, path: Path = REGISTRY_PATH) -> "Registry":
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
        return cls(data, path)

    @property
    def op_list(self) -> list[dict[str, Any]]:
        return list(self.ops.values())

    def op(self, op_id: str) -> dict[str, Any]:
        return self.ops[op_id]

    def settle(self, op_id: str) -> int:
        return int(self.ops[op_id].get("settle_s") or self.defaults.get("settle_s", 15))

    @property
    def start_states(self) -> list[tuple[str, ...]]:
        return list(self._starts)

    @property
    def round_budget(self) -> int:
        return int(self.defaults.get("round_budget_s", 90))

    def validate(self) -> list[str]:
        """注册表结构校验（问题列表；空 = 通过）。"""
        problems: list[str] = []
        if not self.ops:
            problems.append("注册表 ops 为空")
        for oid, op in self.ops.items():
            for key in OP_REQUIRED_KEYS:
                if key not in op:
                    problems.append(f"op {oid} 缺字段 {key}")
            if op.get("class") not in OP_CLASSES:
                problems.append(f"op {oid} class 非法：{op.get('class')!r}")
            if op.get("target") not in OP_TARGETS:
                problems.append(f"op {oid} target 非法：{op.get('target')!r}")
            if not isinstance(op.get("settle_s"), int) or op.get("settle_s", 0) <= 0:
                problems.append(f"op {oid} settle_s 非正整数")
            weight = op.get("weight")
            if not isinstance(weight, (int, float)) or weight < 0:
                problems.append(f"op {oid} weight 非法：{weight!r}")
            exp = op.get("expect") or {}
            if exp.get("rc") not in ("ok", "fail", "either"):
                problems.append(f"op {oid} expect.rc 非法：{exp.get('rc')!r}")
            saa = exp.get("state_after_any")
            if not isinstance(saa, list) or not saa:
                problems.append(f"op {oid} expect.state_after_any 为空")
            for entry in saa or []:
                if entry.startswith("(") and len(entry.strip("()").split(",")) != len(VECTOR):
                    problems.append(f"op {oid} state_after_any 元组分量数错：{entry}")
            if op.get("impl") != "ops.sh":
                problems.append(f"op {oid} impl 非 ops.sh（inline 逃生舱未实现）")
        if self.vector != VECTOR:
            problems.append(f"state_model.vector 与 explore.py 假设不符：{self.vector}")
        for key, ids in self.allowed_map.items():
            if key not in CAR_STATES:
                problems.append(f"allowed 键非 car_state：{key}")
            for oid in ids:
                if oid not in self.ops:
                    problems.append(f"allowed[{key}] 含未知 op：{oid}")
        for st in self._starts:
            if len(st) != len(VECTOR):
                problems.append(f"start_state 分量数错：{st}")
                continue
            for name, val in zip(VECTOR, st):
                if val not in COMPONENT_VOCAB[name]:
                    problems.append(f"start_state 分量 {name} 非法：{st}")
            if st[0] not in self.allowed_map:
                problems.append(f"start_state 的 car_state 无 allowed 条目（将产生 0 轮）：{st}")
        for key in ("settle_s", "teardown_settle_s", "round_budget_s", "op_timeout_s"):
            if key not in self.defaults:
                problems.append(f"defaults 缺 {key}")
        return problems


def ops_sh_list() -> set[str]:
    """ops.sh list 的 id 集合（dummy serial 仅满足其入口断言，不触设备）。"""
    result = bash(["bash", OPS_SH, "list"],
                  env_extra={"PHONE_SERIAL": "check-registry",
                             "CAR_SERIAL": "check-registry"},
                  timeout=60)
    if result.returncode != 0:
        raise RuntimeError(f"ops.sh list 失败（rc={result.returncode}）："
                           f"{(result.stderr or '').strip()}")
    return {ln.strip() for ln in (result.stdout or "").splitlines() if ln.strip()}


# ── 复合态工具 ────────────────────────────────────────────────────────────

def fmt_state(state: Sequence[str]) -> str:
    """复合态 → 日志用单行（car=.. phone=.. engine=.. vd=.. sentinel=..）。"""
    return " ".join(f"{k}={v}" for k, v in zip(PROBE_KEYS, state))


def state_str(state: Sequence[str]) -> str:
    """复合态 → registry 元组字符串（(IDLE,IDLE,absent,absent,off)，无空格）。"""
    return "(" + ",".join(state) + ")"


def parse_start(text: str) -> tuple[str, ...]:
    """解析 --start 复合态；非法抛 ValueError。"""
    t = text.strip()
    if not (t.startswith("(") and t.endswith(")")):
        raise ValueError(f"--start 需为复合态元组，如 (IDLE,IDLE,absent,absent,off)：{text}")
    parts = [p.strip() for p in t[1:-1].split(",")]
    if len(parts) != len(VECTOR):
        raise ValueError(f"--start 分量数应为 {len(VECTOR)}：{text}")
    for name, val in zip(VECTOR, parts):
        if val not in COMPONENT_VOCAB[name]:
            raise ValueError(f"--start 分量 {name} 非法：{val}"
                             f"（合法值 {sorted(COMPONENT_VOCAB[name])}）")
    return tuple(parts)


def serials_env(serials: dict[str, str]) -> dict[str, str]:
    """内部 serials 字典 → bash 委派 env（PHONE_SERIAL/CAR_SERIAL）。"""
    return {"PHONE_SERIAL": serials["phone"], "CAR_SERIAL": serials["car"]}


def detect_state(serials: dict[str, str], log: Logger | None = None) -> tuple[str, ...]:
    """复合态检测（委派 asserts.sh probe state；UNKNOWN 是合法分量，§1.5.3）。"""
    try:
        result = bash(["bash", ASSERTS_SH, "probe", "state"],
                      env_extra=serials_env(serials), timeout=PROBE_TIMEOUT_S)
    except subprocess.TimeoutExpired:
        if log:
            log.warn(f"probe state 超时（{PROBE_TIMEOUT_S}s）——复合态记全 UNKNOWN")
        return tuple("UNKNOWN" for _ in VECTOR)
    parsed: dict[str, str] = {}
    for kv in (result.stdout or "").strip().split():
        if "=" in kv:
            key, val = kv.split("=", 1)
            parsed[key] = val.strip()
    if result.returncode != 0 or len(parsed) < len(VECTOR):
        if log:
            log.warn(f"probe state 异常（rc={result.returncode}, out={result.stdout!r}）"
                     "——缺失分量记 UNKNOWN")
    return tuple(parsed.get(k, "UNKNOWN") for k in PROBE_KEYS)


def requires_ok(op: dict[str, Any], state: Sequence[str]) -> bool:
    """op requires 对复合态的细剪（UNKNOWN 仅当 requires 显式列出才满足）。"""
    for key, want in (op.get("requires") or {}).items():
        if key not in VECTOR:
            continue
        cur = state[VECTOR.index(key)]
        if isinstance(want, list):
            if cur not in want:
                return False
        elif cur != want:
            return False
    return True


def applicable(op: dict[str, Any], state: Sequence[str], reg: Registry) -> bool:
    """运行时适用性 = requires 细剪 + allowed 粗剪（car_state 无 allowed 条目时
    放行 requires 已通过的 op——ERROR/UNKNOWN 态下 connect 等恢复 op 要能跑）。"""
    if not requires_ok(op, state):
        return False
    car = state[0]
    if car in reg.allowed_map and op["id"] not in reg.allowed_map[car]:
        return False
    return True


def check_expect(op: dict[str, Any], rc: int | None,
                 state: Sequence[str]) -> list[str]:
    """产品级预期判定（registry expect；ops.sh 不做此判定——关注点分离）。

    - rc（expect.rc）：ok=必须 0（2=SKIPPED(env) 不算产品违反）；fail=必须非 0
      （0=unexpected-pass）；either=不判。rc=None（python 侧硬超时）→ op-timeout。
    - state_after_any：元组条目 = 整态精确匹配；裸分量条目 = 按 op target 取
      car_state/phone_state，必须在允许集合（UNKNOWN 仅当集合显式列出才合法）。
    """
    codes: list[str] = []
    if rc is None:
        return ["op-timeout"]
    exp = op.get("expect") or {}
    want = exp.get("rc", "either")
    if want == "ok":
        if rc == 2:
            return codes          # SKIPPED(env)：环境缺能力，非产品违反（registry 语义）
        if rc != 0:
            codes.append("expect-rc")
    elif want == "fail" and rc == 0:
        codes.append("unexpected-pass")
    allowed = exp.get("state_after_any") or []
    if allowed:
        tuples = [a for a in allowed if a.startswith("(")]
        if tuples:
            if state_str(state) not in tuples:
                codes.append("state-after")
        else:
            for comp in TARGET_COMPONENT.get(op.get("target", "car"), ("car_state",)):
                if state[VECTOR.index(comp)] not in allowed:
                    codes.append("state-after")
    return codes


# ── op 执行 / 记账 ────────────────────────────────────────────────────────

@dataclass
class OpResult:
    """一次 ops.sh run 委派的结果。rc=None 表示 python 侧硬超时。"""
    rc: int | None
    elapsed_s: float
    timed_out: bool
    hard_s: int
    out: str = ""


def run_op(reg: Registry, op_id: str, ctx: str, serials: dict[str, str],
           run_dir: Path, log: Logger, ip_env: dict[str, str]) -> OpResult:
    """经 bash 委派执行 op（动作层唯一入口；python 不直连 adb，P3）。

    单 op 硬超时 = settle_s + 60（§1.5.4）：内层 coreutils timeout 自裁（bash
    看门狗），外层 python subprocess timeout 兜底；超时即杀并记 op-timeout。
    """
    settle_s = reg.settle(op_id)
    hard = settle_s + OP_TIMEOUT_SLACK
    env = {**serials_env(serials),
           "E2E_RUN_DIR": bs(run_dir),
           "E2E_OP_RESULT": bs(run_dir / "ops-result.jsonl"),
           "E2E_SCENARIO_ID": ctx}
    env.update(ip_env)
    inner = f"timeout -k 10 {hard} bash {OPS_SH} run {shlex.quote(op_id)}"
    t0 = time.monotonic()
    try:
        result = bash(["bash", "-c", inner], env_extra=env, timeout=hard + 30)
        rc: int | None = result.returncode
        out = (result.stdout or "") + (result.stderr or "")
        # coreutils timeout 约定：124=TERM 裁掉；配合耗时确认是超时而非恰好 124
        timed_out = rc == 124 and (time.monotonic() - t0) >= hard - 2
    except subprocess.TimeoutExpired as exc:
        rc, out, timed_out = None, _expired_text(exc), True
    elapsed = time.monotonic() - t0
    if timed_out:
        log.err(f"✗ {op_id} 硬超时（>{hard}s = settle {settle_s}+{OP_TIMEOUT_SLACK}）")
    elif rc == 2:
        log.warn(f"↷ {op_id} rc=2（用法/环境错 → SKIPPED(env)，不计产品违反）")
    elif rc != 0:
        log.err(f"✗ {op_id} rc={rc}（{elapsed:.0f}s）")
    else:
        log.info(f"✓ {op_id} rc=0（{elapsed:.0f}s）")
    # ops.sh 内部 log_step/log_ok 明细另存（op 的 stdout/stderr 全量）
    safe_ctx = re.sub(r"[^A-Za-z0-9._-]", "-", ctx)
    (run_dir / f"op-{safe_ctx}-{op_id}.log").write_text(clean_out(out), encoding="utf-8")
    return OpResult(rc, elapsed, timed_out, hard, out)


def restore_neutral(reg: Registry, ctx: str, serials: dict[str, str], run_dir: Path,
                    log: Logger, ip_env: dict[str, str]) -> bool:
    """setup/restore 唯一实现：ops.sh run neutral（六项核验不过 = 环境坏）。"""
    result = run_op(reg, "neutral", ctx, serials, run_dir, log, ip_env)
    return (not result.timed_out) and result.rc == 0


def settle(seconds: int, flag: "StopFlag | None" = None) -> None:
    """op 后静默窗口（真实时间睡眠；信号置位则提前结束，§1.5.4/P1）。"""
    deadline = time.monotonic() + seconds
    while True:
        left = deadline - time.monotonic()
        if left <= 0:
            return
        time.sleep(min(1.0, left))
        if flag is not None and flag.is_set():
            return


def seq_append(run_dir: Path, sid: str, op_id: str, rc: int | None,
               elapsed_s: float, note: str = "") -> None:
    """sequence.jsonl 追加一行（全局 op 序列；L3 回放/导出 L1 数据源）。"""
    record: dict[str, Any] = {"t": time.strftime("%H:%M:%S"), "sid": sid, "op": op_id,
                              "rc": rc, "elapsed_s": round(elapsed_s, 1)}
    if note:
        record["note"] = note
    with (run_dir / "sequence.jsonl").open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(record, ensure_ascii=False) + "\n")


def violation_append(run_dir: Path, *, mode: str, round_id: str,
                     op_seq: Sequence[str], codes: Sequence[str],
                     state: Sequence[str], desc: str,
                     op_index: int | None = None) -> dict[str, Any]:
    """violations.jsonl 追加一行（export-scenario 的唯一数据源）。"""
    path = run_dir / "violations.jsonl"
    n = 0
    if path.exists():
        n = sum(1 for ln in path.read_text(encoding="utf-8").splitlines() if ln.strip())
    inv_codes = [c for c in codes if c in INV_LIGHT or c in INV_FULL_EXTRA]
    record: dict[str, Any] = {
        "id": n + 1,
        "ts": datetime.now().isoformat(timespec="seconds"),
        "mode": mode,
        "round": round_id,
        "op_seq": list(op_seq),
        "codes": list(codes),
        "invariant_codes": inv_codes,
        "state": list(state),
        "desc": desc,
    }
    if op_index is not None:
        record["op_index"] = op_index
    with path.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(record, ensure_ascii=False) + "\n")
    return record


# ── 现场冻结（P5：任何违反先冻结后 restore）───────────────────────────────

def do_freeze(tag: str, serials: dict[str, str], run_dir: Path, log: Logger) -> bool:
    """旁路取证：委派 asserts.sh freeze_scene（双端 WAKEUP + 日志/截图/dumpsys）。

    freeze_scene 读 E2E_RUN_DIR 且把 $E2E_SEQ_LOG 拷成 sequence-slice.jsonl
    （回放用）；E2E_VDLOG/E2E_CLIENTLOG 显式指到 run-dir 防快照落仓库根。
    """
    script = (SOURCE_CHAIN + " && freeze_scene "
              + shlex.quote(tag) + " " + shlex.quote(bs(run_dir)))
    env = {**serials_env(serials),
           "E2E_RUN_DIR": bs(run_dir),
           "E2E_SEQ_LOG": bs(run_dir / "sequence.jsonl"),
           "E2E_VDLOG": bs(run_dir / "vdlog.snapshot"),
           "E2E_CLIENTLOG": bs(run_dir / "clientlog.snapshot")}
    try:
        result = bash_script(script, env_extra=env, timeout=FREEZE_TIMEOUT_S)
    except subprocess.TimeoutExpired:
        log.err(f"freeze_scene({tag}) 超时（{FREEZE_TIMEOUT_S}s）——现场可能不完整")
        return False
    # 路径提取只用 stdout：本机 bash 每次往 stderr 写 132 字节 WSL 代理
    # 警告（混合编码），拼接会干扰 \S+ 提取；日志写清洗后的合并输出。
    stdout = result.stdout or ""
    (run_dir / f"freeze-{tag}.log").write_text(
        clean_out(stdout + (result.stderr or "")), encoding="utf-8")
    exact = re.search(r"现场已冻结：(\S+)", stdout)
    log.info(f"现场已冻结：{exact.group(1) if exact else run_dir / ('freeze-' + tag)}"
             f"（rc={result.returncode}）")
    return result.returncode == 0


# ── 设备在线核验与离线甄别（§2.5）─────────────────────────────────────────

def adb_state(serial: str) -> str:
    """PC 侧 adb get-state（经 bash -c 委派——python 不直连 adb；只读探测）。"""
    try:
        result = bash(["adb", "-s", serial, "get-state"], timeout=30)
    except subprocess.TimeoutExpired:
        return "timeout"
    return (result.stdout or "").strip()


def offline_sides(serials: dict[str, str]) -> list[str]:
    """返回不在线的端（phone/car）；空 = 双端在线。"""
    return [side for side in ("phone", "car") if adb_state(serials[side]) != "device"]


def recover_devices(serials: dict[str, str], run_dir: Path, log: Logger,
                    phone_ip: str | None = None) -> bool:
    """任一端离线：冻结 + adb kill-server + 重连一次作甄别（§2.5）。

    恢复 → True（调用方记 env-suspect、轮作废不计产品失败）；仍离线 → False
    （调用方冻结 + abort）。TCP 旁路（B 的 IP:5555）在 kill-server 后需重连。
    """
    do_freeze("device-offline", serials, run_dir, log)
    log.warn("设备离线——adb kill-server 后重连一次（区分产品搞掉设备 vs adb 抖动）")
    bash(["adb", "kill-server"], timeout=30)
    time.sleep(2)
    if phone_ip:
        bash(["adb", "connect", f"{phone_ip}:5555"], timeout=30)
    deadline = time.monotonic() + RECONNECT_BUDGET_S
    while time.monotonic() < deadline:
        if not offline_sides(serials):
            log.ok("设备已恢复在线（记 env-suspect，本轮作废）")
            return True
        time.sleep(2)
    log.err("重连后仍离线——冻结并 abort")
    do_freeze("device-offline-persist", serials, run_dir, log)
    return False


# ── 信号 trap ────────────────────────────────────────────────────────────

class StopFlag:
    """SIGINT/SIGTERM trap：置位后主循环在下个检查点收尾（先 freeze 再 neutralize）。"""

    def __init__(self) -> None:
        self.sig: int | None = None

    def set(self, sig: int) -> None:
        self.sig = sig

    def is_set(self) -> bool:
        return self.sig is not None


def _install_signals(flag: StopFlag) -> None:
    def handler(signum: int, _frame: Any) -> None:
        flag.set(signum)

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            signal.signal(sig, handler)
        except (ValueError, OSError):
            pass   # 非主线程等场景：无 trap 则由 python 默认行为处理


# ── 不变量检查（docs §5；档位 §3.4）───────────────────────────────────────

class InvariantChecker:
    """不变量检查：light/full 两档，全部经 asserts.sh source 委派。

    light = engine-single / port-9637 / stream-alive
    full   = light + sentinel / vd-size / no-anr / no-force-kill / no-tex0 /
             state-consistent

    实现方式（§1.5.3/§3.4）：能用 asserts.sh 原语覆盖的走 source 委派，**以退出
    码判定**（0=过，1=违反，2/python 超时=不判定并 WARN）；原语内部 ok/bad 记账
    输出另存 run-dir 的 invariant-*.log 供事后复核。只能靠 probe 的
    （state-consistent）与端口推断（port-9637）用 probe state +
    listening_on/port_established 委派组合探测。流态相关不变量仅在会话态判定，
    中立/过渡态跳过（防假阳性）。
    """

    def __init__(self, serials: dict[str, str], run_dir: Path, log: Logger):
        self.serials = serials
        self.run_dir = run_dir
        self.log = log
        self._seq = 0

    def _env(self) -> dict[str, str]:
        return {**serials_env(self.serials),
                "E2E_RUN_DIR": bs(self.run_dir),
                "E2E_VDLOG": bs(self.run_dir / "vdlog.snapshot"),
                "E2E_CLIENTLOG": bs(self.run_dir / "clientlog.snapshot")}

    def _fn(self, fn: str, args: Sequence[str] = (), *, timeout: int = 90,
            tag: str = "") -> tuple[int | None, str]:
        """source 链执行单个 asserts.sh 原语；返回 (rc|None, 合并输出)。

        仅用「函数名 + 参数」的直接调用形态：python spawn 的 bash 在
        本机 MSYS 下 `$(fn ...)` 命令替换会丢函数表（实测 command not
        found），直接调用形态可靠；组合探测一律拆成多次 _fn。
        返回值只含 stdout：本机 bash 每次往 stderr 写 132 字节 WSL
        localhost 代理警告（混合 UTF-16/UTF-8），合并会污染 Y/N 等
        应答解析；合并输出经 clean_out 清洗后才落 invariant-*.log。
        """
        self._seq += 1
        script = SOURCE_CHAIN + " && " + fn
        if args:
            script += " " + " ".join(shlex.quote(bs(a)) for a in args)
        try:
            result = bash_script(script, env_extra=self._env(), timeout=timeout)
            rc: int | None = result.returncode
            out = result.stdout or ""
            log_out = clean_out((result.stdout or "") + (result.stderr or ""))
        except subprocess.TimeoutExpired:
            rc, out = None, ""
            log_out = f"<python 侧超时 {timeout}s>"
        (self.run_dir / f"invariant-{tag or fn}-{self._seq:04d}.log").write_text(
            log_out, encoding="utf-8")
        return rc, out

    # ── 档位调度 ──────────────────────────────────────────────────────────
    def check(self, level: str | None, state: Sequence[str],
              ctx: str) -> list[str]:
        """按档位跑不变量，返回违反码列表（空 = 通过/跳过）。"""
        if level not in ("light", "full"):
            return []
        codes: list[str] = []
        codes += self._engine_single(state, ctx)
        codes += self._port_9637(state, ctx)
        codes += self._stream_alive(state, ctx)
        if level == "full":
            codes += self._sentinel(state, ctx)
            codes += self._vd_size(state, ctx)
            codes += self._no_anr(ctx)
            codes += self._no_force_kill(ctx)
            codes += self._no_tex0(ctx)
            codes += self._state_consistent(state, ctx)
        return codes

    # ── light ────────────────────────────────────────────────────────────
    def _engine_single(self, state: Sequence[str], ctx: str) -> list[str]:
        """引擎单开（docs §5：会话中恰 1 个；收尾后为空；过渡态不判定）。"""
        car, phone, engine = state[0], state[1], state[2]
        if car in ("CONNECTED", "STREAMING"):
            rc, _ = self._fn("assert_engine_single", timeout=60,
                             tag=f"{ctx}-engine-single")
            if rc is None or rc == 2:
                return []
            return [] if rc == 0 else ["engine-single"]
        if car == "IDLE" and phone == "IDLE":
            return [] if engine == "absent" else ["engine-single"]
        return []

    def _port_9637(self, state: Sequence[str], ctx: str) -> list[str]:
        """9637 一致性：会话中（含 WAITING）控制口必须 LISTEN 或 ESTABLISHED；
        双端 IDLE 必须释放（LISTEN 是瞬态，会话中按 ESTABLISHED 判，observability §A0）。"""
        car, phone = state[0], state[1]
        if car == "UNKNOWN" or phone == "UNKNOWN":
            return []
        # 两次独立委派而非命令替换组合：python spawn 的 bash -c 中
        # `$(fn ...)` 形式在本机 MSYS 下丢函数表（见模块 docstring 硬约束），
        # 直接调用形态（`bash <脚本文件>` 等价路径）实测可靠。
        rc_l, out_l = self._fn("listening_on_phone", ("9637",), timeout=60,
                               tag=f"{ctx}-port-9637-listen")
        rc_e, out_e = self._fn("established_on_phone", ("9637",), timeout=60,
                               tag=f"{ctx}-port-9637-estab")
        if rc_l is None or rc_e is None:
            self.log.warn("port-9637 探测委派超时——不判定")
            return []
        listen, estab = out_l.strip(), out_e.strip()
        if listen not in ("Y", "N") or estab not in ("Y", "N"):
            self.log.warn(
                f"port-9637 探测输出异常（listen={listen!r} estab={estab!r}）——不判定")
            return []
        up = listen == "Y" or estab == "Y"
        active = (car in ("CONNECTING", "CONNECTED", "STREAMING")
                  or phone in ("WAITING", "CONNECTED", "STREAMING"))
        if active:
            return [] if up else ["port-9637"]
        if car == "IDLE" and phone == "IDLE":
            return [] if not up else ["port-9637"]
        return []

    def _stream_alive(self, state: Sequence[str], ctx: str) -> list[str]:
        """流存活：vdlog 帧计数在窗口内增长（仅 STREAMING 判定；tex 停止增长 =
        陈旧纹理黑屏回归信号，D7：screen_off 期间同样看帧计数）。"""
        if state[0] != "STREAMING":
            return []
        rc, _ = self._fn("wait_stream_alive", ("20",), timeout=90,
                         tag=f"{ctx}-stream-alive")
        if rc is None or rc == 2:
            return []
        return [] if rc == 0 else ["stream-alive"]

    # ── full ─────────────────────────────────────────────────────────────
    def _sentinel(self, state: Sequence[str], ctx: str) -> list[str]:
        """哨兵：会话中 screen_off_timeout=2147483647（收尾基线由 neutral 负责）。"""
        if state[0] in ("CONNECTED", "STREAMING"):
            rc, _ = self._fn("assert_sentinel", ("phone", "2147483647", "30"),
                             timeout=90, tag=f"{ctx}-sentinel")
            if rc is None or rc == 2:
                return []
            return [] if rc == 0 else ["sentinel"]
        return []

    def _vd_size(self, state: Sequence[str], ctx: str) -> list[str]:
        """VD 尺寸 = 握手下发值（Starting: VD=WxH vs wm size -d <vdId>）。"""
        if state[3] != "present":
            return []
        rc, out = self._fn("vd_handshake_size", timeout=60,
                           tag=f"{ctx}-vd-handshake")
        if rc is None:
            return []
        parts = out.strip().split()
        if len(parts) != 2 or not all(p.isdigit() for p in parts):
            self.log.warn("取不到握手下发尺寸（Starting: VD=WxH）——vd-size 不判定")
            return []
        rc2, _ = self._fn("assert_vd_size_eq", (parts[0], parts[1], "15"),
                          timeout=60, tag=f"{ctx}-vd-size")
        if rc2 is None or rc2 == 2:
            return []
        return [] if rc2 == 0 else ["vd-size"]

    def _no_anr(self, ctx: str) -> list[str]:
        """无 ANR/崩溃：双端 logcat -d 落盘后扫描（空面跳过，防假通过）。"""
        files: list[Path] = []
        for side in ("phone", "car"):
            path = self.run_dir / f"logcat-{side}-{self._seq:04d}.txt"
            rc, _ = self._fn("logcat_dump", (side, bs(path)), timeout=90,
                             tag=f"{ctx}-no-anr")
            if rc == 0 and path.exists() and path.stat().st_size > 0:
                files.append(path)
            else:
                self.log.warn(f"{side} logcat 落盘失败/为空——no-anr 该面跳过")
        if not files:
            return []
        rc, _ = self._fn("assert_no_anr", tuple(bs(p) for p in files), timeout=60,
                         tag=f"{ctx}-no-anr")
        if rc is None or rc == 2:
            return []
        return [] if rc == 0 else ["no-anr"]

    def _no_force_kill(self, ctx: str) -> list[str]:
        """无强制 kill：vdlog/clientlog 归档后扫三串（D2 修正：手机端等价串）。
        无会话轮次两面皆空 = 不判定（neutral 会归档+删 vd-server.log）。"""
        files: list[Path] = []
        for fn, name in (("pull_vdlog", f"vd-server-{self._seq:04d}.log"),
                         ("pull_clientlog", f"client-{self._seq:04d}.log")):
            path = self.run_dir / name
            self._fn(fn, (bs(path),), timeout=120, tag=f"{ctx}-no-force-kill")
            if path.exists() and path.stat().st_size > 0:
                files.append(path)
        if not files:
            self.log.warn("vdlog/clientlog 均为空（无会话轮次）——no-force-kill 不判定")
            return []
        rc, _ = self._fn("assert_no_force_kill", tuple(bs(p) for p in files),
                         timeout=60, tag=f"{ctx}-no-force-kill")
        if rc is None or rc == 2:
            return []
        return [] if rc == 0 else ["no-force-kill"]

    def _no_tex0(self, ctx: str) -> list[str]:
        """无 tex=0 静止（黑屏回归信号；vdlog 快照为空 rc=2 → 不判定）。"""
        rc, _ = self._fn("vdlog_scan", ("tex=0", "--expect-zero"), timeout=120,
                         tag=f"{ctx}-no-tex0")
        if rc is None or rc == 2:
            return []
        return [] if rc == 0 else ["no-tex0"]

    def _state_consistent(self, state: Sequence[str], ctx: str) -> list[str]:
        """双端状态一致（probe 复合态推断；UNKNOWN 分量不判定）。"""
        car, phone = state[0], state[1]
        if car == "UNKNOWN" or phone == "UNKNOWN":
            return []
        return [] if (car, phone) in CONSISTENT_PAIRS else ["state-consistent"]


# ── L2/L3 计划 ───────────────────────────────────────────────────────────

def plan_l2(reg: Registry, depth: int = 3, sample: float = DEFAULT_SAMPLE,
            seed: int = DEFAULT_SEED,
            start: tuple[str, ...] | None = None) -> tuple[list[Round], int]:
    """L2 计划（§3.4）：allowed 粗剪 + requires 细剪（静态部分：首 op 对起点必须
    可满足）；depth-2 全排列、depth-3 按 sample 抽样；random.Random(seed) 固定
    可复现。返回 (轮次列表, 被 requires 剪掉的首 op 组合数)。"""
    rng = random.Random(seed)
    starts = [start] if start else reg.start_states
    rounds: list[Round] = []
    pruned = 0
    for st in starts:
        subset = list(reg.allowed_map.get(st[0], []))
        if not subset:
            continue
        usable = [oid for oid in subset if requires_ok(reg.op(oid), st)]
        pruned += len(subset) - len(usable)
        for seq in itertools.permutations(usable, 2):
            rounds.append(Round(st, seq, 2))
        if depth >= 3 and len(usable) >= 3:
            triples = list(itertools.permutations(usable, 3))
            k = min(len(triples), max(1, round(len(triples) * sample)))
            for seq in rng.sample(triples, k):
                rounds.append(Round(st, seq, 3))
    return rounds, pruned


def plan_l3(reg: Registry, max_ops: int, seed: int) -> list[str]:
    """L3 抽签预览：与 run_l3 同一 rng 消耗顺序（random() 定池 → choices 定 op），
    同 seed 预览与实跑的前 max_ops 次抽签一致（实跑额外按实时态 requires 重抽）。"""
    rng = random.Random(seed)
    normal = [o for o in reg.op_list if o["class"] == "normal"]
    fault = [o for o in reg.op_list if o["class"] == "fault"]
    seq: list[str] = []
    for _ in range(max_ops):
        pool = fault if rng.random() < FAULT_RATIO else normal
        if not pool:
            break
        seq.append(rng.choices(pool, weights=[o["weight"] for o in pool])[0]["id"])
    return seq


# ── L2/L3 实跑 ───────────────────────────────────────────────────────────

def reach_streaming(serials: dict[str, str], run_dir: Path, log: Logger, ctx: str,
                    ip_env: dict[str, str]) -> bool:
    """复用 scenarios/_common.sh 的 reach_streaming（P3 单一实现：与 S01 同款字节）。"""
    script = SOURCE_CHAIN + " && source scripts/scenarios/_common.sh && reach_streaming"
    env = {**serials_env(serials),
           "E2E_RUN_DIR": bs(run_dir),
           "E2E_SCENARIO_ID": ctx,
           "E2E_OP_RESULT": bs(run_dir / "ops-result.jsonl")}
    env.update(ip_env)
    try:
        result = bash_script(script, env_extra=env, timeout=REACH_TIMEOUT_S)
    except subprocess.TimeoutExpired:
        log.err(f"reach_streaming 超时（{REACH_TIMEOUT_S}s）")
        return False
    safe_ctx = re.sub(r"[^A-Za-z0-9._-]", "-", ctx)
    (run_dir / f"reach-{safe_ctx}.log").write_text(
        clean_out((result.stdout or "") + (result.stderr or "")), encoding="utf-8")
    if result.returncode != 0:
        log.err("reach_streaming 失败（明细见 reach-*.log）")
        return False
    log.ok("reach_streaming 完成（健康会话）")
    return True


def setup_to_start(reg: Registry, start: tuple[str, ...], ctx: str,
                   serials: dict[str, str], run_dir: Path, log: Logger,
                   ip_env: dict[str, str]) -> str:
    """建立本轮起点态，返回 ok / void / abort。

    设计偏差说明（相对 §3.4 伪代码的「neutralize → 起点核验」）：伪代码每轮先
    neutralize 再核验，非中立起点（STREAMING）将恒 VOID、白跑一半计划。本实现对
    registry 的非中立起点在 neutralize 之后追加 reach_streaming（_common.sh 单一
    实现），核验不符仍记 VOID 不计失败——VOID 语义不变，只是让流态起点轮真正
    有机会开跑。任意 --start（非 registry 起点）仅中立化后核验，不符即 VOID。
    """
    if not restore_neutral(reg, f"{ctx}-setup", serials, run_dir, log, ip_env):
        log.err("起点建立失败：neutralize 不过（六项核验 = 环境坏，ENV_BROKEN）")
        return "abort"
    if start == NEUTRAL_STATE:
        return "ok"
    if start in reg.start_states:
        log.info(f"建立会话起点 {fmt_state(start)}（reach_streaming）……")
        return "ok" if reach_streaming(serials, run_dir, log, f"{ctx}-setup", ip_env) \
            else "void"
    return "ok"


def run_l2(reg: Registry, rounds: list[Round], serials: dict[str, str],
           run_dir: Path, log: Logger, *, budget_s: int, max_ops: int,
           watchdog_s: int, flag: StopFlag,
           ip_env: dict[str, str]) -> tuple[str, dict[str, int]]:
    """L2 状态机遍历主循环（§3.4 run_round + §2.5 中止/暂停规则）。

    每轮：设备在线核验 → 建立起点态 → 起点核验（mismatch=VOID 不计失败）→
    op（requires/allowed 细剪 → run_op 硬超时 settle+60 → settle → detect →
    check_expect + light 不变量）→ full 不变量 → 轮预算 → 违反则**先冻结后
    restore**（P5）→ restore neutral。轮预算只计 op 阶段（neutralize/reach 是
    setup 环境成本，不计产品超时判据）。
    """
    stats = {"rounds": len(rounds), "pass": 0, "violated": 0, "void": 0,
             "ops": 0, "skipped": 0}
    inv = InvariantChecker(serials, run_dir, log)
    phone_ip = ip_env.get("PHONE_IP")
    t_start = time.monotonic()
    for n, rnd in enumerate(rounds, 1):
        if flag.is_set():
            break
        if watchdog_s and time.monotonic() - t_start > watchdog_s:
            log.err(f"L2 全局看门狗超时（{watchdog_s}s）——停止开新轮")
            do_freeze("l2-watchdog", serials, run_dir, log)
            break
        rid = f"r{n:04d}"
        ctx = f"l2-{rid}"
        log.info(f"═══ L2 轮 {rid}｜起点 {fmt_state(rnd.start)}｜ops {' → '.join(rnd.ops)}")
        # ① 设备在线核验（每轮开始，§2.5）
        if offline_sides(serials):
            if not recover_devices(serials, run_dir, log, phone_ip):
                log.err("设备仍离线——abort（exit 2）")
                return "abort", stats
            log.warn("设备离线后恢复（env-suspect）——本轮作废")
            stats["void"] += 1
            continue
        # ② 建立起点态 + 起点核验
        status = setup_to_start(reg, rnd.start, ctx, serials, run_dir, log, ip_env)
        if status == "abort":
            do_freeze(ctx, serials, run_dir, log)
            violation_append(run_dir, mode="l2", round_id=ctx, op_seq=[],
                             codes=["env-broken"], state=NEUTRAL_STATE,
                             desc=f"L2 轮 {rid} neutralize 不过——环境坏")
            return "abort", stats
        cur = detect_state(serials, log)
        if status == "void" or cur != rnd.start:
            log.warn(f"起点核验不符：期望 {fmt_state(rnd.start)} 实测 "
                     f"{fmt_state(cur)} → VOID（不计失败）")
            stats["void"] += 1
            if not flag.is_set():
                restore_neutral(reg, f"{ctx}-void", serials, run_dir, log, ip_env)
            continue
        # ③ op 阶段（预算从这里起算）
        violations: list[str] = []
        executed: list[str] = []
        t0 = time.monotonic()
        for op_id in rnd.ops:
            if flag.is_set() or (max_ops and stats["ops"] >= max_ops):
                break
            op = reg.op(op_id)
            if not applicable(op, cur, reg):
                log.info(f"↷ 跳过 {op_id}（当前态不满足 requires/allowed：{fmt_state(cur)}）")
                seq_append(run_dir, ctx, op_id, None, 0.0, "skipped:requires")
                stats["skipped"] += 1
                continue
            result = run_op(reg, op_id, ctx, serials, run_dir, log, ip_env)
            seq_append(run_dir, ctx, op_id, result.rc, result.elapsed_s)
            stats["ops"] += 1
            executed.append(op_id)
            if flag.is_set():
                break                      # 中断短整：不判分，走收尾
            if result.timed_out:
                violations.append("op-timeout")
                break                      # 单 op 硬超时：收敛本轮
            settle(reg.settle(op_id), flag)
            cur = detect_state(serials, log)
            vs = check_expect(op, result.rc, cur)
            vs += inv.check("light", cur, f"{ctx}-{op_id}")
            if vs:
                log.err(f"✗ {op_id} 后期望/轻量不变量违反：{' '.join(vs)}"
                        f"（态 {fmt_state(cur)}）")
            violations += vs
            if cur[0] == "ERROR":
                log.warn("car=ERROR 且无恢复迹象——提前收敛本轮")
                break
        # ④ 全套不变量 + 轮预算
        if not flag.is_set():
            violations += inv.check("full", cur, f"{ctx}-final")
            used = time.monotonic() - t0
            if used > budget_s:
                violations.append("round-overrun")
                log.err(f"✗ 轮超预算 {used:.0f}s > {budget_s}s")
        # ⑤ 先冻结后 restore（P5 证据优先于清理）
        if violations:
            do_freeze(ctx, serials, run_dir, log)
            violation_append(run_dir, mode="l2", round_id=ctx, op_seq=executed,
                             codes=violations, state=cur,
                             desc=f"L2 轮 {rid}（起点 {fmt_state(rnd.start)}）："
                                  f"违反 {' '.join(violations)}")
            stats["violated"] += 1
        else:
            stats["pass"] += 1
            log.ok(f"轮 {rid} 通过（终态 {fmt_state(cur)}）")
        # ⑥ restore
        if not flag.is_set() and not restore_neutral(reg, f"{ctx}-restore", serials,
                                                     run_dir, log, ip_env):
            do_freeze(f"{ctx}-restore", serials, run_dir, log)
            violation_append(run_dir, mode="l2", round_id=ctx, op_seq=executed,
                             codes=["restore-failed"], state=cur,
                             desc=f"L2 轮 {rid} restore neutral 失败——环境坏")
            return "abort", stats
    return ("violated" if stats["violated"] else "ok"), stats


def run_l3(reg: Registry, serials: dict[str, str], run_dir: Path, log: Logger, *,
           max_ops: int, watchdog_s: int, seed: int, flag: StopFlag,
           ip_env: dict[str, str]) -> tuple[str, dict[str, int]]:
    """L3 fuzz 主循环（§3.4 run_l3 + docs §6 + §2.5 暂停规则）。

    权重随机抽 op（class=fault 概率 30%）；轻量不变量每 5 op、全套每 20 op；
    --max-ops 与 --watchdog-s 双闸；连续 3 次同 op 同失败码 → pause_and_report
    （拉黑该 op 并上报，**不 abort**）；最近 10 op 失败率 >50% → 暂停上报；
    任一端离线 → 冻结 + kill-server 重连甄别，仍离线 → abort（exit 2）。
    """
    stats = {"ops": 0, "draws": 0, "skipped": 0, "violations": 0, "paused": 0}
    rng = random.Random(seed)
    normal = [o for o in reg.op_list if o["class"] == "normal"]
    fault = [o for o in reg.op_list if o["class"] == "fault"]
    if not normal or not fault:
        log.err("注册表缺 normal/fault op——无法 L3")
        return "abort", stats
    inv = InvariantChecker(serials, run_dir, log)
    phone_ip = ip_env.get("PHONE_IP")
    t0 = time.monotonic()
    since_full = 0
    consec: dict[tuple[str, tuple[str, ...]], int] = {}
    blacklist: set[str] = set()
    executed: list[str] = []
    fail_window: list[int] = []
    # 起始中立化（环境基线）
    if not restore_neutral(reg, "l3-setup", serials, run_dir, log, ip_env):
        do_freeze("l3-setup", serials, run_dir, log)
        violation_append(run_dir, mode="l3", round_id="l3-setup", op_seq=[],
                         codes=["env-broken"], state=NEUTRAL_STATE,
                         desc="L3 起始 neutralize 不过——环境坏")
        return "abort", stats
    while max_ops <= 0 or stats["ops"] < max_ops:
        if flag.is_set():
            break
        if watchdog_s and time.monotonic() - t0 > watchdog_s:
            log.err(f"L3 全局看门狗超时（{watchdog_s}s）——冻结退出")
            do_freeze("l3-watchdog", serials, run_dir, log)
            break
        stats["draws"] += 1
        # 设备在线核验（每次抽签前）
        if offline_sides(serials):
            if not recover_devices(serials, run_dir, log, phone_ip):
                log.err("设备仍离线——abort（exit 2）")
                return "abort", stats
            continue                     # env-suspect：本次抽签作废
        pool = fault if rng.random() < FAULT_RATIO else normal
        op = rng.choices(pool, weights=[o["weight"] for o in pool])[0]
        if op["id"] in blacklist:
            log.info(f"↷ {op['id']} 已拉黑（连续失败）——本次抽签作废")
            continue
        st = detect_state(serials, log)
        if not applicable(op, st, reg):
            log.info(f"↷ 跳过 {op['id']}（当前态不满足 requires：{fmt_state(st)}）")
            seq_append(run_dir, f"l3-draw{stats['draws']:04d}", op["id"], None, 0.0,
                       "skipped:requires")
            stats["skipped"] += 1
            continue
        op_no = stats["ops"] + 1
        ctx = f"l3-op{op_no:04d}-{op['id']}"
        result = run_op(reg, op["id"], ctx, serials, run_dir, log, ip_env)
        seq_append(run_dir, ctx, op["id"], result.rc, result.elapsed_s)
        stats["ops"] += 1
        since_full += 1
        executed.append(op["id"])
        if flag.is_set():
            break                          # 中断短整：不判分，走收尾
        st_after = st
        if result.timed_out:
            vs = ["op-timeout"]
        else:
            settle(reg.settle(op["id"]), flag)
            st_after = detect_state(serials, log)
            level = "full" if since_full % 20 == 0 else \
                ("light" if since_full % 5 == 0 else None)
            vs = inv.check(level, st_after, ctx) if level else []
            vs += check_expect(op, result.rc, st_after)
        if vs:
            log.err(f"✗ {ctx} 违反：{' '.join(vs)}（态 {fmt_state(st_after)}）")
            do_freeze(ctx, serials, run_dir, log)
            violation_append(run_dir, mode="l3", round_id=ctx,
                             op_seq=executed[-OP_SEQ_SLICE:], codes=vs,
                             state=st_after, op_index=op_no,
                             desc=f"L3 op#{op_no} {op['id']}：违反 {' '.join(vs)}")
            stats["violations"] += 1
        fail_window.append(1 if vs else 0)
        if len(fail_window) > 10:
            fail_window.pop(0)
        # 连续 3 次同 op 同失败码 → pause_and_report（拉黑 + 上报，不 abort）
        key = (op["id"], tuple(sorted(vs)))
        consec[key] = consec.get(key, 0) + 1
        if consec[key] >= 3:
            log.err(f"连续 3 次同 op 同失败码：{op['id']} {' '.join(vs)}"
                    "——pause_and_report（拉黑该 op，不 abort）")
            violation_append(run_dir, mode="l3", round_id=ctx,
                             op_seq=executed[-OP_SEQ_SLICE:],
                             codes=["consecutive-fail"], state=st_after,
                             op_index=op_no,
                             desc=f"L3 op#{op_no} {op['id']} 连续 3 次同失败码 "
                                  f"{' '.join(vs)}——pause_and_report")
            stats["paused"] += 1
            blacklist.add(op["id"])
            consec.clear()
        # 失败率 >50% 持续 10 op → 暂停（疑似构建/环境坏，§2.5）
        if len(fail_window) == 10 and sum(fail_window) > 5:
            log.err("最近 10 op 失败率 >50%——疑似构建/环境坏，暂停并上报")
            violation_append(run_dir, mode="l3", round_id=ctx,
                             op_seq=executed[-OP_SEQ_SLICE:],
                             codes=["fail-rate-high"], state=st_after,
                             op_index=op_no,
                             desc="L3 最近 10 op 失败率 >50%——暂停上报")
            stats["paused"] += 1
            break
    # 收尾中立化
    if not flag.is_set() and not restore_neutral(reg, "l3-teardown", serials,
                                                 run_dir, log, ip_env):
        do_freeze("l3-teardown", serials, run_dir, log)
        violation_append(run_dir, mode="l3", round_id="l3-teardown", op_seq=[],
                         codes=["restore-failed"], state=NEUTRAL_STATE,
                         desc="L3 收尾 neutralize 失败——环境坏")
        return "abort", stats
    return ("violated" if stats["violations"] or stats["paused"] else "ok"), stats


# ── export-scenario（L3 发现 → L1 场景骨架，§1.5.5/§2.5）─────────────────

def next_sid(out_dir: Path) -> str:
    """扫描 out_dir 的 s*.sh 取 max+1（当前 S22 → S23）。"""
    mx = 0
    for path in out_dir.glob("s*.sh"):
        m = re.match(r"^s(\d+)-", path.name)
        if m:
            mx = max(mx, int(m.group(1)))
    return f"s{mx + 1:02d}"


def slugify(text: str) -> str:
    """op 序列 → 文件名 slug（小写、非字母数字转连字符、限长 48）。"""
    slug = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")
    return slug[:48].strip("-") or "violation"


def render_scenario(sid: str, v: dict[str, Any]) -> str:
    """violation 记录 → L1 场景骨架文本（@post 与断言细节留人工补全）。"""
    op_seq = v.get("op_seq") or []
    codes = v.get("codes") or []
    inv_codes = [c for c in codes if c in INV_LIGHT or c in INV_FULL_EXTRA]
    other = [c for c in codes if c not in inv_codes]
    ops_body = "\n".join(f"  run_op {oid}" for oid in op_seq) \
        or "  # TODO(人工)：补 op 序列（该 violation 未记录 op_seq）"
    lines: list[str] = []
    for code in inv_codes:
        lines.append(f"  # ── 违反码 {code} ──")
        lines.append(ASSERT_SKELETON[code])
    for code in other:
        lines.append(f"  # TODO(人工)：{code} —— "
                     f"{NON_INVARIANT_HINT.get(code, '需人工判读')}")
    if not lines:
        lines.append("  # TODO(人工)：补断言（该 violation 未带违反码）")
    name = (v.get("desc") or f"L2/L3 发现：{' → '.join(op_seq)}")
    name = name.replace("{", "(").replace("}", ")")
    provenance = (f"# 来源：explore.py violation id={v.get('id')} @ {v.get('ts')}；"
                  f"mode={v.get('mode')} round={v.get('round')}；"
                  f"序列 {' → '.join(op_seq) or '（空）'}")
    return SCENARIO_TEMPLATE.format(
        sid=sid, name=name, ops=",".join(op_seq),
        refs=f"explore violation {v.get('id')}",
        provenance=provenance, ops_body=ops_body, asserts_body="\n".join(lines))


def resolve_dir_arg(arg: str | None, env_names: Sequence[str],
                    default: Path) -> Path:
    """--run-dir / 环境变量 / 缺省 三者为目录路径（相对路径按 REPO 解析）。"""
    raw = arg or next((os.environ[k] for k in env_names if os.environ.get(k)), None)
    if raw:
        path = Path(raw)
        return path if path.is_absolute() else REPO / path
    return default


# ── CLI 子命令 ───────────────────────────────────────────────────────────

def cmd_check_registry(_args: argparse.Namespace) -> int:
    """JSON ids ↔ ops.sh list 集合比对（不一致 exit 2）+ 权重 70:30±10pp 校验。"""
    print(f"注册表校验：{REGISTRY_PATH.relative_to(REPO)} ↔ ops.sh list")
    try:
        reg = Registry.load()
    except (OSError, json.JSONDecodeError) as exc:
        print(f"  ✗ 注册表读取/解析失败：{exc}", file=sys.stderr)
        return 2
    problems = reg.validate()
    try:
        ops_ids = ops_sh_list()
    except RuntimeError as exc:
        print(f"  ✗ {exc}", file=sys.stderr)
        return 2
    ids = set(reg.ops)
    miss_sh = sorted(ids - ops_ids)        # JSON 有、ops.sh 无
    miss_json = sorted(ops_ids - ids)      # ops.sh 有、JSON 无
    nw = sum(o["weight"] for o in reg.op_list if o["class"] == "normal")
    fw = sum(o["weight"] for o in reg.op_list if o["class"] == "fault")
    total = nw + fw
    ratio = nw / total if total else 0.0
    ratio_ok = total > 0 and abs(ratio - WEIGHT_TARGET) <= WEIGHT_TOL
    print(f"  JSON ops {len(ids)} 个 ↔ ops.sh list {len(ops_ids)} 个")
    print(f"  集合差：JSON−ops.sh = {miss_sh or '∅'}；ops.sh−JSON = {miss_json or '∅'}")
    print(f"  权重（internal/manual 不参与）：normal={nw} fault={fw} → "
          f"{ratio:.3f}（目标 {WEIGHT_TARGET:.2f} ± {WEIGHT_TOL:.2f}）"
          f"{'✓' if ratio_ok else '✗'}")
    print(f"  结构校验：{'通过' if not problems else f'{len(problems)} 处问题'}")
    for problem in problems:
        print(f"    ✗ {problem}")
    ok = not miss_sh and not miss_json and ratio_ok and not problems
    print("✓ check-registry 通过" if ok else "✗ check-registry 失败")
    return 0 if ok else 2


def cmd_list_ops(_args: argparse.Namespace) -> int:
    """打印 op 表（id/target/settle/class/weight/l1_refs）。"""
    reg = Registry.load()
    print(f"{'id':<14} {'target':<7} {'settle':>6} {'class':<9} {'weight':>6}  l1_refs")
    for op in reg.op_list:
        print(f"{op['id']:<14} {op['target']:<7} {op['settle_s']:>6} "
              f"{op['class']:<9} {op['weight']:>6}  {','.join(op['l1_refs'])}")
    return 0


def cmd_plan(args: argparse.Namespace) -> int:
    """只打印将执行的 op 序列，不执行（不触设备）。"""
    reg = Registry.load()
    problems = reg.validate()
    if problems:
        for problem in problems:
            print(f"  ✗ {problem}", file=sys.stderr)
        print("✗ 注册表校验失败——修复后再计划", file=sys.stderr)
        return 2
    if args.mode == "l2":
        start = None
        if args.start:
            try:
                start = parse_start(args.start)
            except ValueError as exc:
                print(f"✗ {exc}", file=sys.stderr)
                return 2
        rounds, pruned = plan_l2(reg, depth=args.depth, sample=args.sample,
                                 seed=args.seed, start=start)
        if not rounds:
            print("（计划为空：allowed 子集为空或 requires 全剪——检查注册表/--start）")
            return 0
        print(f"L2 计划（depth≤{args.depth}｜sample={args.sample}｜seed={args.seed}）")
        grouped: dict[tuple[str, ...], list[Round]] = {}
        for rnd in rounds:
            grouped.setdefault(rnd.start, []).append(rnd)
        for st, group in grouped.items():
            d2 = sum(1 for r in group if r.depth == 2)
            print(f"  起点 {fmt_state(st)}：{len(group)} 轮"
                  f"（depth-2 全排列 {d2} + depth-3 抽样 {len(group) - d2}）")
        print(f"  requires 静态细剪：{pruned} 个首 op 组合")
        total_ops = sum(len(r.ops) for r in rounds)
        print(f"  合计 {len(rounds)} 轮 / 总 op {total_ops} 个"
              "（执行时按实时态再细剪，部分 op 可能跳过）")
        for i, rnd in enumerate(rounds[:PLAN_DISPLAY_CAP], 1):
            print(f"  {i:04d}  {fmt_state(rnd.start)}  {' → '.join(rnd.ops)}")
        if len(rounds) > PLAN_DISPLAY_CAP:
            print(f"  ……（其余 {len(rounds) - PLAN_DISPLAY_CAP} 轮略）")
        return 0
    # L3 预览
    seq = plan_l3(reg, args.max_ops, args.seed)
    fault_ids = {o["id"] for o in reg.op_list if o["class"] == "fault"}
    print(f"L3 计划（max-ops={args.max_ops}｜seed={args.seed}｜"
          f"fault 注入概率 {FAULT_RATIO:.0%}）")
    for i, oid in enumerate(seq, 1):
        print(f"  {i:04d}  {oid}（{'fault' if oid in fault_ids else 'normal'}）")
    nf = sum(1 for oid in seq if oid in fault_ids)
    print(f"  合计 {len(seq)} op（normal {len(seq) - nf} / fault {nf}；"
          "执行时按实时态 requires 重抽/跳过，预览与实跑同 seed 前段一致）")
    return 0


def write_run_meta(run_dir: Path, args: argparse.Namespace,
                   serials: dict[str, str]) -> None:
    """run-meta.txt（角色/参数/起止时间/git HEAD，§2.7）。"""
    try:
        head = bash(["git", "rev-parse", "--short", "HEAD"], timeout=30).stdout.strip()
    except subprocess.TimeoutExpired:
        head = "unknown"
    lines = [
        f"mode={args.mode}",
        f"phone={serials['phone']}",
        f"car={serials['car']}",
        f"seed={args.seed}",
        f"max_ops={args.max_ops}",
        f"budget_s={args.budget_s or 'registry'}",
        f"watchdog_s={args.watchdog_s}",
        f"depth={getattr(args, 'depth', '-')}",
        f"sample={getattr(args, 'sample', '-')}",
        f"start={args.start or '-'}",
        f"begin={datetime.now().isoformat(timespec='seconds')}",
        f"python={sys.version.split()[0]}",
        f"git={head or 'unknown'}",
        f"registry={REGISTRY_PATH.name}",
    ]
    (run_dir / "run-meta.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def cmd_run(args: argparse.Namespace) -> int:
    """实跑 L2/L3（会动设备；所有设备交互经 bash 委派）。"""
    reg = Registry.load()
    problems = reg.validate()
    if problems:
        for problem in problems:
            print(f"  ✗ {problem}", file=sys.stderr)
        print("✗ 注册表校验失败——修复后再跑", file=sys.stderr)
        return 2
    phone = args.phone or os.environ.get("PHONE_SERIAL", "")
    car = args.car or os.environ.get("CAR_SERIAL", "")
    if not phone or not car:
        print("✗ 必须指定双 serial：--phone/--car 或环境 PHONE_SERIAL/CAR_SERIAL",
              file=sys.stderr)
        return 2
    if phone == car:
        print("✗ phone 与 car 不能是同一台设备", file=sys.stderr)
        return 2
    serials = {"phone": phone, "car": car}
    ip_env = {k: os.environ[k] for k in ("PHONE_IP", "CAR_IP") if os.environ.get(k)}
    # 产物目录：--run-dir > 环境 E2E_RUN_DIR > debug-logs/e2e/<时间戳>
    if args.run_dir:
        run_dir = resolve_dir_arg(args.run_dir, (), DEBUG_ROOT)
    else:
        run_dir = resolve_dir_arg(None, ("E2E_RUN_DIR",),
                                  DEBUG_ROOT / datetime.now().strftime("%Y%m%d-%H%M%S"))
    run_dir.mkdir(parents=True, exist_ok=True)
    write_run_meta(run_dir, args, serials)
    log = Logger(run_dir)
    log.info(f"运行目录：{run_dir}")
    log.info(f"模式 {args.mode}｜phone={phone} car={car}｜seed={args.seed}")
    if not ip_env.get("PHONE_IP"):
        log.warn("未提供 PHONE_IP（环境）——connect/reach_streaming 类 op 会失败"
                 "（编排层 preflight 会填充 PHONE_IP/CAR_IP）")
    flag = StopFlag()
    _install_signals(flag)
    verdict, stats = "ok", {}
    try:
        offline = offline_sides(serials)
        if offline:
            log.err(f"起始设备不在线：{offline}——先解决设备再跑")
            do_freeze("device-offline-start", serials, run_dir, log)
            return 2
        if args.mode == "l2":
            start = None
            if args.start:
                try:
                    start = parse_start(args.start)
                except ValueError as exc:
                    log.err(str(exc))
                    return 2
            rounds, pruned = plan_l2(reg, depth=args.depth, sample=args.sample,
                                     seed=args.seed, start=start)
            if not rounds:
                log.err("L2 计划为空（allowed 子集为空或 requires 全剪）——检查注册表/--start")
                return 2
            log.info(f"L2 计划：{len(rounds)} 轮"
                     f"（requires 静态剪掉 {pruned} 个首 op 组合）")
            verdict, stats = run_l2(reg, rounds, serials, run_dir, log,
                                    budget_s=args.budget_s or reg.round_budget,
                                    max_ops=args.max_ops, watchdog_s=args.watchdog_s,
                                    flag=flag, ip_env=ip_env)
        else:
            verdict, stats = run_l3(reg, serials, run_dir, log, max_ops=args.max_ops,
                                    watchdog_s=args.watchdog_s, seed=args.seed,
                                    flag=flag, ip_env=ip_env)
    except Exception as exc:   # 最后防线：单轮失败的语义由循环内部保证
        log.err(f"未预期异常：{exc!r}")
        do_freeze("exception", serials, run_dir, log)
        verdict = "abort"
    finally:
        if flag.is_set():
            log.warn(f"收到信号 {flag.sig}——先冻结后中立化再退出")
            do_freeze("interrupt", serials, run_dir, log)
            restore_neutral(reg, "interrupt", serials, run_dir, log, ip_env)
    if args.mode == "l2":
        log.info(f"汇总：轮次 {stats.get('rounds', 0)}｜通过 {stats.get('pass', 0)}"
                 f"｜违反 {stats.get('violated', 0)}｜VOID {stats.get('void', 0)}"
                 f"｜执行 op {stats.get('ops', 0)}｜跳过 {stats.get('skipped', 0)}")
    else:
        log.info(f"汇总：执行 op {stats.get('ops', 0)}｜抽签 {stats.get('draws', 0)}"
                 f"｜跳过 {stats.get('skipped', 0)}｜违反 {stats.get('violations', 0)}"
                 f"｜暂停上报 {stats.get('paused', 0)}")
    log.info(f"违反记录：{run_dir / 'violations.jsonl'}")
    log.info(f"op 序列：{run_dir / 'sequence.jsonl'}")
    if verdict == "abort":
        return 2
    if verdict == "violated":
        return 1
    if flag.is_set():
        return 130 if flag.sig == signal.SIGINT else 143
    return 0


def cmd_freeze(args: argparse.Namespace) -> int:
    """旁路取证：调 asserts.sh 的 freeze_scene（唯一副作用 = 双端 WAKEUP）。"""
    if not re.fullmatch(r"[A-Za-z0-9._-]+", args.tag):
        print("✗ tag 只能含字母数字和 . _ -（会成为 freeze-<tag>/ 目录名）",
              file=sys.stderr)
        return 2
    phone = args.phone or os.environ.get("PHONE_SERIAL", "")
    car = args.car or os.environ.get("CAR_SERIAL", "")
    if not phone or not car:
        print("✗ 必须指定双 serial：--phone/--car 或环境 PHONE_SERIAL/CAR_SERIAL",
              file=sys.stderr)
        return 2
    run_dir = resolve_dir_arg(args.run_dir, ("E2E_RUN_DIR",), DEBUG_ROOT / "local")
    run_dir = Path(run_dir)
    run_dir.mkdir(parents=True, exist_ok=True)
    log = Logger(run_dir)
    ok = do_freeze(args.tag, {"phone": phone, "car": car}, run_dir, log)
    return 0 if ok else 1


def cmd_export_scenario(args: argparse.Namespace) -> int:
    """violations.jsonl 一条 → L1 场景骨架（未经人工确认不得当绿用例，§2.5）。"""
    run_dir = resolve_dir_arg(args.run_dir, ("E2E_RUN_DIR",), DEBUG_ROOT / "local")
    vfile = run_dir / "violations.jsonl"
    if not vfile.is_file():
        print(f"✗ 找不到 {vfile}（--run-dir 指到含 violations.jsonl 的运行目录）",
              file=sys.stderr)
        return 2
    records: list[dict[str, Any]] = []
    for line in vfile.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            records.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    if not records:
        print(f"✗ {vfile} 无有效记录", file=sys.stderr)
        return 2
    if args.violation_id in ("last", "latest"):
        record = records[-1]
    else:
        try:
            idx = int(args.violation_id)
        except ValueError:
            print(f"✗ violation-id 需为 0 起整数索引或 last（现有 {len(records)} 条）",
                  file=sys.stderr)
            return 2
        if not 0 <= idx < len(records):
            print(f"✗ 索引越界：{idx}（共 {len(records)} 条，0 起）", file=sys.stderr)
            return 2
        record = records[idx]
    out_dir = Path(args.out_dir)
    if not out_dir.is_absolute():
        out_dir = REPO / out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    sid = next_sid(out_dir)
    slug = slugify("-".join(record.get("op_seq") or []))
    path = out_dir / f"{sid}-{slug}.sh"
    if path.exists():
        print(f"✗ 目标已存在：{path}（防覆盖，请先处理）", file=sys.stderr)
        return 2
    path.write_text(render_scenario(sid, record), encoding="utf-8")
    print(f"✓ 骨架已导出：{path}")
    print("  ⚠️ 未经人工确认不得当绿用例（§2.5 假阴性防护）：@post 与断言细节必须"
          "人工补全 + 修复验证后才可提交")
    return 0


# ── 入口 ─────────────────────────────────────────────────────────────────

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="explore.py",
        description="DiLink-Auto 双手机真机 E2E — L2/L3 探索 runner"
                    "（python 只编排，设备交互全委派 bash）",
        epilog="退出码：0=无违反；1=有违反；2=用法/环境/中止；130=SIGINT；143=SIGTERM")
    sub = parser.add_subparsers(dest="cmd", required=True)

    sp = sub.add_parser("check-registry",
                        help="JSON ids ↔ ops.sh list 集合比对 + 权重 70:30±10pp 校验")
    sp.set_defaults(func=cmd_check_registry)

    sp = sub.add_parser("list-ops", help="打印 op 表（id/target/settle/class/weight/l1_refs）")
    sp.set_defaults(func=cmd_list_ops)

    sp = sub.add_parser("plan", help="只打印将执行的 op 序列，不执行")
    sp.add_argument("--mode", choices=["l2", "l3"], required=True)
    sp.add_argument("--start", default=None,
                    help='起点复合态，如 "(IDLE,IDLE,absent,absent,off)"'
                         "（L2；缺省用 registry start_states）")
    sp.add_argument("--depth", type=int, choices=[2, 3], default=3,
                    help="L2 最大深度（默认 3 = depth-2 全排列 + depth-3 按 sample 抽样）")
    sp.add_argument("--sample", type=float, default=DEFAULT_SAMPLE,
                    help="depth-3 抽样比例（默认 0.1）")
    sp.add_argument("--seed", type=int, default=DEFAULT_SEED)
    sp.add_argument("--max-ops", type=int, default=50, help="L3 预览长度（默认 50）")
    sp.set_defaults(func=cmd_plan)

    sp = sub.add_parser("run", help="实跑（会动设备！）")
    sp.add_argument("--mode", choices=["l2", "l3"], required=True)
    sp.add_argument("--max-ops", type=int, default=0,
                    help="总 op 上限（0=不限；L2 按轮累计，L3 按 op）")
    sp.add_argument("--budget-s", type=int, default=None,
                    help="L2 单轮预算秒（缺省 registry defaults.round_budget_s）")
    sp.add_argument("--watchdog-s", type=int, default=DEFAULT_WATCHDOG_S,
                    help=f"全局看门狗秒（默认 {DEFAULT_WATCHDOG_S}）")
    sp.add_argument("--run-dir", default=None,
                    help="产物目录（缺省 debug-logs/e2e/<时间戳>）")
    sp.add_argument("--phone", default=None, help="B 的 serial（缺省取环境 PHONE_SERIAL）")
    sp.add_argument("--car", default=None, help="A 的 serial（缺省取环境 CAR_SERIAL）")
    sp.add_argument("--seed", type=int, default=DEFAULT_SEED)
    sp.add_argument("--start", default=None, help="L2 起点复合态（覆盖 registry 起点）")
    sp.add_argument("--depth", type=int, choices=[2, 3], default=3)
    sp.add_argument("--sample", type=float, default=DEFAULT_SAMPLE)
    sp.set_defaults(func=cmd_run)

    sp = sub.add_parser("export-scenario",
                        help="violations.jsonl 一条 → L1 场景骨架"
                             "（未经人工确认不得当绿用例）")
    sp.add_argument("violation_id", help="violations.jsonl 的 0 起索引，或 last")
    sp.add_argument("--run-dir", default=None,
                    help="含 violations.jsonl 的运行目录（缺省取环境 E2E_RUN_DIR）")
    sp.add_argument("--out-dir", default="scripts/scenarios",
                    help="导出目录（默认 scripts/scenarios）")
    sp.set_defaults(func=cmd_export_scenario)

    sp = sub.add_parser("freeze", help="旁路取证：调 asserts.sh freeze_scene")
    sp.add_argument("tag", help="冻结标签（成为 freeze-<tag>/ 目录名）")
    sp.add_argument("--run-dir", default=None)
    sp.add_argument("--phone", default=None)
    sp.add_argument("--car", default=None)
    sp.set_defaults(func=cmd_freeze)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    sanitize_session_env()   # WorkBuddy 会话消毒（干净环境 no-op）
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except SystemExit:
        raise
    except Exception as exc:   # 顶层兜底：分类不明异常按用法/环境错退出
        print(f"✗ 未处理异常：{exc!r}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
