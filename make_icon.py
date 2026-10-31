from PIL import Image
import os

SRC = r"D:\Downloads\鸭头.png"
RES = r"C:\Users\HK-info\WorkBuddy\2026-10-30-11-53-57\RoundTrace\app\src\main\res"

im = Image.open(SRC).convert("RGBA")
w, h = im.size
px = im.load()

# 1. 找非近白像素的 bbox（自动裁掉白边）
def is_near_white(r, g, b, a):
    if a < 10:
        return True
    return r > 240 and g > 240 and b > 235

min_x, min_y, max_x, max_y = w, h, 0, 0
for y in range(h):
    for x in range(w):
        r, g, b, a = px[x, y]
        if not is_near_white(r, g, b, a):
            if x < min_x: min_x = x
            if x > max_x: max_x = x
            if y < min_y: min_y = y
            if y > max_y: max_y = y

print(f"content bbox: ({min_x},{min_y})-({max_x},{max_y})")
cropped = im.crop((min_x, min_y, max_x + 1, max_y + 1))
cw, ch = cropped.size
print(f"cropped size: {cw}x{ch}")

# 2. 放到正方形画布，缩放到 92%（留 4% 边距）
side = max(cw, ch)
pad = int(side * 0.04)
canvas = side + pad * 2
square = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
ox = (canvas - cw) // 2
oy = (canvas - ch) // 2
square.paste(cropped, (ox, oy), cropped)

# 3. 把近白背景转透明（抗锯齿边缘用 alpha 渐变）
sp = square.load()
for y in range(canvas):
    for x in range(canvas):
        r, g, b, a = sp[x, y]
        if a == 0:
            continue
        # 近白 → 透明
        if r > 235 and g > 235 and b > 225:
            sp[x, y] = (r, g, b, 0)
        # 浅灰白过渡 → 半透明
        elif r > 215 and g > 215 and b > 205:
            sp[x, y] = (r, g, b, int(a * 0.4))

# 4. 输出各密度
sizes = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

for density, sz in sizes.items():
    out = square.resize((sz, sz), Image.LANCZOS)
    for name in ("ic_launcher.png", "ic_launcher_round.png"):
        p = os.path.join(RES, f"mipmap-{density}", name)
        out.save(p, "PNG")
        print(f"saved {p} ({sz}x{sz})")

print("done")
