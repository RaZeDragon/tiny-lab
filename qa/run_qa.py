"""
Scripted QA playthrough for Shroomology on an Android emulator.
The game (started with --ez qa true) logs its state and every button's position each second,
so this script taps buttons by name. Saves screenshots, crash logs and frame stats to qa-out/.
"""
import json
import math
import os
import re
import subprocess
import time

PKG = "com.razedragon.tinylab"
OUT = "qa-out"
os.makedirs(OUT, exist_ok=True)
notes = []
step_no = [0]
problems = []


def adb(*args, binary=False):
    r = subprocess.run(["adb", *args], capture_output=True)
    return r.stdout if binary else r.stdout.decode(errors="replace")


def note(msg):
    print(msg, flush=True)
    notes.append(msg)


def problem(msg):
    note("**PROBLEM:** " + msg)
    problems.append(msg)


DENS = int(re.findall(r"(\d+)", adb("shell", "wm", "density"))[-1])
DP = DENS / 160.0


def dp(v):
    return v * DP


def shot(name):
    step_no[0] += 1
    fn = f"{step_no[0]:02d}_{name}.png"
    with open(os.path.join(OUT, fn), "wb") as f:
        f.write(adb("exec-out", "screencap", "-p", binary=True))
    note(f"- screenshot `{fn}`")


def raw_tap(x, y, wait=0.3):
    adb("shell", "input", "tap", str(int(x)), str(int(y)))
    time.sleep(wait)


def swipe(x1, y1, x2, y2, ms=300, wait=0.3):
    adb("shell", "input", "swipe", str(int(x1)), str(int(y1)), str(int(x2)), str(int(y2)), str(ms))
    time.sleep(wait)


def state(fresh=True):
    """The latest state the game logged (waits for a new report if fresh)."""
    if fresh:
        adb("logcat", "-c")
        time.sleep(1.3)
    log = adb("logcat", "-d", "-s", "SHROOMQA:D")
    lines = [l for l in log.splitlines() if "SHROOMQA" in l and "{" in l]
    if not lines:
        return None
    try:
        return json.loads(lines[-1][lines[-1].index("{"):])
    except Exception:
        return None


def button(st, bid):
    if not st:
        return None
    for b in st.get("buttons", []):
        if b["id"] == bid:
            return b
    return None


def tap_id(bid, wait=0.4, required=True):
    st = state()
    b = button(st, bid)
    if b is None:
        if required:
            problem(f"button `{bid}` not found (tab={st and st.get('tab')}, tut={st and st.get('tut')})")
        return False
    raw_tap(b["x"], b["y"], wait)
    return True


def crashes():
    log = adb("logcat", "-d", "-b", "crash")
    return log if ("FATAL EXCEPTION" in log or PKG in log) else ""


def check_crash(where):
    c = crashes()
    if c:
        problem(f"CRASH after: {where}")
        with open(os.path.join(OUT, "crash.txt"), "a") as f:
            f.write(f"=== {where} ===\n{c}\n")
        adb("logcat", "-b", "crash", "-c")


def launch():
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--ez", "qa", "true")


# ---------------------------------------------------------------- fresh install
note("## Fresh install & tutorial")
adb("shell", "pm", "clear", PKG)
adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
adb("logcat", "-b", "crash", "-c")
launch()
time.sleep(1.8)
shot("splash")
time.sleep(4.5)
st = state()
W, H = st["w"], st["h"]
note(f"Game view {W}x{H} px, density {DENS} (dp={DP:.3f}) = {W / DP:.0f}x{H / DP:.0f} dp")
FLASK = (W / 2, H * 0.29)
shot("tutorial_welcome")
check_crash("launch")

tap_id("tut_next", 0.8)
shot("tutorial_go_lab")
tap_id("nav1", 1.0)
shot("lab_tutorial_drag")
st = state()
for ing in ("ing0", "ing1"):
    b = button(st, ing)
    if b:
        swipe(b["x"], b["y"], FLASK[0], FLASK[1], 700, 0.8)
    else:
        problem(f"{ing} missing")
shot("lab_two_ingredients")
st = state()
note(f"tutorial step after adding ingredients: {st['tut']} (expect 3 = tap BREW)")
tap_id("brew", 0.3)
shot("brewing_start")
R = dp(105)
for k in range(40):
    a1 = k * math.pi / 6
    a2 = a1 + math.pi / 6
    swipe(FLASK[0] + math.cos(a1) * R, FLASK[1] + math.sin(a1) * R,
          FLASK[0] + math.cos(a2) * R, FLASK[1] + math.sin(a2) * R, 35, 0.0)
    if k == 12:
        shot("stirring")
time.sleep(0.4)
shot("popup_mystery")
time.sleep(1.8)
shot("popup_reveal")
check_crash("brew")
tap_id("p_see", 1.2)
shot("garden_selected")
tap_id("feed", 0.8)
shot("feed_panel")
tap_id("food1", 1.2)
shot("tutorial_done_card")
st = state()
note(f"tutorial step after feeding: {st['tut']} (expect 7 = done card)")
tap_id("tut_next", 1.0)
st = state()
if st["tut"] != -1:
    problem(f"tutorial didn't finish (step {st['tut']})")
shot("tutorial_finished")
check_crash("tutorial")

# ---------------------------------------------------------------- garden interactions
note("\n## Garden interactions")
raw_tap(W / 2, H * 0.14, 1.0)   # tap the sky: deselect
st = state()
if not st["shrooms"]:
    problem("no mushrooms in the garden after the tutorial")
