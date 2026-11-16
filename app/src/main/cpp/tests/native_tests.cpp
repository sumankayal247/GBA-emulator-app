#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <thread>
#include <vector>

#include "../core/AudioRingBuffer.h"
#include "../core/CheatValidator.h"
#include "../core/CoreOptionsStore.h"
#include "../core/EmulatorState.h"
#include "../core/InputState.h"
#include "../core/LibretroFrontend.h"
#include "../core/RomValidator.h"
#include "../core/VideoFrameBuffer.h"

namespace {

int gTestsRun = 0;
int gTestsFailed = 0;

#define CHECK(cond, msg)                                                                           \
    do {                                                                                           \
        if (!(cond)) {                                                                             \
            std::cerr << "  [FAIL] " << __func__ << ":" << __LINE__ << " — " << (msg) << "\n";     \
            ++gTestsFailed;                                                                        \
            return;                                                                                \
        }                                                                                          \
    } while (0)

#define RUN_TEST(fn)                                                                               \
    do {                                                                                           \
        const int prevFail = gTestsFailed;                                                         \
        ++gTestsRun;                                                                               \
        std::cout << "[RUN ] " #fn "\n";                                                           \
        fn();                                                                                      \
        if (gTestsFailed == prevFail) {                                                            \
            std::cout << "[PASS] " #fn "\n";                                                       \
        }                                                                                          \
    } while (0)

void writeU32Le(std::vector<uint8_t>& buf, size_t offset, uint32_t value) {
    buf[offset + 0] = static_cast<uint8_t>(value & 0xFFu);
    buf[offset + 1] = static_cast<uint8_t>((value >> 8) & 0xFFu);
    buf[offset + 2] = static_cast<uint8_t>((value >> 16) & 0xFFu);
    buf[offset + 3] = static_cast<uint8_t>((value >> 24) & 0xFFu);
}

void writeFile(const std::filesystem::path& path, const std::vector<uint8_t>& data) {
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    out.write(reinterpret_cast<const char*>(data.data()), static_cast<std::streamsize>(data.size()));
    out.flush();
}

// Builds a genuine, self-contained 32 KiB Game Boy Advance homebrew ROM (ARM7TDMI):
// - Zero copyrighted logos or assets (uses valid branch opcode + 0x96 fixed byte + complement checksum).
// - Includes "SRAM_V113" save-type marker so mGBA initializes 32 KiB cartridge SRAM.
// - Enables Mode 3 bitmap video (240x160) & PSG Channel 1 audio.
// - Writes 4 signature bytes (0x5A, 0xA5, 0x3C, 0xC3) to SRAM[0..3] on boot.
// - Polls KEYINPUT (0x04000130): paints VRAM red by default, green when A is held, blue when L is held.
std::vector<uint8_t> buildSyntheticGbaTestRom() {
    std::vector<uint8_t> rom(32u * 1024u, 0x00);

    // 0x00: ARM branch to 0x0C0 -> offset = (0xC0 - 0x08) / 4 = 0x2E => 0xEA00002E
    writeU32Le(rom, 0x00, 0xEA00002Eu);

    // 0xA0..0xAB: ASCII Title
    const char* title = "GBA_TEST_ROM";
    std::memcpy(&rom[0xA0], title, 12);
    // 0xAC..0xAF: Game Code, 0xB0..0xB1: Maker Code
    std::memcpy(&rom[0xAC], "GTST01", 6);
    // 0xB2: Fixed value 0x96 (required by GBA header & mGBA GBAIsROM)
    rom[0xB2] = 0x96;

    // 0xBD: GBA header complement checksum: -(sum(0xA0..0xBC) + 0x19) & 0xFF
    uint32_t sum = 0;
    for (size_t i = 0xA0; i <= 0xBC; ++i) {
        sum += rom[i];
    }
    rom[0xBD] = static_cast<uint8_t>((0u - (sum + 0x19u)) & 0xFFu);

    // Word-aligned mGBA SRAM save-type string marker at 0x200
    const char* sramMarker = "SRAM_V113\0\0\0";
    std::memcpy(&rom[0x200], sramMarker, 12);

    // ARM7TDMI program starting at 0x0C0:
    // r0 = 0x04000000 (I/O base)
    // r1 = 0x06000000 (VRAM Mode 3 base)
    // r2 = 0x0E000000 (SRAM base)
    size_t pc = 0x0C0;
    auto emit = [&](uint32_t insn) {
        writeU32Le(rom, pc, insn);
        pc += 4;
    };

    // mov r0, #0x04000000
    emit(0xE3A00301u);
    // mov r1, #0x06000000  (0x06 << 24 = rotate right 8)
    emit(0xE3A01406u);
    // mov r2, #0x0E000000  (0x0E << 24 = rotate right 8)
    emit(0xE3A0240Eu);

    // Set DISPCNT = 0x0403 (Mode 3 + BG2)
    emit(0xE3A03C04u);  // mov r3, #0x0400
    emit(0xE3833003u);  // orr r3, r3, #0x03
    emit(0xE1C030B0u);  // strh r3, [r0, #0x00]

    // Enable GBA PSG Sound Channel 1 (produces audible square wave)
    emit(0xE3A03080u);  // mov r3, #0x80
    emit(0xE1C038B4u);  // strh r3, [r0, #0x84] (SOUNDCNT_X master enable)
    emit(0xE3A03CFFu);  // mov r3, #0xFF00
    emit(0xE3833077u);  // orr r3, r3, #0x77
    emit(0xE1C038B0u);  // strh r3, [r0, #0x80] (SOUNDCNT_L L/R enable + max vol)
    emit(0xE3A03002u);  // mov r3, #0x02
    emit(0xE1C038B2u);  // strh r3, [r0, #0x82] (SOUNDCNT_H PSG 100%)
    emit(0xE3A03CF0u);  // mov r3, #0xF000
    emit(0xE3833080u);  // orr r3, r3, #0x80
    emit(0xE1C036B2u);  // strh r3, [r0, #0x62] (SOUND1CNT_H volume 15, duty 50%)
    emit(0xE3A03C86u);  // mov r3, #0x8600
    emit(0xE1C036B4u);  // strh r3, [r0, #0x64] (SOUND1CNT_X trigger freq)

    // Write SRAM test signature [0x5A, 0xA5, 0x3C, 0xC3] to 0x0E000000..3
    emit(0xE3A0305Au);  // mov r3, #0x5A
    emit(0xE5C23000u);  // strb r3, [r2, #0]
    emit(0xE3A030A5u);  // mov r3, #0xA5
    emit(0xE5C23001u);  // strb r3, [r2, #1]
    emit(0xE3A0303Cu);  // mov r3, #0x3C
    emit(0xE5C23002u);  // strb r3, [r2, #2]
    emit(0xE3A030C3u);  // mov r3, #0xC3
    emit(0xE5C23003u);  // strb r3, [r2, #3]

    // Main loop:
    const size_t loopPc = pc;
    // ldr r4, [r0, #0x130] (KEYINPUT, active-low)
    emit(0xE5904130u);

    // Default color r5 = 0x001F (GBA BGR555 pure RED)
    emit(0xE3A0501Fu);

    // tst r4, #0x01 (A button pressed when bit 0 == 0)
    emit(0xE3140001u);
    // moveq r5, #0x03E0 (0x3E << 4 = rotate right 28 => rot=14 -> 0x03A05E3E)
    // Simpler: moveq r5, #0x0300; orreq r5, r5, #0xE0
    emit(0x03A05C03u);  // moveq r5, #0x0300
    emit(0x038550E0u);  // orreq r5, r5, #0xE0 (pure GREEN)

    // tst r4, #0x200 (L button pressed when bit 9 == 0) -> 0x02 << 8 = rot 24 -> 0xE3140C02
    emit(0xE3140C02u);
    // moveq r5, #0x7C00 (pure BLUE)
    emit(0x03A05C7Cu);  // moveq r5, #0x7C00

    // Paint 240 pixels across scanline 0 & 1 (480 halfwords) so frame is unmistakably colored
    emit(0xE3A06000u);  // mov r6, #0
    const size_t fillLoopPc = pc;
    emit(0xE18150B6u);  // strh r5, [r1, r6]
    emit(0xE2866002u);  // add r6, r6, #2
    emit(0xE3560C04u);  // cmp r6, #0x0400 (512 pixels = >2 full scanlines)
    const int32_t fillBranchOff = static_cast<int32_t>(fillLoopPc) - static_cast<int32_t>(pc + 8);
    emit(0xBA000000u | (static_cast<uint32_t>(fillBranchOff >> 2) & 0x00FFFFFFu));  // blt fillLoop

    const int32_t mainBranchOff = static_cast<int32_t>(loopPc) - static_cast<int32_t>(pc + 8);
    emit(0xEA000000u | (static_cast<uint32_t>(mainBranchOff >> 2) & 0x00FFFFFFu));  // b loop

    return rom;
}

// Builds a genuine, self-contained 32 KiB Game Boy (DMG) or Game Boy Color (CGB) homebrew ROM:
// - Uses open 4-byte Sachen magic (0x7C, 0xE7, 0xC0, 0x00) recognized by mGBA GBIsROM (zero Nintendo logo).
// - Uses MBC1 + 8 KiB Battery RAM (cartridge type 0x03, RAM size 0x02).
// - Enables external RAM, writes signature bytes (0x42, 0x99) to SRAM[0..1], configures PSG sound,
//   renders visible CGB red palette or DMG striped tiles, and loops.
std::vector<uint8_t> buildSyntheticGbTestRom(bool isCgb) {
    std::vector<uint8_t> rom(32u * 1024u, 0x00);

    // 0x100: NOP; JP 0x0150
    rom[0x100] = 0x00;
    rom[0x101] = 0xC3;
    rom[0x102] = 0x50;
    rom[0x103] = 0x01;

    // 0x104..0x107: Open Sachen 4-byte magic recognized by mGBA GBIsROM
    rom[0x104] = 0x7C;
    rom[0x105] = 0xE7;
    rom[0x106] = 0xC0;
    rom[0x107] = 0x00;

    // 0x134..0x13D: ASCII Title
    const char* title = isCgb ? "GBC_TEST" : "DMG_TEST";
    std::memcpy(&rom[0x134], title, 8);

    // 0x143: CGB flag (0xC0 = CGB-only, 0x00 = DMG)
    rom[0x143] = isCgb ? 0xC0 : 0x00;
    // 0x147: Cartridge type = MBC1 + RAM + BATTERY
    rom[0x147] = 0x03;
    // 0x148: ROM size = 32 KiB
    rom[0x148] = 0x00;
    // 0x149: RAM size = 8 KiB (1 bank)
    rom[0x149] = 0x02;

    // 0x14D: GB header checksum
    uint8_t x = 0;
    for (size_t i = 0x134; i <= 0x14C; ++i) {
        x = static_cast<uint8_t>(x - rom[i] - 1u);
    }
    rom[0x14D] = x;

    // SM83 machine code at 0x0150:
    size_t pc = 0x150;
    auto b = [&](uint8_t v) { rom[pc++] = v; };

    b(0xF3);                    // DI
    // Turn LCD off safely (LD A, $00; LDH ($40), A)
    b(0x3E); b(0x00);
    b(0xE0); b(0x40);

    // Enable MBC1 external SRAM: LD A, $0A; LD ($0000), A
    b(0x3E); b(0x0A);
    b(0xEA); b(0x00); b(0x00);

    // Write SRAM signature bytes: SRAM[0] = 0x42, SRAM[1] = 0x99
    b(0x3E); b(0x42);
    b(0xEA); b(0x00); b(0xA0);
    b(0x3E); b(0x99);
    b(0xEA); b(0x01); b(0xA0);

    // Enable PSG audio channel 1 (NR52=$80, NR51=$FF, NR50=$77, NR11=$80, NR12=$F0, NR13=$80, NR14=$87)
    b(0x3E); b(0x80); b(0xE0); b(0x26);
    b(0x3E); b(0xFF); b(0xE0); b(0x25);
    b(0x3E); b(0x77); b(0xE0); b(0x24);
    b(0x3E); b(0x80); b(0xE0); b(0x11);
    b(0x3E); b(0xF0); b(0xE0); b(0x12);
    b(0x3E); b(0x80); b(0xE0); b(0x13);
    b(0x3E); b(0x87); b(0xE0); b(0x14);

    if (isCgb) {
        // Set CGB BG Palette 0 Color 0 = pure RED (0x001F -> low=0x1F, high=0x00)
        b(0x3E); b(0x80); b(0xE0); b(0x68);  // BCPS = 0x80 (auto-increment)
        b(0x3E); b(0x1F); b(0xE0); b(0x69);  // BCPD low byte
        b(0x3E); b(0x00); b(0xE0); b(0x69);  // BCPD high byte
    } else {
        // DMG: set BGP = 0xE4 and write stripe pattern into tile 0 ($8000..$800F)
        b(0x3E); b(0xE4); b(0xE0); b(0x47);
        b(0x21); b(0x00); b(0x80);           // LD HL, $8000
        b(0x06); b(0x10);                    // LD B, 16
        b(0x3E); b(0xAA);                    // LD A, $AA (alternating pixels)
        // loop: LD (HL+), A; DEC B; JR NZ, -4
        b(0x22);
        b(0x05);
        b(0x20); b(0xFC);
    }

    // Turn LCD ON: LD A, $91; LDH ($40), A
    b(0x3E); b(0x91);
    b(0xE0); b(0x40);

    // Infinite loop: JR -2 (0x18, 0xFE)
    b(0x18); b(0xFE);

    return rom;
}

// ---------- Unit tests ----------

void testEmulatorStateTransitions() {
    using gba::EmulatorState;
    using gba::isValidStateTransition;

    CHECK(isValidStateTransition(EmulatorState::Unloaded, EmulatorState::Loading), "Unloaded -> Loading");
    CHECK(isValidStateTransition(EmulatorState::Loading, EmulatorState::Running), "Loading -> Running");
    CHECK(isValidStateTransition(EmulatorState::Loading, EmulatorState::Paused), "Loading -> Paused");
    CHECK(isValidStateTransition(EmulatorState::Running, EmulatorState::Paused), "Running -> Paused");
    CHECK(isValidStateTransition(EmulatorState::Paused, EmulatorState::Running), "Paused -> Running");
    CHECK(isValidStateTransition(EmulatorState::Running, EmulatorState::Stopping), "Running -> Stopping");
    CHECK(isValidStateTransition(EmulatorState::Stopping, EmulatorState::Stopped), "Stopping -> Stopped");
    CHECK(isValidStateTransition(EmulatorState::Stopped, EmulatorState::Unloaded), "Stopped -> Unloaded");

    // Reject illegal jumps
    CHECK(!isValidStateTransition(EmulatorState::Unloaded, EmulatorState::Running), "Reject Unloaded -> Running");
    CHECK(!isValidStateTransition(EmulatorState::Unloaded, EmulatorState::Paused), "Reject Unloaded -> Paused");
    CHECK(!isValidStateTransition(EmulatorState::Stopping, EmulatorState::Running), "Reject Stopping -> Running");
    CHECK(!isValidStateTransition(EmulatorState::Stopped, EmulatorState::Running), "Reject Stopped -> Running");
}

void testInputStateAndConflictSanitization() {
    gba::InputState input;
    input.setTouchMask(gba::JOYPAD_UP | gba::JOYPAD_A);
    input.setGamepadMask(gba::JOYPAD_RIGHT | gba::JOYPAD_L);

    const uint16_t merged = input.currentMask();
    CHECK((merged & gba::JOYPAD_UP) != 0, "UP present");
    CHECK((merged & gba::JOYPAD_RIGHT) != 0, "RIGHT present");
    CHECK((merged & gba::JOYPAD_A) != 0, "A present");
    CHECK((merged & gba::JOYPAD_L) != 0, "L present");

    // Opposing directions must cancel by default
    input.setTouchMask(gba::JOYPAD_LEFT | gba::JOYPAD_RIGHT | gba::JOYPAD_B);
    input.setGamepadMask(0);
    const uint16_t sanitized = input.currentMask();
    CHECK((sanitized & (gba::JOYPAD_LEFT | gba::JOYPAD_RIGHT)) == 0, "Opposing Left+Right cancelled");
    CHECK((sanitized & gba::JOYPAD_B) != 0, "Non-opposing B preserved");

    // When explicitly allowed by core option
    input.setAllowOpposingDirections(true);
    CHECK((input.currentMask() & (gba::JOYPAD_LEFT | gba::JOYPAD_RIGHT)) ==
          (gba::JOYPAD_LEFT | gba::JOYPAD_RIGHT),
          "Opposing directions preserved when allowed");

    // releaseAll clears both touch and gamepad sources
    input.setGamepadMask(gba::JOYPAD_START);
    input.releaseAll();
    CHECK(input.currentMask() == 0, "releaseAll clears all inputs");
}

void testAudioRingBufferAndResampler() {
    gba::AudioRingBuffer rb(512);
    CHECK(rb.capacityFrames() == 512, "Capacity 512");

    std::vector<int16_t> in(300 * 2);
    for (size_t i = 0; i < in.size(); ++i) {
        in[i] = static_cast<int16_t>((i % 200) * 100);
    }
    CHECK(rb.push(in.data(), 300) == 300, "Push 300 frames");
    CHECK(rb.availableFrames() == 300, "300 available");

    // Wrap-around test: push another 300 -> 212 written, 88 overrun
    CHECK(rb.push(in.data(), 300) == 212, "Push wraps up to capacity");
    CHECK(rb.overrunFrames() == 88, "Overrun counted");

    std::vector<int16_t> out(400 * 2, -1);
    CHECK(rb.pop(out.data(), 400) == 400, "Pop 400 frames");
    CHECK(out[0] == in[0] && out[1] == in[1], "Samples match");

    // Underrun fills trailing silence cleanly
    std::vector<int16_t> out2(300 * 2, 1234);
    CHECK(rb.pop(out2.data(), 300) == 112, "Pop remaining 112 frames");
    CHECK(rb.underrunFrames() == 188, "Underrun counted");
    CHECK(out2[299 * 2] == 0 && out2[299 * 2 + 1] == 0, "Underrun filled with silence");

    // Mute test
    rb.setMuted(true);
    rb.push(in.data(), 64);
    std::vector<int16_t> mutedOut(64 * 2, 777);
    rb.pop(mutedOut.data(), 64);
    CHECK(mutedOut[10] == 0 && mutedOut[11] == 0, "Muted output is zero");

    // Resampler test: 65536 Hz -> 48000 Hz
    gba::LinearStereoResampler resampler;
    resampler.configure(65536.0, 48000.0);
    std::vector<int16_t> src(1024 * 2, 10000);
    std::vector<int16_t> dst;
    resampler.process(src.data(), 1024, dst);
    const size_t dstFrames = dst.size() / 2;
    CHECK(dstFrames >= 740 && dstFrames <= 760, "Resampled frame count matches 48000/65536 ratio");
    CHECK(dst[20] == 10000, "DC level preserved through linear resampler");
}

void testCheatValidatorAndFuzzing() {
    // Valid GBA codes
    auto gba1 = gba::CheatValidator::validate(gba::RomSystem::Gba, "82025840 0044");
    CHECK(gba1.valid && gba1.normalizedCode == "82025840 0044", "Valid GBA CodeBreaker");
    auto gba2 = gba::CheatValidator::validate(gba::RomSystem::Gba, "deadbeefcafebabe");
    CHECK(gba2.valid && gba2.normalizedCode == "DEADBEEF CAFEBABE", "Valid GBA 16-hex normalized");
    auto gbaMulti = gba::CheatValidator::validate(gba::RomSystem::Gba, "01234567 89ABCDEF\n11223344 5566");
    CHECK(gbaMulti.valid && gbaMulti.normalizedCode == "01234567 89ABCDEF+11223344 5566", "Multi-line GBA");

    // Valid GB/GBC codes
    auto gb1 = gba::CheatValidator::validate(gba::RomSystem::Gbc, "01f-23a-45b");
    CHECK(gb1.valid && gb1.normalizedCode == "01F-23A-45B", "Valid GB Game Genie 9-char");
    auto gb2 = gba::CheatValidator::validate(gba::RomSystem::Gb, "010F9CD0");
    CHECK(gb2.valid && gb2.normalizedCode == "010F9CD0", "Valid GB GameShark 8-char");

    // Fuzz / overflow payloads that would overflow mGBA's fixed realCode[] buffer!
    CHECK(!gba::CheatValidator::validate(gba::RomSystem::Gba, std::string(100, 'A')).valid,
          "Reject oversized unbroken GBA token");
    CHECK(!gba::CheatValidator::validate(gba::RomSystem::Gb, std::string(64, 'F')).valid,
          "Reject oversized unbroken GB token");
    CHECK(!gba::CheatValidator::validate(gba::RomSystem::Gba, "ZZZZZZZZ ZZZZZZZZ").valid,
          "Reject non-hex GBA code");
    CHECK(!gba::CheatValidator::validate(gba::RomSystem::Gba, "").valid,
          "Reject empty cheat");
    CHECK(!gba::CheatValidator::validate(gba::RomSystem::Unknown, "01234567 89ABCDEF").valid,
          "Reject unknown system");
}

void testRomValidatorNegativeCases() {
    const auto tmpDir = std::filesystem::temp_directory_path() / "gba_emulator_native_tests";
    std::filesystem::create_directories(tmpDir);

    // Empty file
    const auto emptyPath = tmpDir / "empty.gba";
    writeFile(emptyPath, {});
    CHECK(!gba::RomValidator::validateFile(emptyPath.string()).valid, "Reject empty GBA file");

    // Truncated GBA file
    const auto truncPath = tmpDir / "trunc.gba";
    writeFile(truncPath, std::vector<uint8_t>(64, 0xEA));
    CHECK(!gba::RomValidator::validateFile(truncPath.string()).valid, "Reject truncated GBA file");

    // Corrupt GBA header
    const auto corruptPath = tmpDir / "corrupt.gba";
    writeFile(corruptPath, std::vector<uint8_t>(512, 0x11));
    CHECK(!gba::RomValidator::validateFile(corruptPath.string()).valid, "Reject corrupt GBA header");

    // Unsupported archive extension
    const auto zipPath = tmpDir / "game.zip";
    writeFile(zipPath, std::vector<uint8_t>(512, 0x50));
    CHECK(!gba::RomValidator::validateFile(zipPath.string()).valid, "Reject .zip extension in v1");

    // Missing file
    CHECK(!gba::RomValidator::validateFile((tmpDir / "does_not_exist.gba").string()).valid,
          "Reject missing file");
}

// ---------- Real mGBA Libretro Core Integration Tests (GBA, GBC, GB) ----------

void testRealMgbaGbaBootInputVideoAudioSramAndState() {
    const auto tmpDir = std::filesystem::temp_directory_path() / "gba_emulator_native_tests";
    std::filesystem::create_directories(tmpDir);
    const auto gbaRomPath = tmpDir / "synthetic_test.gba";
    writeFile(gbaRomPath, buildSyntheticGbaTestRom());

    gba::LibretroFrontend frontend;
    gba::LoadConfig cfg;
    cfg.coreLibraryPath = GBA_LIBRETRO_CORE_PATH;
    cfg.romFilePath = gbaRomPath.string();
    cfg.outputSampleRate = 48000.0;
    cfg.startPaused = true;  // Deterministic stepFrames testing!

    std::string err;
    CHECK(frontend.loadRom(cfg, err), ("GBA loadRom failed: " + err).c_str());
    CHECK(frontend.state() == gba::EmulatorState::Paused, "State is Paused after startPaused load");

    const auto info = frontend.coreInfo();
    CHECK(info.system == gba::RomSystem::Gba, "Detected system is GBA");
    CHECK(info.baseWidth == 240 && info.baseHeight == 160, "GBA native resolution 240x160");
    CHECK(std::abs(info.aspectRatio - 1.5f) < 0.02f, "GBA aspect ratio 3:2");

    // Dynamic core options must be populated directly by pinned libretro/mgba
    const auto opts = frontend.coreOptions();
    CHECK(!opts.empty(), "Pinned core exposed dynamic core options");
    bool foundFrameskip = false;
    bool foundColorCorrection = false;
    for (const auto& opt : opts) {
        if (opt.key == "mgba_frameskip") foundFrameskip = true;
        if (opt.key == "mgba_color_correction") foundColorCorrection = true;
    }
    CHECK(foundFrameskip, "Pinned core exposes mgba_frameskip");
    CHECK(foundColorCorrection, "Pinned core exposes mgba_color_correction");
    CHECK(!frontend.setCoreOption("nonexistent_fake_option", "on"), "Rejects unknown core option");
    CHECK(!frontend.setCoreOption("mgba_frameskip", "INVALID_CHOICE"), "Rejects unsupported option value");

    // Run 6 frames with no buttons pressed -> Mode 3 VRAM should be pure RED
    CHECK(frontend.stepFrames(6), "Step 6 frames (no input)");
    CHECK(frontend.totalFramesExecuted() == 6, "Executed 6 frames");
    CHECK(frontend.totalAudioFramesProduced() > 0, "Audio samples produced by GBA PSG");

    std::vector<uint16_t> pixels;
    gba::VideoFrameBuffer::FrameMetadata meta;
    CHECK(frontend.videoBuffer().consumeLatest(0, pixels, meta), "Video frame captured");
    CHECK(meta.width == 240 && meta.height == 160, "Frame dimensions 240x160");
    const uint16_t redPixel = pixels[10];
    const uint16_t redR = (redPixel >> 11) & 0x1Fu;
    const uint16_t redG = (redPixel >> 5) & 0x3Fu;
    const uint16_t redB = redPixel & 0x1Fu;
    CHECK(redR >= 28 && redG <= 4 && redB <= 4, "No-button frame renders RED pixel");

    // Press A button -> GBA ROM reads KEYINPUT and switches VRAM to GREEN!
    frontend.setTouchInputMask(gba::JOYPAD_A);
    CHECK(frontend.stepFrames(3), "Step 3 frames with A held");
    CHECK(frontend.videoBuffer().consumeLatest(meta.frameIndex, pixels, meta), "Frame after A press");
    const uint16_t greenPixel = pixels[10];
    const uint16_t grnR = (greenPixel >> 11) & 0x1Fu;
    const uint16_t grnG = (greenPixel >> 5) & 0x3Fu;
    const uint16_t grnB = greenPixel & 0x1Fu;
    CHECK(grnR <= 4 && grnG >= 56 && grnB <= 4, "A button input changes GBA screen to GREEN");

    // Press L button -> GBA ROM switches VRAM to BLUE!
    frontend.setTouchInputMask(gba::JOYPAD_L);
    CHECK(frontend.stepFrames(3), "Step 3 frames with L held");
    CHECK(frontend.videoBuffer().consumeLatest(meta.frameIndex, pixels, meta), "Frame after L press");
    const uint16_t bluePixel = pixels[10];
    const uint16_t bluR = (bluePixel >> 11) & 0x1Fu;
    const uint16_t bluG = (bluePixel >> 5) & 0x3Fu;
    const uint16_t bluB = bluePixel & 0x1Fu;
    CHECK(bluR <= 4 && bluG <= 4 && bluB >= 28, "L button input changes GBA screen to BLUE");

    // releaseAllInputs returns screen to RED
    frontend.releaseAllInputs();
    CHECK(frontend.stepFrames(3), "Step 3 frames after releaseAllInputs");
    CHECK(frontend.videoBuffer().consumeLatest(meta.frameIndex, pixels, meta), "Frame after release");
    CHECK(((pixels[10] >> 11) & 0x1Fu) >= 28, "Release returns GBA screen to RED");

    // Save-state serialize & unserialize round-trip + negative check
    std::vector<uint8_t> stateBytes;
    CHECK(frontend.serializeState(stateBytes), "GBA serializeState succeeded");
    CHECK(stateBytes.size() > 1024, "Serialized state is non-trivial");
    CHECK(frontend.unserializeState(stateBytes), "GBA unserializeState round-trip succeeded");
    CHECK(!frontend.unserializeState(std::vector<uint8_t>{1, 2, 3, 4}),
          "Reject truncated/corrupt save state");

    // Cheat validation + application on live core
    std::string cheatErr;
    CHECK(frontend.applyCheats({"82000000 0001"}, cheatErr), ("Valid cheat applied: " + cheatErr).c_str());
    CHECK(!frontend.applyCheats({std::string(80, 'A')}, cheatErr), "Oversized fuzz cheat rejected");
    CHECK(frontend.applyCheats({}, cheatErr), "Cheat reset with empty list succeeded");

    // Pause/Resume live worker thread test
    CHECK(frontend.resume(), "Resume live worker thread");
    CHECK(frontend.state() == gba::EmulatorState::Running, "State is Running");
    std::this_thread::sleep_for(std::chrono::milliseconds(60));
    CHECK(frontend.pause(), "Pause live worker thread");
    CHECK(frontend.state() == gba::EmulatorState::Paused, "State is Paused");
    CHECK(frontend.totalFramesExecuted() > 15, "Worker thread executed live frames while Running");

    // Verify SRAM written by GBA ROM survives shutdown -> reload!
    std::vector<uint8_t> savedSram;
    CHECK(frontend.shutdown(&savedSram, 4000), "Bounded shutdown succeeded");
    CHECK(frontend.state() == gba::EmulatorState::Unloaded, "State returned to Unloaded");
    CHECK(savedSram.size() >= 32u * 1024u, "Captured GBA SRAM (>= 32 KiB)");
    CHECK(savedSram[0] == 0x5A && savedSram[1] == 0xA5 &&
          savedSram[2] == 0x3C && savedSram[3] == 0xC3,
          "GBA ROM SRAM signature persisted in extracted SRAM");

    // Mutate marker byte 16 in savedSram, reload ROM with initialSram, and confirm reload restores it!
    savedSram[16] = 0x77;
    cfg.initialSram = savedSram;
    CHECK(frontend.loadRom(cfg, err), ("GBA reload with initialSram failed: " + err).c_str());
    CHECK(frontend.stepFrames(2), "Step reloaded GBA ROM");
    std::vector<uint8_t> reloadedSram;
    CHECK(frontend.extractSram(reloadedSram), "Extract SRAM after reload");
    CHECK(reloadedSram.size() > 16 && reloadedSram[16] == 0x77,
          "Persisted SRAM byte survived core unload & reload");
    CHECK(frontend.shutdown(nullptr, 4000), "Final GBA shutdown");
}

void testRealMgbaGbcAndGbBootVideoAudioAndSram() {
    const auto tmpDir = std::filesystem::temp_directory_path() / "gba_emulator_native_tests";
    std::filesystem::create_directories(tmpDir);

    // 1. Game Boy Color (.gbc)
    {
        const auto gbcPath = tmpDir / "synthetic_test.gbc";
        writeFile(gbcPath, buildSyntheticGbTestRom(true));

        gba::LibretroFrontend frontend;
        gba::LoadConfig cfg;
        cfg.coreLibraryPath = GBA_LIBRETRO_CORE_PATH;
        cfg.romFilePath = gbcPath.string();
        cfg.outputSampleRate = 48000.0;
        cfg.startPaused = true;

        std::string err;
        CHECK(frontend.loadRom(cfg, err), ("GBC loadRom failed: " + err).c_str());
        const auto info = frontend.coreInfo();
        CHECK(info.system == gba::RomSystem::Gbc, "Detected system is GBC");
        CHECK(info.baseWidth == 160 && info.baseHeight == 144, "GBC native resolution 160x144");
        CHECK(std::abs(info.aspectRatio - (160.0f / 144.0f)) < 0.02f, "GBC aspect ratio 10:9");

        CHECK(frontend.stepFrames(8), "GBC step 8 frames");
        CHECK(frontend.totalAudioFramesProduced() > 0, "GBC audio produced");

        std::vector<uint16_t> pixels;
        gba::VideoFrameBuffer::FrameMetadata meta;
        CHECK(frontend.videoBuffer().consumeLatest(0, pixels, meta), "GBC video frame captured");
        CHECK(meta.width == 160 && meta.height == 144, "GBC frame 160x144");
        const uint16_t p = pixels[160 * 10 + 10];
        CHECK(((p >> 11) & 0x1Fu) >= 28 && ((p >> 5) & 0x3Fu) <= 4,
              "GBC rendered RED palette color");

        std::vector<uint8_t> sram;
        CHECK(frontend.shutdown(&sram, 4000), "GBC shutdown");
        CHECK(sram.size() >= 8192 && sram[0] == 0x42 && sram[1] == 0x99,
              "GBC MBC1 battery SRAM signature persisted");
    }

    // 2. Original Game Boy (.gb)
    {
        const auto gbPath = tmpDir / "synthetic_test.gb";
        writeFile(gbPath, buildSyntheticGbTestRom(false));

        gba::LibretroFrontend frontend;
        gba::LoadConfig cfg;
        cfg.coreLibraryPath = GBA_LIBRETRO_CORE_PATH;
        cfg.romFilePath = gbPath.string();
        cfg.outputSampleRate = 48000.0;
        cfg.startPaused = true;

        std::string err;
        CHECK(frontend.loadRom(cfg, err), ("GB loadRom failed: " + err).c_str());
        const auto info = frontend.coreInfo();
        CHECK(info.system == gba::RomSystem::Gb, "Detected system is GB");
        CHECK(info.baseWidth == 160 && info.baseHeight == 144, "GB native resolution 160x144");

        CHECK(frontend.stepFrames(8), "GB step 8 frames");
        CHECK(frontend.totalAudioFramesProduced() > 0, "GB audio produced");

        std::vector<uint16_t> pixels;
        gba::VideoFrameBuffer::FrameMetadata meta;
        CHECK(frontend.videoBuffer().consumeLatest(0, pixels, meta), "GB video frame captured");
        CHECK(meta.width == 160 && meta.height == 144, "GB frame 160x144");
        // Verify striped tile pattern produced at least two distinct pixel shades
        bool sawDistinct = false;
        for (size_t i = 1; i < pixels.size(); ++i) {
            if (pixels[i] != pixels[0]) {
                sawDistinct = true;
                break;
            }
        }
        CHECK(sawDistinct, "DMG rendered striped tile pattern");

        std::vector<uint8_t> sram;
        CHECK(frontend.shutdown(&sram, 4000), "GB shutdown");
        CHECK(sram.size() >= 8192 && sram[0] == 0x42 && sram[1] == 0x99,
              "GB MBC1 battery SRAM signature persisted");
    }
}

void testSingleOwnerGuardPreventsConcurrentCores() {
    const auto tmpDir = std::filesystem::temp_directory_path() / "gba_emulator_native_tests";
    std::filesystem::create_directories(tmpDir);
    const auto gbaRomPath = tmpDir / "single_owner.gba";
    writeFile(gbaRomPath, buildSyntheticGbaTestRom());

    gba::LibretroFrontend first;
    gba::LibretroFrontend second;
    gba::LoadConfig cfg;
    cfg.coreLibraryPath = GBA_LIBRETRO_CORE_PATH;
    cfg.romFilePath = gbaRomPath.string();
    cfg.startPaused = true;

    std::string err1;
    std::string err2;
    CHECK(first.loadRom(cfg, err1), ("First core load succeeded: " + err1).c_str());
    CHECK(!second.loadRom(cfg, err2), "Second concurrent core instance rejected");
    CHECK(first.shutdown(nullptr, 4000), "First core shutdown");
    CHECK(second.loadRom(cfg, err2), ("Second core loads cleanly after first shutdown: " + err2).c_str());
    CHECK(second.shutdown(nullptr, 4000), "Second core shutdown");
}

void testCoreOptionsJsonOverridesAndScreenshotArgb() {
    const auto tmpDir = std::filesystem::temp_directory_path() / "gba_emulator_native_tests";
    std::filesystem::create_directories(tmpDir);
    const auto gbaRomPath = tmpDir / "options_screenshot.gba";
    writeFile(gbaRomPath, buildSyntheticGbaTestRom());

    gba::LibretroFrontend frontend;
    gba::LoadConfig cfg;
    cfg.coreLibraryPath = GBA_LIBRETRO_CORE_PATH;
    cfg.romFilePath = gbaRomPath.string();
    cfg.startPaused = true;
    cfg.initialCoreOptions = {
        {"mgba_color_correction", "GBA"},
        {"mgba_nonexistent_fake_option", "bogus"},
    };

    std::string err;
    CHECK(frontend.loadRom(cfg, err), ("loadRom with initial options failed: " + err).c_str());

    const std::string json = frontend.coreOptionsJson();
    CHECK(json.size() > 64 && json.front() == '[' && json.back() == ']',
          "coreOptionsJson returns non-empty JSON array");
    CHECK(json.find("\"key\":\"mgba_color_correction\"") != std::string::npos,
          "JSON contains real mgba_color_correction key");
    CHECK(json.find("\"currentValue\":\"GBA\"") != std::string::npos,
          "Boot-time initialCoreOptions override applied to mgba_color_correction");
    CHECK(json.find("mgba_nonexistent_fake_option") == std::string::npos,
          "Unknown fake option key is never surfaced in JSON");
    CHECK(json.find("\"visible\":") != std::string::npos,
          "JSON includes visibility flag from CoreOptionsStore");

    CHECK(frontend.stepFrames(4), "Step 4 frames to populate video buffer");
    uint32_t w = 0;
    uint32_t h = 0;
    std::vector<uint32_t> argbGbaCorrected;
    CHECK(frontend.captureLatestFrameArgb(w, h, argbGbaCorrected), "captureLatestFrameArgb succeeded");
    CHECK(w == 240 && h == 160 && argbGbaCorrected.size() == 240u * 160u, "Captured 240x160 ARGB8888 thumbnail");
    const uint32_t correctedPixel = argbGbaCorrected[10];
    const uint32_t alpha = (correctedPixel >> 24) & 0xFFu;
    const uint32_t red = (correctedPixel >> 16) & 0xFFu;
    const uint32_t green = (correctedPixel >> 8) & 0xFFu;
    CHECK(alpha == 0xFFu && red >= 120u && red > green + 60u, "Captured ARGB8888 pixel is opaque reddish");

    // Toggle color correction OFF live and confirm mGBA's rendered pixel brightens to uncorrected red.
    CHECK(frontend.setCoreOption("mgba_color_correction", "OFF"), "Live core option toggle OFF succeeded");
    CHECK(frontend.stepFrames(3), "Step frames after core option change");
    std::vector<uint32_t> argbRaw;
    CHECK(frontend.captureLatestFrameArgb(w, h, argbRaw), "Recapture ARGB8888 after option change");
    const uint32_t rawRed = (argbRaw[10] >> 16) & 0xFFu;
    CHECK(rawRed >= 240u && rawRed > red, "Live mgba_color_correction toggle changed rendered output");

    CHECK(frontend.shutdown(nullptr, 4000), "Shutdown after options & screenshot test");
}

}  // namespace

int main() {
    std::cout << "=== GBA Emulator Native C++ & mGBA Integration Test Suite ===\n";
    std::cout << "Core path: " << GBA_LIBRETRO_CORE_PATH << "\n";

    RUN_TEST(testEmulatorStateTransitions);
    RUN_TEST(testInputStateAndConflictSanitization);
    RUN_TEST(testAudioRingBufferAndResampler);
    RUN_TEST(testCheatValidatorAndFuzzing);
    RUN_TEST(testRomValidatorNegativeCases);
    RUN_TEST(testRealMgbaGbaBootInputVideoAudioSramAndState);
    RUN_TEST(testRealMgbaGbcAndGbBootVideoAudioAndSram);
    RUN_TEST(testSingleOwnerGuardPreventsConcurrentCores);
    RUN_TEST(testCoreOptionsJsonOverridesAndScreenshotArgb);

    std::cout << "\nSummary: " << (gTestsRun - gTestsFailed) << "/" << gTestsRun << " passed, "
              << gTestsFailed << " failed.\n";
    return gTestsFailed == 0 ? 0 : 1;
}
