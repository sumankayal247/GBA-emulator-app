# Reference image asset manifest

The reference guide and implementation plan expect these files in this folder:

| Repository filename | Original uploaded filename | Description |
|---|---|---|
| `01-gameplay-portrait.jpg` | `1000189015.jpg` | Portrait gameplay / virtual handheld |
| `02-gameplay-landscape.jpg` | `1000189022.jpg` | Landscape gameplay layout |
| `03-controllers-screen.jpg` | `1000189020.jpg` | Bluetooth / USB controller page |
| `04-core-options-dialog.jpg` | `1000189019.jpg` | Core options overlay |
| `05-cheat-code-dialog.jpg` | `1000189017.jpg` | Add cheat dialog |
| `06-game-menu.jpg` | `1000189016.jpg` | In-game menu |
| `07-save-state-slots.jpg` | `1000189018.jpg` | Eight save-state slots |
| `08-library-home.jpg` | `1000189021.jpg` | Game library home screen |

## Add the supplied image files

The original-resolution screenshots are provided in the companion ZIP archive with this exact folder structure. The images are not yet committed to the repository; add them on a dedicated branch and open a PR.

1. Download and extract `GBA-emulator-reference-images.zip` at the root of a clone.
2. Create a branch and commit the screenshots:

```bash
git switch -c docs/add-reference-screenshots
# Extract the archive at the repository root, then:
git add docs/reference-images
git commit -m "docs: add emulator UI reference screenshots"
git push -u origin docs/add-reference-screenshots
```

3. Open a pull request into `main`. Do not push directly to `main`.

The archive preserves the original screenshot resolution. These files are visual references only, not production artwork or emulator ROM/BIOS assets.
