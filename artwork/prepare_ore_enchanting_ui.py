"""Convert the supplied UI concept into a background with empty live content regions."""

from pathlib import Path

from PIL import Image, ImageDraw


root = Path(__file__).resolve().parents[1]
source = root / "artwork/ore_enchanting_ui_reference.png"
target = root / "src/main/resources/assets/ore_craft/textures/gui/ore_enchanting_table.png"
image = Image.open(source).convert("RGBA")
draw = ImageDraw.Draw(image)

# The illustrative tools and mineral cube become actual menu slots. Keep their frames.
draw.rectangle((216, 252, 313, 344), fill="#172638")
draw.rectangle((216, 421, 313, 513), fill="#172638")
draw.rectangle((532, 345, 629, 451), fill="#182638")

# Clear text and icons from the seven sample rows. The GUI now renders real enchantment data.
for row in range(7):
    top = 244 + row * 102
    draw.rectangle((868, top, 1386, top + 79), fill="#182431")

# The search prompt is also rendered by a real editable widget.
draw.rectangle((924, 166, 1307, 206), fill="#121d29")
# The live scroll position replaces the fixed cyan thumb in the concept art.
draw.rectangle((1411, 249, 1430, 942), fill="#101c28")

# Only the white exterior is connected to this corner, so this preserves bright frame details.
ImageDraw.floodfill(image, (0, 0), (0, 0, 0, 0), thresh=30)
image.save(target, optimize=True)
