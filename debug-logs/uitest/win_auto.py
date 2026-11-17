# -*- coding: utf-8 -*-
"""DiLink 桌面端实机调试用 Windows UI 自动化辅助。

子命令：
  shot <out.png>            截全屏
  rect <title-substr>       查找窗口矩形（标题包含子串），打印 hwnd x y w h title
  focus <title-substr>      把窗口提到前台（SW_RESTORE + AttachThreadInput + SetForegroundWindow）
  close <title-substr>      发送 WM_CLOSE（等价于点标题栏 ×；坐标点击失手时的兜底）
  click <x> <y>             移动并单击
  dblclick <x> <y>          双击
  move <x> <y>              仅移动
  key <vk>                  发送按键（如 0x7A=F11）
  type <text>               逐字输入（仅 ASCII）
  pos                       打印当前光标位置

依赖：Pillow（截图）+ ctypes/user32（输入）。所有坐标为主屏物理像素坐标。
"""
import ctypes
import sys
import time

user32 = ctypes.windll.user32

# ── DPI 感知，保证坐标 == 物理像素 ──
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception:
    try:
        user32.SetProcessDPIAware()
    except Exception:
        pass


def shot(path):
    from PIL import ImageGrab
    img = ImageGrab.grab(all_screens=True)
    img.save(path)
    print(f"saved {path} {img.size}")


def find_windows(substr):
    """枚举顶层可见窗口，标题含 substr 的返回 (hwnd, title, rect)。"""
    results = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    def enum_proc(hwnd, lparam):
        if not user32.IsWindowVisible(hwnd):
            return True
        length = user32.GetWindowTextLengthW(hwnd)
        if length == 0:
            return True
        buf = ctypes.create_unicode_buffer(length + 1)
        user32.GetWindowTextW(hwnd, buf, length + 1)
        title = buf.value
        if substr.lower() in title.lower():
            rect = ctypes.wintypes.RECT()
            user32.GetWindowRect(hwnd, ctypes.byref(rect))
            results.append((hwnd, title, (rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top)))
        return True

    user32.EnumWindows(enum_proc, 0)
    return results


def rect_cmd(substr):
    wins = find_windows(substr)
    if not wins:
        print("NOT_FOUND")
        sys.exit(1)
    for hwnd, title, r in wins:
        print(f"{hwnd}\t{r[0]}\t{r[1]}\t{r[2]}\t{r[3]}\t{title}")


def set_pos(x, y):
    user32.SetCursorPos(int(x), int(y))


def focus(substr):
    """把窗口提到前台，返回是否成功。

    三层手段叠加（2026-10-10 实测：仅 ALT + SetForegroundWindow 在本机被前台锁拒绝，
    表现为 ok=False，且此时注入的点击会被系统吞掉）：
      1. SW_RESTORE —— 最小化时 GetWindowRect 会给 -32000 之类的假坐标；
      2. AttachThreadInput —— 把本线程输入队列临时挂到当前前台窗口的线程，
         此时调用者被视为"拥有输入焦点"，SetForegroundWindow 不再被拒（前台锁正规解）；
      3. 老式 ALT 轻拍 —— 覆盖 AttachThreadInput 被拒（跨会话/权限不足）的情形。
    """
    wins = find_windows(substr)
    if not wins:
        print("NOT_FOUND")
        sys.exit(1)
    hwnd = wins[0][0]
    user32.ShowWindow(hwnd, 9)  # SW_RESTORE
    user32.BringWindowToTop(hwnd)

    kernel32 = ctypes.windll.kernel32
    fg = user32.GetForegroundWindow()
    fg_tid = user32.GetWindowThreadProcessId(fg, None) if fg else 0
    my_tid = kernel32.GetCurrentThreadId()
    attached = False
    if fg_tid and fg_tid != my_tid:
        attached = bool(user32.AttachThreadInput(fg_tid, my_tid, True))
    if attached:
        user32.SetForegroundWindow(hwnd)
        user32.SetActiveWindow(hwnd)
        user32.AttachThreadInput(fg_tid, my_tid, False)
    else:
        user32.keybd_event(0x12, 0, 0, 0)
        user32.keybd_event(0x12, 0, 2, 0)
        user32.SetForegroundWindow(hwnd)
    time.sleep(0.15)
    fg2 = user32.GetForegroundWindow()
    print(f"focus hwnd={hwnd} fg={fg2} attached={attached} ok={fg2 == hwnd}")
    return fg2 == hwnd


def close_win(substr):
    """发送 WM_CLOSE（与点标题栏 × 同一条消息链：onClose -> exitApplication）。

    自动化的兜底手段：像素级点击失手（窗口被遮挡/尺寸意外）时仍能走真实关闭路径。
    """
    wins = find_windows(substr)
    if not wins:
        print("NOT_FOUND")
        sys.exit(1)
    hwnd = wins[0][0]
    user32.PostMessageW(hwnd, 0x0010, 0, 0)  # WM_CLOSE
    print(f"WM_CLOSE sent to hwnd={hwnd}")


def click(x, y, double=False):
    set_pos(x, y)
    time.sleep(0.05)
    user32.mouse_event(0x0002, 0, 0, 0, 0)  # LEFTDOWN
    time.sleep(0.02)
    user32.mouse_event(0x0004, 0, 0, 0, 0)  # LEFTUP
    if double:
        time.sleep(0.06)
        user32.mouse_event(0x0002, 0, 0, 0, 0)
        time.sleep(0.02)
        user32.mouse_event(0x0004, 0, 0, 0, 0)
    print(f"clicked {x},{y} double={double}")


def key(vk):
    user32.keybd_event(int(vk), 0, 0, 0)
    time.sleep(0.03)
    user32.keybd_event(int(vk), 0, 2, 0)
    print(f"key {vk}")


def type_text(text):
    for ch in text:
        vk = user32.VkKeyScanW(ord(ch))
        if vk == -1:
            print(f"skip {ch!r}")
            continue
        vk &= 0xFF
        shift = (user32.VkKeyScanW(ord(ch)) >> 8) & 1
        if shift:
            user32.keybd_event(0x10, 0, 0, 0)
        user32.keybd_event(vk, 0, 0, 0)
        user32.keybd_event(vk, 0, 2, 0)
        if shift:
            user32.keybd_event(0x10, 0, 2, 0)
        time.sleep(0.02)
    print(f"typed {text!r}")


def pos():
    pt = ctypes.wintypes.POINT()
    user32.GetCursorPos(ctypes.byref(pt))
    print(f"{pt.x},{pt.y}")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "shot":
        shot(sys.argv[2])
    elif cmd == "rect":
        rect_cmd(sys.argv[2])
    elif cmd == "focus":
        focus(sys.argv[2])
    elif cmd == "close":
        close_win(sys.argv[2])
    elif cmd == "click":
        click(int(sys.argv[2]), int(sys.argv[3]))
    elif cmd == "dblclick":
        click(int(sys.argv[2]), int(sys.argv[3]), double=True)
    elif cmd == "move":
        set_pos(int(sys.argv[2]), int(sys.argv[3]))
        print("moved")
    elif cmd == "key":
        key(int(sys.argv[2], 0))
    elif cmd == "type":
        type_text(sys.argv[2])
    elif cmd == "pos":
        pos()
    else:
        print("unknown cmd")
        sys.exit(2)
