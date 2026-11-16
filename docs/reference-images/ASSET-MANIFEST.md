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

The GitHub connection used for this planning change can create text files and Git objects, but it cannot transfer the uploaded image bytes directly from the chat workspace. The original-resolution screenshots are therefore provided as a separate ZIP bundle with this exact folder structure.

1. Download `GBA-emulator-reference-images.zip` from the ChatGPT response.
2. Extract it at the root of a clone of this repository.
3. Run:

```bash
git add docs/reference-images
git commit -m "docs: add emulator UI reference screenshots"
git push origin main
```

The archive preserves the original screenshot resolution. These files are visual references only, not production artwork or emulator ROM/BIOS assets.
