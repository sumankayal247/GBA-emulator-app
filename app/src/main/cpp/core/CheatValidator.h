#pragma once

#include <cctype>
#include <cstddef>
#include <optional>
#include <string>
#include <vector>

namespace gba {

enum class RomSystem : int32_t {
    Unknown = 0,
    Gba = 1,
    Gbc = 2,
    Gb = 3,
};

// Strictly validates and normalizes untrusted user cheat strings before forwarding to
// mGBA's retro_cheat_set().
//
// Security rationale (ADR-0001): pinned third_party/mgba/src/platform/libretro/libretro.c
// copies unbroken cheat tokens into fixed-size stack buffers ("XXXXXXXX XXXXXXXX" = 17 chars
// for GBA; "XXX-XXX-XXX" = 11 chars for GB/GBC) without checking bounds before writing.
// Validating every line strictly here eliminates any possibility of stack buffer overflow.
class CheatValidator {
public:
    static constexpr size_t kMaxTotalLength = 512;
    static constexpr size_t kMaxLinesPerCheat = 16;

    struct ValidationResult {
        bool valid{false};
        std::string normalizedCode;
        std::string errorMessage;
    };

    static ValidationResult validate(RomSystem system, const std::string& rawInput) {
        if (system == RomSystem::Unknown) {
            return {false, "", "Cannot validate cheat without an identified system."};
        }
        if (rawInput.empty()) {
            return {false, "", "Cheat code cannot be empty."};
        }
        if (rawInput.size() > kMaxTotalLength) {
            return {false, "", "Cheat code exceeds maximum allowed length."};
        }

        // Split on newlines, '+' or ';' into individual tokens/lines.
        std::vector<std::string> lines;
        std::string current;
        for (char ch : rawInput) {
            if (ch == '\r' || ch == '\n' || ch == '+' || ch == ';') {
                trimAndPush(current, lines);
            } else {
                current.push_back(ch);
            }
        }
        trimAndPush(current, lines);

        if (lines.empty()) {
            return {false, "", "Cheat code contains no valid entries."};
        }
        if (lines.size() > kMaxLinesPerCheat) {
            return {false, "", "Cheat code has too many lines."};
        }

        std::string normalized;
        for (size_t i = 0; i < lines.size(); ++i) {
            std::optional<std::string> lineOk;
            if (system == RomSystem::Gba) {
                lineOk = normalizeGbaLine(lines[i]);
            } else {
                lineOk = normalizeGbLine(lines[i]);
            }
            if (!lineOk.has_value()) {
                if (system == RomSystem::Gba) {
                    return {
                        false,
                        "",
                        "Invalid GBA cheat format. Expected XXXXXXXX XXXXXXXX (GameShark/AR) or XXXXXXXX YYYY (CodeBreaker)."
                    };
                }
                return {
                    false,
                    "",
                    "Invalid GB/GBC cheat format. Expected XXX-XXX-XXX, XXX-XXX (Game Genie), or XXXXXXXX (GameShark)."
                };
            }
            if (i > 0) {
                normalized.push_back('+');
            }
            normalized += *lineOk;
        }

        return {true, normalized, ""};
    }

private:
    static void trimAndPush(std::string& s, std::vector<std::string>& out) {
        size_t start = 0;
        while (start < s.size() && std::isspace(static_cast<unsigned char>(s[start]))) {
            ++start;
        }
        size_t end = s.size();
        while (end > start && std::isspace(static_cast<unsigned char>(s[end - 1]))) {
            --end;
        }
        if (end > start) {
            out.push_back(s.substr(start, end - start));
        }
        s.clear();
    }

    static bool isHexStr(const std::string& s) {
        if (s.empty()) {
            return false;
        }
        for (char ch : s) {
            if (!std::isxdigit(static_cast<unsigned char>(ch))) {
                return false;
            }
        }
        return true;
    }

    static std::string toUpper(const std::string& s) {
        std::string out = s;
        for (char& ch : out) {
            ch = static_cast<char>(std::toupper(static_cast<unsigned char>(ch)));
        }
        return out;
    }

    // Supported mGBA GBA formats:
    // - GameShark / Action Replay: XXXXXXXX XXXXXXXX (17 chars) or 16 contiguous hex digits
    // - CodeBreaker:               XXXXXXXX YYYY (13 chars) or 12 contiguous hex digits
    static std::optional<std::string> normalizeGbaLine(const std::string& line) {
        std::vector<std::string> parts;
        std::string tok;
        for (char ch : line) {
            if (std::isspace(static_cast<unsigned char>(ch))) {
                if (!tok.empty()) {
                    parts.push_back(tok);
                    tok.clear();
                }
            } else {
                tok.push_back(ch);
            }
        }
        if (!tok.empty()) {
            parts.push_back(tok);
        }

        if (parts.size() == 1) {
            const std::string& hex = parts[0];
            if (!isHexStr(hex)) {
                return std::nullopt;
            }
            const std::string up = toUpper(hex);
            if (up.size() == 16) {
                return up.substr(0, 8) + " " + up.substr(8, 8);
            }
            if (up.size() == 12) {
                return up.substr(0, 8) + " " + up.substr(8, 4);
            }
            return std::nullopt;
        }
        if (parts.size() == 2) {
            if (!isHexStr(parts[0]) || !isHexStr(parts[1])) {
                return std::nullopt;
            }
            if (parts[0].size() == 8 && (parts[1].size() == 8 || parts[1].size() == 4)) {
                return toUpper(parts[0]) + " " + toUpper(parts[1]);
            }
        }
        return std::nullopt;
    }

    // Supported mGBA GB/GBC formats (max token length 11 chars):
    // - Game Genie 9-digit: XXX-XXX-XXX (11 chars)
    // - Game Genie 6-digit: XXX-XXX (7 chars)
    // - GameShark 8-digit:  XXXXXXXX (8 hex chars)
    static std::optional<std::string> normalizeGbLine(const std::string& line) {
        const std::string up = toUpper(line);
        if (up.size() == 8 && isHexStr(up)) {
            return up;
        }
        if (up.size() == 7 && up[3] == '-') {
            const std::string a = up.substr(0, 3);
            const std::string b = up.substr(4, 3);
            if (isHexStr(a) && isHexStr(b)) {
                return up;
            }
        }
        if (up.size() == 11 && up[3] == '-' && up[7] == '-') {
            const std::string a = up.substr(0, 3);
            const std::string b = up.substr(4, 3);
            const std::string c = up.substr(8, 3);
            if (isHexStr(a) && isHexStr(b) && isHexStr(c)) {
                return up;
            }
        }
        return std::nullopt;
    }
};

}  // namespace gba
