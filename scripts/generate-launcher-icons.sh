#!/usr/bin/env bash
# Regenerate launcher resources from logo.jpeg using ImageMagick 6 or 7.
set -euo pipefail
cd "$(dirname "$0")/.."
if command -v magick >/dev/null 2>&1; then
    image_command=magick
else
    image_command=convert
fi
res=app/src/main/res
mkdir -p "$res/drawable-nodpi"
# Flood-fill only the connected JPEG corner backgrounds; keep the white printer paper.
clean_logo=(logo.jpeg -alpha on -fuzz 12% -fill none -draw
    'color 0,0 floodfill color 2047,0 floodfill color 0,2047 floodfill color 2047,2047 floodfill')
# 82dp artwork on a 108dp adaptive canvas, rendered at xxxhdpi.
"$image_command" "${clean_logo[@]}" -resize 328x328 -gravity center \
    -background none -extent 432x432 -strip -define webp:lossless=true \
    "$res/drawable-nodpi/ic_launcher_artwork.webp"
for entry in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
    density=${entry%:*}
    size=${entry#*:}
    "$image_command" "${clean_logo[@]}" -resize "${size}x${size}" -strip \
        -define webp:lossless=true "$res/mipmap-$density/ic_launcher.webp"
    # Round icons use a circular alpha mask with a matching navy background.
    "$image_command" "${clean_logo[@]}" -background '#171354' -alpha remove -alpha off \
        \( -size 2048x2048 xc:none -fill white -draw 'circle 1024,1024 1024,0' \) \
        -alpha off -compose CopyOpacity -composite -resize "${size}x${size}" \
        -strip -define webp:lossless=true "$res/mipmap-$density/ic_launcher_round.webp"
done
