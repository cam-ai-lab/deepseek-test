#!/usr/bin/env bash
# Builds the EPUB: renders every mermaid diagram to a high-contrast PNG, then assembles the book.
# PNG rather than SVG because mermaid's SVG output relies on features many e-readers do not support.
set -euo pipefail
cd "$(dirname "$0")"

mkdir -p build/images
for source in diagrams/*.mmd; do
  target="build/images/$(basename "${source%.mmd}").png"
  if [[ ! -f "$target" || "$source" -nt "$target" || mermaid-config.json -nt "$target" ]]; then
    echo "diagram: $source"
    mmdc -q -i "$source" -o "$target" -c mermaid-config.json -w 900 -s 2 -b white
  fi
done

pandoc metadata.yaml chapters/*.md \
  --from markdown+fenced_divs+pipe_tables+implicit_figures \
  --to epub3 \
  --toc --toc-depth=2 \
  --split-level=1 \
  --css epub.css \
  --resource-path build \
  --epub-title-page=true \
  --output testing-from-the-inside-out.epub

echo "built: $(pwd)/testing-from-the-inside-out.epub"
