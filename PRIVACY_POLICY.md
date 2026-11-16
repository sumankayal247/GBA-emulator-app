# Privacy Policy — GBA Emulator (`io.github.sumankayal247.gbaemulator`)

**Effective Date:** 2026-11-16

This Privacy Policy accurately describes the actual data flows of the **GBA Emulator** Android application (`io.github.sumankayal247.gbaemulator`).

## 1. Summary

- **No network access:** The application does **not** declare Android's `INTERNET` or `ACCESS_NETWORK_STATE` permissions and makes zero outbound network connections.
- **No accounts, analytics, ads, or crash-upload SDKs:** The application contains no analytics SDKs, advertising SDKs, tracking libraries, or remote crash-reporting services.
- **On-device storage only:** All game indexes, battery saves (SRAM), save states, screenshots, cheat entries, controller mappings, layout profiles, and optional BIOS files remain strictly inside your device's local storage.

## 2. Files and Permissions

- The application uses Android's **Storage Access Framework (SAF)** (`OpenDocument`, `OpenDocumentTree`, and `CreateDocument`).
- It requests **no broad storage permissions** (`READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, or `MANAGE_EXTERNAL_STORAGE`).
- It can access **only** the individual ROM files, folders, cover images, save backups, or optional BIOS files that you explicitly choose through the Android system picker.
- You can revoke folder access at any time by removing a folder in the **Settings** tab or through Android system settings.

## 3. Data Stored Locally on Your Device

To provide emulator functionality, the app stores the following strictly on your device inside app-private storage (`filesDir`, `cacheDir`, Room database, and DataStore):

- **Library metadata:** Game SHA-256 checksums, detected system (`GBA`/`GBC`/`GB`), titles, custom titles, favourites, play time, and persisted SAF document URIs.
- **Saves & thumbnails:** Per-game battery saves (`.sav`), 8-slot save states (`.state`, `.meta`, `.bak`), and save-state PNG thumbnails captured locally from the emulated screen.
- **Preferences & profiles:** Audio/video preferences, portrait & landscape touch layout settings, per-game cheat codes, core option overrides, and external Bluetooth/USB controller button mappings.
- **Optional user-supplied BIOS files:** Only if you explicitly import them via the Extras tab.

## 4. Data Deletion and Uninstall Notice

- Uninstalling the application causes Android to permanently delete all app-private data, including your battery saves and save states.
- Before uninstalling or switching devices, use **Export Battery Save (.sav)** from the Home library menu to store a copy in a location of your choice.
- Inside the app, removing a game from the library never deletes your original ROM file on storage; deleting saves requires a separate explicit confirmation.

## 5. Third-Party Components

This app bundles the open-source **mGBA Libretro core** (`MPL-2.0`) and standard AndroidX / Jetpack Compose libraries (`Apache-2.0`). None of these components transmit user data off the device. See `THIRD_PARTY_NOTICES.md` for full licence details.

## 6. Contact

For privacy questions or bug reports, open an issue on the project repository:
`https://github.com/sumankayal247/GBA-emulator-app/issues`
