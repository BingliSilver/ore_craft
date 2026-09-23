"""Rebuild the conversion table background from its reference and clean panels.

Run from the project root with: python artwork/render_conversion_ui.py
"""

from collections import deque
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter


ROOT = Path(__file__).resolve().parents[1]
ARTWORK = ROOT / "artwork"
OUTPUT = (
    ROOT
    / "src/main/resources/assets/ore_craft/textures/gui/ore_conversion_table.png"
)
SIZE = (1727, 910)


def remove_checkerboard(image: Image.Image) -> Image.Image:
    """Flood only the light neutral checkerboard connected to the image edge."""
    image = image.convert("RGB")
    width, height = image.size
    pixels = image.tobytes()
    count = width * height
    candidates = bytearray(count)
    for index in range(count):
        offset = index * 3
        red, green, blue = pixels[offset : offset + 3]
        candidates[index] = min(red, green, blue) >= 185 and max(red, green, blue) - min(red, green, blue) <= 22

    background = bytearray(count)
    queue = deque()

    def enqueue(index: int) -> None:
        if candidates[index] and not background[index]:
            background[index] = 1
            queue.append(index)

    for x in range(width):
        enqueue(x)
        enqueue((height - 1) * width + x)
    for y in range(height):
        enqueue(y * width)
        enqueue(y * width + width - 1)

    while queue:
        index = queue.popleft()
        x = index % width
        if x > 0:
            enqueue(index - 1)
        if x + 1 < width:
            enqueue(index + 1)
        if index >= width:
            enqueue(index - width)
        if index + width < count:
            enqueue(index + width)

    alpha = Image.frombytes("L", (width, height), bytes(0 if value else 255 for value in background))
    image.putalpha(alpha)
    return image


def replace_panel(
    target: Image.Image,
    clean: Image.Image,
    destination: tuple[int, int, int, int],
    source: tuple[int, int, int, int],
) -> None:
    left, top, right, bottom = destination
    panel = clean.crop(source).resize((right - left, bottom - top), Image.Resampling.BICUBIC)
    # A tiny blend keeps the painted stone rim while clearing all baked text,
    # buttons, items, and slots from the reference interior.
    mask = Image.new("L", panel.size)
    ImageDraw.Draw(mask).rectangle((13, 13, panel.width - 14, panel.height - 14), fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(7))
    target.paste(panel, (left, top), mask)


def restore_pickaxe(target: Image.Image, reference: Image.Image) -> None:
    """Keep the reference's decorative pickaxe without its baked UI controls."""
    box = (515, 128, 843, 444)
    width = box[2] - box[0]
    height = box[3] - box[1]
    mask = Image.new("L", (width, height))
    ImageDraw.Draw(mask).ellipse((22, 22, width - 23, height - 23), fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(14))
    target.paste(reference.crop(box), box[:2], mask)


def main() -> None:
    reference = Image.open(ARTWORK / "ore_conversion_ui_reference.png")
    previous = Image.open(ARTWORK / "ore_conversion_ui_previous.png").convert("RGBA")
    # Crop only the checkerboard margin so the frame keeps its original ratio.
    reference = reference.crop((0, 7, reference.width, 911)).resize(SIZE, Image.Resampling.BICUBIC)
    reference = remove_checkerboard(reference)
    result = previous.copy()
    replace_panel(result, previous, (137, 119, 863, 795), (889, 101, 1627, 792))
    replace_panel(result, previous, (883, 119, 1584, 795), (889, 101, 1627, 792))
    frame_mask = Image.new("L", SIZE, 255)
    frame_draw = ImageDraw.Draw(frame_mask)
    frame_draw.rectangle((141, 119, 861, 793), fill=0)
    frame_draw.rectangle((883, 119, 1582, 793), fill=0)
    frame_mask = frame_mask.filter(ImageFilter.GaussianBlur(2))
    result = Image.composite(reference, result, frame_mask)
    restore_pickaxe(result, reference)
    result.save(OUTPUT, optimize=True)


if __name__ == "__main__":
    main()
