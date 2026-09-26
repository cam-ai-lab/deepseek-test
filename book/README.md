# Testing from the Inside Out

A hands-on book about software testing that uses this repository as its lab. It covers the four axes
of testing (scope, visibility, purpose, environment/phase), every tier of this project's suite, the
Cucumber black-box migration, performance, pipelines and test strategy.

- **Read it:** `testing-from-the-inside-out.epub` (EPUB 3, e-ink and phone friendly).
- **Parts 1–2** describe the suite before the black-box migration: `git checkout before-blackbox`.
- **Parts 3–4** describe `main`: the `blackbox/` module, the image, the pipeline.

## Building

Needs [pandoc](https://pandoc.org) 3.x and [mermaid-cli](https://github.com/mermaid-js/mermaid-cli)
(`mmdc`).

```bash
./build.sh
```

`build.sh` renders every `diagrams/*.mmd` to a high-contrast PNG (into `build/`, which is ignored) and
assembles `chapters/*.md` into the EPUB. Chapters are ordered by their numeric prefix: `1xx` Part 1,
`2xx` Part 2, `3xx` Part 3, `4xx` Part 4, `9xx` appendices.
