"""
Scripted QA playthrough for Shroomology on an Android emulator.
Taps and swipes through the game, saves screenshots, and records crashes and frame stats.
Everything lands in qa-out/ (report.md + PNGs).
"""
import json
import os
import re
import subprocess
import time

PKG = "com.razedragon.tinylab"
OUT = "qa-out"
os.makedirs(OUT, exist_ok=True)
notes = []
step_no = [0]


def adb(*args, binary=False):
    r = subprocess.run(["adb", *args], capture_output=True)
    return r.stdout if binary else r.stdout.decode(errors="replace")


def note(msg):
    print(msg, flush=True)
    notes.append(msg)


size = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size")).groups()
W, H = int(size[0]), int(size[1])
DENS = int(re.findall(r"(\d+)", adb("shell", "wm", "density"))[-1])
DP = DENS / 160.0
note(f"Screen {W}x{H} px, density {DENS} (dp={DP:.3f})")


def dp(v):
    return v * DP


def shot(name):
    step_no[0] += 1
    fn = f"{step_no[0]:02d}_{name}.png"
    data = adb("exec-out", "screencap", "-p", binary=True)
    with open(os.path.join(OUT, fn), "wb") as f:
        f.write(data)
    note(f"- screenshot `{fn}`")


def tap(x, y, wait=0.6):
    adb("shell", "input", "tap", str(int(x)), str(int(y)))
    time.sleep(wait)


def swipe(x1, y1, x2, y2, ms=300, wait=0.4):
    adb("shell", "input", "swipe", str(int(x1)), str(int(y1)), str(int(x2)), str(int(y2)), str(ms))
    time.sleep(wait)


def state():
    """Latest positions the game logged in test mode."""
    log = adb("logcat", "-d", "-s", "SHROOMQA:D")
    lines = [l for l in log.splitlines() if "SHROOMQA" in l and "{" in l]
    if not lines:
        return None
    try:
        return json.loads(lines[-1][lines[-1].index("{"):])
    except Exception:
        return None


def crashes():
    log = adb("logcat", "-d", "-b", "crash")
    return log if "FATAL EXCEPTION" in log or PKG in log else ""


def check_crash(where):
    c = crashes()
    if c:
        note(f"\n**CRASH after: {where}**\n```\n{c[-4000:]}\n```")
        with open(os.path.join(OUT, "crash.txt"), "a") as f:
            f.write(f"=== {where} ===\n{c}\n")
        adb("logcat", "-b", "crash", "-c")
        return True
    return False


def launch():
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--ez", "qa", "true")


# layout helpers (mirror GameView's layout math)
navTop = H - dp(78)
topBar = dp(64)


def nav(i):
    tap((i + 0.5) * W / 5, navTop + dp(30), 1.0)


def journal_tab(k):
    tw = (W - dp(32)) / 5
    tap(dp(16) + (k + 0.5) * tw, topBar + dp(26), 0.8)


