# -*- coding: utf-8 -*-
"""设备侧轮询器：哨兵写入后，引擎多久才真的消失？

用途（2026-10-10）：桌面端 closeSession 的 gracefulStop 每次都在 6s 预算上超时、
补了一记 -9，但 devices 侧 screen_off_timeout 已被真实恢复 —— 说明 cleanup 跑完了、
瓶颈在"进程退出本身"。为了把"引擎自然退出延迟"和"桌面端预算"解耦，这里用一个
与桌面端无关的轮询器观察：**由人工写哨兵触发停止，没有任何人会补 -9**。

每次迭代一次 adb 往返，把 (pid, timeout, 哨兵存在与否) 打上时间戳。

用法：python debug-logs/uitest/poll_engine.py [秒数]
"""
import subprocess
import sys
import time

S = "9e779dea"
# 整条命令作为单个 argv 交给设备侧 sh；$p/$t/$s 由设备侧展开
CMD = (
    'p=$(pgrep -f "[P]ipelineServer" | head -1); '
    "t=$(settings get system screen_off_timeout); "
    "if ls /sdcard/DiLinkAuto/stop-request >/dev/null 2>&1; then s=P; else s=-; fi; "
    'echo "$p|$t|$s"'
)


def main() -> None:
    seconds = float(sys.argv[1]) if len(sys.argv) > 1 else 60.0
    t0 = time.time()
    prev = None
    print(f"--- 轮询开始 t0={time.strftime('%H:%M:%S')} span={seconds}s ---", flush=True)
    while time.time() - t0 < seconds:
        r = subprocess.run(
            ["adb", "-s", S, "shell", CMD], capture_output=True, text=True, timeout=15
        )
        line = r.stdout.strip().replace("\r", "").replace("\n", " ")
        if line != prev:  # 只在状态变化时打印，避免刷屏
            print(f"+{time.time()-t0:6.2f}s  {line}", flush=True)
            prev = line
        time.sleep(0.25)
    print("--- 轮询结束 ---", flush=True)


if __name__ == "__main__":
    main()