else:
    m = st["shrooms"][0]
    raw_tap(m["x"], m["y"], 0.25)
    shot("poke")
    raw_tap(W / 2, H * 0.14, 0.5)
    m = state()["shrooms"][0]
    swipe(m["x"], m["y"], m["x"] + 1, m["y"] + 1, 1100, 0.2)   # long press = hug
    shot("hug")
    m = state()["shrooms"][0]
    for k in range(8):
        swipe(m["x"] - dp(22), m["y"], m["x"] + dp(22), m["y"], 110, 0.0)
    shot("rub_pet")
    m = state()["shrooms"][0]
    swipe(m["x"], m["y"], m["x"] + dp(130), m["y"] - dp(150), 80, 0.15)
    shot("toss_in_air")
    time.sleep(2.5)
    shot("after_toss")
    raw_tap(W / 2, H * 0.14, 0.5)
    check_crash("garden gestures")

    # info panel modes
    m = state()["shrooms"][0]
    raw_tap(m["x"], m["y"], 1.0)
    shot("info_panel")
    tap_id("style", 0.8); shot("style_panel"); tap_id("sdone", 0.6)
    tap_id("explore", 0.8); shot("explore_panel"); tap_id("ecancel", 0.6)
    tap_id("card", 1.0); shot("profile_card"); tap_id("c_close", 0.6)
    raw_tap(W / 2, H * 0.14, 0.6)
    check_crash("info panel")

tap_id("photo", 1.0); shot("photo_mode"); tap_id("unsnap", 0.8)
tap_id("decorate", 1.0); shot("decorate_tray"); tap_id("decorate", 0.8)
check_crash("photo/decorate")

# buy a ball and place it
tap_id("nav4", 0.8); tap_id("s2", 0.8); tap_id("dbuy11", 0.8)
tap_id("nav0", 0.8); tap_id("decorate", 1.0)
st = state()
b = button(st, "dec11")
if b:
    swipe(b["x"], b["y"], W / 2, H * 0.45, 800, 1.0)
    shot("ball_placed")
else:
    problem("ball not in the decorate tray after buying it")
tap_id("decorate", 0.8)
time.sleep(4)
shot("garden_with_ball")
check_crash("decorations")

# ---------------------------------------------------------------- every tab
note("\n## Tabs")
tap_id("nav2", 0.8); shot("shroomdex")
tap_id("dex1", 0.8); shot("dex_detail")
tap_id("nav3", 0.8)
for k, name in enumerate(["today", "contest", "awards", "diary", "games"]):
    tap_id(f"j{k}", 0.6); shot(f"journal_{name}")
tap_id("nav4", 0.8)
for k, name in enumerate(["ingredients", "food", "decor", "themes", "style", "lab", "event"]):
    if tap_id(f"s{k}", 0.6, required=(k < 6)):
        shot(f"shop_{name}")
check_crash("tabs")

tap_id("nav1", 0.8)
tap_id("m1", 0.8); shot("lab_crossbreed"); tap_id("m0", 0.6)
raw_tap(W * 0.13, H * 0.29 + dp(104), 0.8)   # Professor Spore
shot("professor_tip")
check_crash("lab")

# ---------------------------------------------------------------- settings
note("\n## Settings")
tap_id("gear", 1.0); shot("settings"); tap_id("set_close", 0.8)
check_crash("settings")

# ---------------------------------------------------------------- mini-games
note("\n## Mini-games")
tap_id("nav3", 0.8); tap_id("j4", 0.8)
tap_id("play0", 1.0)
for k in range(8):
    swipe(W * (0.2 + 0.6 * (k % 2)), H * 0.85, W * (0.8 - 0.6 * (k % 2)), H * 0.85, 500, 0.1)
    if k == 3:
        shot("spore_catch")
time.sleep(25)
shot("spore_catch_end")
tap_id("mg_done", 1.0)
tap_id("play1", 1.0)
for i in range(6):
    tap_id(f"mm{i}", 0.6)
shot("memory_match")
tap_id("mg_quit", 1.0)
check_crash("mini-games")

# ---------------------------------------------------------------- smoothness while idle in the garden
note("\n## Frame stats (10 s idle in the garden)")
tap_id("nav0", 0.8)
adb("shell", "dumpsys", "gfxinfo", PKG, "reset")
time.sleep(10)
for line in adb("shell", "dumpsys", "gfxinfo", PKG).splitlines():
    if any(k in line for k in ("Total frames rendered", "Janky frames", "50th percentile", "90th percentile", "99th percentile")):
        note("    " + line.strip())

# ---------------------------------------------------------------- night time
note("\n## Night")
adb("root"); time.sleep(4); adb("wait-for-device")
adb("shell", "date", time.strftime("%m%d", time.localtime()) + "2330" + time.strftime("%Y", time.localtime()) + ".00")
note("device time now: " + adb("shell", "date").strip())
adb("shell", "am", "force-stop", PKG)
launch()
time.sleep(5)
raw_tap(W / 2, H * 0.14, 0.5)
time.sleep(5)
shot("garden_night")
check_crash("night")

note("\n## Result: " + (f"{len(problems)} problem(s)" if problems else "no problems found by the script"))
for p in problems:
    note("- " + p)
with open(os.path.join(OUT, "report.md"), "w") as f:
    f.write("# Shroomology QA run\n\n" + "\n".join(notes) + "\n")