def shop_tab(k):
    tabW = (W - dp(24)) / 3
    tap(dp(12) + (k % 3 + 0.5) * tabW, topBar + dp(6) + (k // 3) * dp(44) + dp(19), 0.8)


def ingredient(i):
    gy = H * 0.45 + dp(70)
    cw = (W - dp(32)) / 3
    ch = min(dp(96), (navTop - gy - dp(8)) / 3)
    return dp(16) + (i % 3 + 0.5) * cw, gy + (i // 3 + 0.5) * ch


FLASK = (W / 2, H * 0.29)

# ---------------------------------------------------------------- fresh install
note("## Fresh install & first launch")
adb("shell", "pm", "clear", PKG)
adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
adb("logcat", "-c")
adb("logcat", "-b", "crash", "-c")
launch()
time.sleep(1.2)
shot("splash")
time.sleep(4.0)   # first launch also generates sounds
shot("tutorial_welcome")
check_crash("launch")

# Tutorial: NEXT button sits near the bottom of the welcome card
for frac in (0.3, ):
    pass
st = state()
note(f"state after launch: {st}")
# tap NEXT (card at 0.3H; try a few heights until the tutorial advances)
for dy in (186, 166, 206, 146, 226):
    tap(W / 2, H * 0.3 + dp(dy), 0.8)
    st = state()
    if st and st.get("tut") != 0:
        note(f"NEXT hit at card+{dy}dp")
        break
shot("tutorial_go_lab")
nav(1)
shot("lab_tutorial_drag")
wx, wy = ingredient(0)
sx, sy = ingredient(1)
swipe(wx, wy, FLASK[0], FLASK[1], 600, 0.8)
swipe(sx, sy, FLASK[0], FLASK[1], 600, 0.8)
shot("lab_two_ingredients")
tap(W / 2, H * 0.45 + dp(27), 0.4)   # BREW
shot("brewing_start")
# stir: lots of short swipes around the flask
import math
R = dp(105)
for k in range(36):
    a1 = k * math.pi / 6
    a2 = a1 + math.pi / 6
    swipe(FLASK[0] + math.cos(a1) * R, FLASK[1] + math.sin(a1) * R,
          FLASK[0] + math.cos(a2) * R, FLASK[1] + math.sin(a2) * R, 40, 0.02)
    if k == 14:
        shot("stirring")
time.sleep(1.0)
shot("popup_mystery")
time.sleep(1.5)
shot("popup_reveal")
check_crash("brew")
bw = min(W * 0.36, dp(160))
tap(W / 2 + dp(6) + bw / 2, H * 0.66 + dp(26), 1.2)   # SEE IT
shot("garden_selected")
by = navTop - dp(62)
bwp = (W - dp(32) - dp(16)) / 3
tap(dp(16) + bwp / 2, by - dp(31), 0.8)     # FEED
shot("feed_panel")
cw = (W - dp(32)) / 5
tap(dp(16) + 1.5 * cw, (H * 0.58 + dp(96) + by - dp(10)) / 2, 1.0)   # Compost
shot("after_feed_tutorial_done")
st = state()
note(f"state after feeding: tut={st and st.get('tut')}")
for dy in (196, 176, 216, 156, 236, 140):
    tap(W / 2, H * 0.3 + dp(dy), 0.8)
    st = state()
    if st and st.get("tut") == -1:
        note(f"LET'S GO hit at card+{dy}dp")
        break
shot("tutorial_finished")
check_crash("tutorial")

# ---------------------------------------------------------------- garden interactions
note("\n## Garden interactions")
tap(W / 2, H * 0.2, 0.6)      # deselect (tap sky)
time.sleep(2)
st = state()
note(f"state: {st}")
if st and st["shrooms"]:
    m = st["shrooms"][0]
    tap(m["x"], m["y"], 0.3)
    shot("poke")
    swipe(m["x"], m["y"], m["x"] + 2, m["y"] + 2, 900, 0.3)   # long press = hug
    shot("hug")
    st = state(); m = st["shrooms"][0]
    for k in range(6):   # rub back and forth
        adb("shell", "input", "swipe", str(int(m["x"] - dp(25))), str(int(m["y"])), str(int(m["x"] + dp(25))), str(int(m["y"])), "120")
    shot("rub_pet")
    st = state(); m = st["shrooms"][0]
    swipe(m["x"], m["y"], m["x"] + dp(150), m["y"] - dp(160), 90, 0.5)   # toss
    shot("toss_in_air")
    time.sleep(2.5)
    shot("after_toss")
    check_crash("garden gestures")
tap(W - dp(36), topBar + dp(71), 1.0)   # photo mode
shot("photo_mode")
tap(W * 0.75, navTop + dp(38), 1.0)       # close photo mode
tap(dp(74), topBar + dp(28), 1.0)         # DECORATE
shot("decorate_tray")
tap(dp(74), topBar + dp(28), 1.0)         # DONE
check_crash("photo/decorate")

# ---------------------------------------------------------------- every tab
note("\n## Tabs")
nav(2); shot("shroomdex")
tap(dp(12) + (W - dp(24)) / 10, topBar + dp(44) + dp(40), 0.6); shot("dex_detail")
nav(3)
for k, name in enumerate(["today", "contest", "awards", "diary", "games"]):
    journal_tab(k); shot(f"journal_{name}")
nav(4)
for k, name in enumerate(["ingredients", "food", "decor", "themes", "style", "lab", "event"]):
    shop_tab(k); shot(f"shop_{name}")
check_crash("tabs")

# buy a toy and a hat, then check styling
shop_tab(0)
nav(1)
tap(W / 2 + dp(75), topBar + dp(27), 0.8)   # CROSSBREED tab
shot("lab_crossbreed")
tap(W * 0.13, H * 0.29 + dp(104), 0.8)     # tap professor
shot("professor_tip")
check_crash("lab")

# ---------------------------------------------------------------- settings
note("\n## Settings")
tap(W - dp(32), dp(34), 1.0)
shot("settings")
tap(W / 2, H * 0.12 + dp(470) - dp(39), 1.0)   # DONE
check_crash("settings")

# ---------------------------------------------------------------- mini-games
note("\n## Mini-games")
nav(3); journal_tab(4)
tap(W - dp(16) - dp(74), topBar + dp(60) + dp(10) + dp(176) - dp(34), 1.0)   # PLAY spore catch
for k in range(10):
    swipe(W * (0.2 + 0.6 * (k % 2)), H * 0.85, W * (0.8 - 0.6 * (k % 2)), H * 0.85, 400, 0.1)
    if k == 4:
        shot("spore_catch")
time.sleep(26)
shot("spore_catch_end")
tap(W / 2 + dp(78), H * 0.62 + dp(26), 1.0)   # DONE
tap(W - dp(16) - dp(74), topBar + dp(60) + dp(10) + dp(190) + dp(176) - dp(34), 1.0)   # PLAY memory
gw = min(W - dp(32), dp(380)); cell = gw / 4; gx = W / 2 - gw / 2; gy = topBar + dp(70)
for i in (0, 1, 2, 3):
    tap(gx + (i % 4 + 0.5) * cell, gy + (i // 4 + 0.5) * cell * 1.15, 0.5)
shot("memory_match")
tap(W - dp(56), dp(34), 1.0)   # QUIT
check_crash("mini-games")

# ---------------------------------------------------------------- night time
note("\n## Night")
nav(0)
adb("root"); time.sleep(3)
adb("shell", "date", time.strftime("%m%d", time.localtime()) + "2330" + time.strftime("%Y", time.localtime()) + ".00")
adb("shell", "am", "force-stop", PKG)
launch()
time.sleep(4)
tap(W / 2, H / 2, 0.5)   # skip splash
time.sleep(6)
shot("garden_night")
check_crash("night")

# ---------------------------------------------------------------- performance
note("\n## Frame stats")
gfx = adb("shell", "dumpsys", "gfxinfo", PKG)
for line in gfx.splitlines():
    if any(k in line for k in ("Total frames rendered", "Janky frames", "50th percentile", "90th percentile", "95th percentile", "99th percentile")):
        note("    " + line.strip())

c = crashes()
note("\n## Result: " + ("CRASHES FOUND (see crash.txt)" if os.path.exists(os.path.join(OUT, "crash.txt")) else "no crashes"))
with open(os.path.join(OUT, "report.md"), "w") as f:
    f.write("# Shroomology QA run\n\n" + "\n".join(notes) + "\n")
