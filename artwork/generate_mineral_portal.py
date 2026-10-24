"""Build native pixel textures and an illustrative opening/closing preview for the portal."""

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'src/main/resources/assets/ore_craft/textures/entity'
SIZE = 128
TAU = math.tau


def textures():
    frame = Image.new('RGBA', (SIZE, SIZE))
    core = Image.new('RGBA', (SIZE, SIZE))
    energy = [(7, 31, 38), (7, 55, 53), (9, 86, 69), (17, 123, 88),
              (28, 170, 119), (47, 212, 158), (100, 244, 199), (191, 255, 230)]
    for y in range(SIZE):
        for x in range(SIZE):
            dx, dy = (x + 0.5 - SIZE / 2) / (SIZE / 2), (y + 0.5 - SIZE / 2) / (SIZE / 2)
            radius, angle = math.hypot(dx, dy), math.atan2(dy, dx)
            if radius <= 1:
                wave = math.sin(angle * 3 - radius * 18) + 0.22 * math.sin(angle * 7 + radius * 12)
                brightness = max(0, min(0.999, 0.38 + wave * 0.26 + 0.18 * radius))
                color = energy[int(brightness * len(energy))]
                if radius < 0.065:
                    color = (171, 255, 224)
                core.putpixel((x, y), color + (235,))
            if not 0.755 <= radius <= 0.985:
                continue
            # Crisp metal bands, cyan inner light and regular facets match the scepter's pixel material.
            facet = int((angle + math.pi) / TAU * 20)
            phase = ((angle + math.pi) / TAU * 20) % 1
            light = -dx * 0.6 - dy * 0.8
            if radius > 0.965:
                color = (24, 41, 45, 220)
            elif radius > 0.92:
                color = (248, 218, 142, 255) if light > 0.2 else (177, 123, 56, 255)
                if phase < 0.12:
                    color = (63, 64, 54, 255)
            elif radius > 0.85:
                color = (39, 59, 60, 255)
                if (facet % 2 == 0 and 0.32 < phase < 0.68) or (0.878 < radius < 0.894 and 0.2 < phase < 0.8):
                    color = (150, 255, 218, 255)
                elif phase < 0.08:
                    color = (203, 159, 81, 255)
            elif radius > 0.805:
                color = (237, 188, 92, 255) if light > 0 else (126, 86, 40, 255)
            elif radius > 0.775:
                color = (182, 255, 227, 255)
            else:
                color = (52, 217, 163, 190)
            frame.putpixel((x, y), color)
    return frame, core


def render(frame, core, tick, close_at=None):
    progress = min(1, tick / 20)
    if close_at is not None:
        progress = min(progress, max(0, 1 - (tick - close_at) / 12))
    progress = progress * progress * (3 - 2 * progress)
    image = Image.new('RGBA', (320, 400), (29, 34, 39, 255))
    if progress <= 0:
        return image
    # This preview mirrors the renderer's proportions; it is not a Minecraft capture.
    body = Image.new('RGBA', (SIZE, SIZE))
    surface = core.rotate(math.degrees(tick * 0.035), Image.Resampling.NEAREST)
    mask = Image.new('L', (SIZE, SIZE))
    ImageDraw.Draw(mask).ellipse((1, 1, SIZE - 2, SIZE - 2), fill=255)
    surface.putalpha(mask)
    surface = surface.resize((104, 104), Image.Resampling.NEAREST)
    body.alpha_composite(surface, (12, 12))
    body.alpha_composite(frame)
    width, height = max(1, round(256 * progress)), round(326 * (0.35 + 0.65 * progress))
    body = body.resize((width, height), Image.Resampling.NEAREST)
    body.putalpha(body.getchannel('A').point(lambda value: round(value * progress)))
    image.alpha_composite(body, ((320 - width) // 2, (400 - height) // 2))
    return image


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    frame, core = textures()
    frame.save(OUTPUT / 'mineral_portal_frame.png')
    core.save(OUTPUT / 'mineral_portal_core.png')
    font = ImageFont.truetype('C:/Windows/Fonts/consola.ttf', 19)
    sheet = Image.new('RGBA', (1280, 440), (29, 34, 39, 255))
    for index, (tick, label) in enumerate(((3, 'OPENING 0.15s'), (10, 'OPENING 0.5s'), (20, 'READY 1.0s'), (36, 'CLOSING'))):
        sheet.alpha_composite(render(frame, core, tick, 30 if index == 3 else None), (index * 320, 40))
        ImageDraw.Draw(sheet).text((index * 320 + 24, 18), label, font=font, fill='#D1E8E2')
    sheet.save(ROOT / 'artwork/mineral_portal_animation_preview.png')
    frames = [render(frame, core, tick, 70 if tick >= 70 else None).convert('RGB') for tick in range(0, 84, 2)]
    frames[0].save(ROOT / 'artwork/mineral_portal_animation_preview.gif', save_all=True,
                   append_images=frames[1:], duration=100, loop=0, disposal=2)


if __name__ == '__main__':
    main()
