#pragma once

#include <algorithm>
#include <cctype>
#include <cstddef>
#include <cstdint>
#include <fstream>
#include <string>
#include <vector>

#include "CheatValidator.h"

namespace gba {

// Validates untrusted ROM files before passing them to the native mGBA core (ADR-0004).
class RomValidator {
public:
    static constexpr size_t kMinGbaSize = 0xC0;              // 192-byte GBA header
    static constexpr size_t kMaxGbaSize = 32u * 1024u * 1024u; // 32 MiB
    static constexpr size_t kMinGbSize  = 0x150;             // 336-byte GB/GBC header
    static constexpr size_t kMaxGbSize  = 8u * 1024u * 1024u;  // 8 MiB

    struct ValidationResult {
        bool valid{false};
        RomSystem detectedSystem{RomSystem::Unknown};
        std::string errorMessage;
    };

    static RomSystem systemFromExtension(const std::string& pathOrName) {
        const size_t dot = pathOrName.rfind('.');
        if (dot == std::string::npos || dot + 1 >= pathOrName.size()) {
            return RomSystem::Unknown;
        }
        std::string ext = pathOrName.substr(dot + 1);
        for (char& ch : ext) {
            ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
        }
        if (ext == "gba") return RomSystem::Gba;
        if (ext == "gbc") return RomSystem::Gbc;
        if (ext == "gb")  return RomSystem::Gb;
        return RomSystem::Unknown;
    }

    static ValidationResult validateHeaderBytes(
        const uint8_t* header,
        size_t headerLen,
        size_t totalFileSize,
        const std::string& pathOrName
    ) {
        if (!header || headerLen == 0 || totalFileSize == 0) {
            return {false, RomSystem::Unknown, "ROM file is empty."};
        }

        const RomSystem extSystem = systemFromExtension(pathOrName);
        if (extSystem == RomSystem::Unknown) {
            return {false, RomSystem::Unknown, "Unsupported file extension. Only .gba, .gbc, and .gb files are supported."};
        }

        if (extSystem == RomSystem::Gba) {
            if (totalFileSize < kMinGbaSize || headerLen < kMinGbaSize) {
                return {false, RomSystem::Unknown, "GBA ROM file is truncated (smaller than 192-byte header)."};
            }
            if (totalFileSize > kMaxGbaSize) {
                return {false, RomSystem::Unknown, "GBA ROM exceeds maximum supported size (32 MiB)."};
            }
            // Mirrors mGBA's GBAIsROM check: ARM branch byte 0xEA at offset 3 and fixed value 0x96 at 0xB2.
            if (header[3] != 0xEA || header[0xB2] != 0x96) {
                return {false, RomSystem::Unknown, "File does not contain a valid Game Boy Advance ROM header."};
            }
            return {true, RomSystem::Gba, ""};
        }

        // GB or GBC
        if (totalFileSize < kMinGbSize || headerLen < kMinGbSize) {
            return {false, RomSystem::Unknown, "GB/GBC ROM file is truncated (smaller than 336-byte header)."};
        }
        if (totalFileSize > kMaxGbSize) {
            return {false, RomSystem::Unknown, "GB/GBC ROM exceeds maximum supported size (8 MiB)."};
        }

        const bool knownLogo =
            header[0x104] == 0xCE && header[0x105] == 0xED &&
            header[0x106] == 0x66 && header[0x107] == 0x66;
        const bool sachenLogo =
            header[0x104] == 0x7C && header[0x105] == 0xE7 &&
            header[0x106] == 0xC0 && header[0x107] == 0x00;
        if (!knownLogo && !sachenLogo) {
            return {false, RomSystem::Unknown, "File does not contain a valid Game Boy / Game Boy Color ROM header."};
        }

        // CGB flag at 0x143: 0x80 = CGB-compatible, 0xC0 = CGB-only.
        const uint8_t cgbFlag = header[0x143];
        const bool isCgbHeader = (cgbFlag == 0x80 || cgbFlag == 0xC0);
        const RomSystem detected = (extSystem == RomSystem::Gbc || isCgbHeader)
            ? RomSystem::Gbc
            : RomSystem::Gb;
        return {true, detected, ""};
    }

    static ValidationResult validateFile(const std::string& filePath) {
        if (filePath.empty()) {
            return {false, RomSystem::Unknown, "ROM file path is empty."};
        }
        std::ifstream file(filePath, std::ios::binary | std::ios::ate);
        if (!file.is_open()) {
            return {false, RomSystem::Unknown, "Unable to open ROM file."};
        }
        const std::streamoff endPos = file.tellg();
        if (endPos <= 0) {
            return {false, RomSystem::Unknown, "ROM file is empty."};
        }
        const size_t totalSize = static_cast<size_t>(endPos);
        const size_t readSize = std::min<size_t>(totalSize, 0x200);
        std::vector<uint8_t> header(readSize);
        file.seekg(0, std::ios::beg);
        file.read(reinterpret_cast<char*>(header.data()), static_cast<std::streamsize>(readSize));
        if (!file) {
            return {false, RomSystem::Unknown, "Failed to read ROM header."};
        }
        return validateHeaderBytes(header.data(), header.size(), totalSize, filePath);
    }
};

}  // namespace gba
