#pragma once

#include <atomic>
#include <cstdio>
#include <mutex>
#include <optional>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include "libretro.h"

namespace gba {

struct CoreOptionChoice {
    std::string value;
    std::string label;
};

struct CoreOptionDefinition {
    std::string key;
    std::string desc;
    std::string info;
    std::string categoryKey;
    std::string defaultValue;
    std::string currentValue;
    bool visible{true};
    std::vector<CoreOptionChoice> choices;
};

// Stores dynamic Libretro core options reported at runtime by the pinned core.
// Rejects unknown keys or unsupported values so UI never sets unverified core options.
class CoreOptionsStore {
public:
    void setPendingOverrides(const std::vector<std::pair<std::string, std::string>>& overrides) {
        std::lock_guard<std::mutex> lock(mutex_);
        pendingOverrides_.clear();
        for (const auto& kv : overrides) {
            if (!kv.first.empty() && !kv.second.empty()) {
                pendingOverrides_[kv.first] = kv.second;
            }
        }
    }

    void clearPendingOverrides() {
        std::lock_guard<std::mutex> lock(mutex_);
        pendingOverrides_.clear();
    }

    void registerFromV2(const struct retro_core_options_v2* opts) {
        std::lock_guard<std::mutex> lock(mutex_);
        definitions_.clear();
        keyToIndex_.clear();
        if (!opts || !opts->definitions) {
            return;
        }
        for (size_t i = 0; opts->definitions[i].key != nullptr; ++i) {
            const auto& src = opts->definitions[i];
            CoreOptionDefinition def;
            def.key = src.key;
            def.desc = src.desc ? src.desc : src.key;
            def.info = src.info ? src.info : "";
            def.categoryKey = src.category_key ? src.category_key : "";
            def.defaultValue = src.default_value ? src.default_value : "";
            def.visible = true;

            for (size_t v = 0; v < RETRO_NUM_CORE_OPTION_VALUES_MAX && src.values[v].value != nullptr; ++v) {
                CoreOptionChoice choice;
                choice.value = src.values[v].value;
                choice.label = src.values[v].label ? src.values[v].label : src.values[v].value;
                def.choices.push_back(std::move(choice));
            }

            if (def.defaultValue.empty() && !def.choices.empty()) {
                def.defaultValue = def.choices.front().value;
            }
            def.currentValue = def.defaultValue;

            auto pendingIt = pendingOverrides_.find(def.key);
            if (pendingIt != pendingOverrides_.end() && hasChoice(def, pendingIt->second)) {
                def.currentValue = pendingIt->second;
            }

            keyToIndex_[def.key] = definitions_.size();
            definitions_.push_back(std::move(def));
        }
        dirty_.store(true, std::memory_order_release);
    }

    void registerFromV1(const struct retro_core_option_definition* defs) {
        std::lock_guard<std::mutex> lock(mutex_);
        definitions_.clear();
        keyToIndex_.clear();
        if (!defs) {
            return;
        }
        for (size_t i = 0; defs[i].key != nullptr; ++i) {
            const auto& src = defs[i];
            CoreOptionDefinition def;
            def.key = src.key;
            def.desc = src.desc ? src.desc : src.key;
            def.info = src.info ? src.info : "";
            def.defaultValue = src.default_value ? src.default_value : "";
            def.visible = true;
            for (size_t v = 0; v < RETRO_NUM_CORE_OPTION_VALUES_MAX && src.values[v].value != nullptr; ++v) {
                CoreOptionChoice choice;
                choice.value = src.values[v].value;
                choice.label = src.values[v].label ? src.values[v].label : src.values[v].value;
                def.choices.push_back(std::move(choice));
            }
            if (def.defaultValue.empty() && !def.choices.empty()) {
                def.defaultValue = def.choices.front().value;
            }
            def.currentValue = def.defaultValue;
            auto pendingIt = pendingOverrides_.find(def.key);
            if (pendingIt != pendingOverrides_.end() && hasChoice(def, pendingIt->second)) {
                def.currentValue = pendingIt->second;
            }
            keyToIndex_[def.key] = definitions_.size();
            definitions_.push_back(std::move(def));
        }
        dirty_.store(true, std::memory_order_release);
    }

    void registerFromV0(const struct retro_variable* vars) {
        std::lock_guard<std::mutex> lock(mutex_);
        definitions_.clear();
        keyToIndex_.clear();
        if (!vars) {
            return;
        }
        for (size_t i = 0; vars[i].key != nullptr; ++i) {
            if (!vars[i].value) {
                continue;
            }
            const std::string raw = vars[i].value;
            const size_t semi = raw.find(';');
            CoreOptionDefinition def;
            def.key = vars[i].key;
            def.desc = (semi != std::string::npos) ? raw.substr(0, semi) : def.key;
            def.visible = true;
            std::string valuesStr = (semi != std::string::npos) ? raw.substr(semi + 1) : raw;
            while (!valuesStr.empty() && valuesStr.front() == ' ') {
                valuesStr.erase(valuesStr.begin());
            }
            size_t start = 0;
            while (start <= valuesStr.size()) {
                const size_t pipe = valuesStr.find('|', start);
                const std::string token = (pipe == std::string::npos)
                    ? valuesStr.substr(start)
                    : valuesStr.substr(start, pipe - start);
                if (!token.empty()) {
                    def.choices.push_back({token, token});
                }
                if (pipe == std::string::npos) {
                    break;
                }
                start = pipe + 1;
            }
            if (!def.choices.empty()) {
                def.defaultValue = def.choices.front().value;
                def.currentValue = def.defaultValue;
            }
            auto pendingIt = pendingOverrides_.find(def.key);
            if (pendingIt != pendingOverrides_.end() && hasChoice(def, pendingIt->second)) {
                def.currentValue = pendingIt->second;
            }
            keyToIndex_[def.key] = definitions_.size();
            definitions_.push_back(std::move(def));
        }
        dirty_.store(true, std::memory_order_release);
    }

    void setVisibility(const char* key, bool visible) {
        if (!key) {
            return;
        }
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = keyToIndex_.find(key);
        if (it != keyToIndex_.end()) {
            definitions_[it->second].visible = visible;
        }
    }

    // Returns pointer to stable internal string value (valid while store is not re-registered).
    // Safe for RETRO_ENVIRONMENT_GET_VARIABLE on the emulator thread.
    const char* getValueCStr(const char* key) {
        if (!key) {
            return nullptr;
        }
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = keyToIndex_.find(key);
        if (it == keyToIndex_.end()) {
            return nullptr;
        }
        lastReturnedValues_[key] = definitions_[it->second].currentValue;
        return lastReturnedValues_[key].c_str();
    }

    bool setValue(const std::string& key, const std::string& value) {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = keyToIndex_.find(key);
        if (it == keyToIndex_.end()) {
            return false;
        }
        auto& def = definitions_[it->second];
        if (!hasChoice(def, value)) {
            return false;
        }
        if (def.currentValue != value) {
            def.currentValue = value;
            pendingOverrides_[key] = value;
            dirty_.store(true, std::memory_order_release);
        }
        return true;
    }

    bool consumeDirtyFlag() noexcept {
        return dirty_.exchange(false, std::memory_order_acq_rel);
    }

    std::vector<CoreOptionDefinition> snapshot() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return definitions_;
    }

    // Serializes all dynamically advertised core options to RFC-8259 JSON for JNI transport.
    std::string toJson() const {
        std::lock_guard<std::mutex> lock(mutex_);
        std::string out;
        out.reserve(definitions_.size() * 256 + 16);
        out.push_back('[');
        for (size_t i = 0; i < definitions_.size(); ++i) {
            if (i > 0) out.push_back(',');
            const auto& d = definitions_[i];
            out += "{\"key\":";
            appendJsonString(out, d.key);
            out += ",\"desc\":";
            appendJsonString(out, d.desc);
            out += ",\"info\":";
            appendJsonString(out, d.info);
            out += ",\"categoryKey\":";
            appendJsonString(out, d.categoryKey);
            out += ",\"defaultValue\":";
            appendJsonString(out, d.defaultValue);
            out += ",\"currentValue\":";
            appendJsonString(out, d.currentValue);
            out += ",\"visible\":";
            out += d.visible ? "true" : "false";
            out += ",\"choices\":[";
            for (size_t c = 0; c < d.choices.size(); ++c) {
                if (c > 0) out.push_back(',');
                out += "{\"value\":";
                appendJsonString(out, d.choices[c].value);
                out += ",\"label\":";
                appendJsonString(out, d.choices[c].label);
                out.push_back('}');
            }
            out += "]}";
        }
        out.push_back(']');
        return out;
    }

    void clear() {
        std::lock_guard<std::mutex> lock(mutex_);
        definitions_.clear();
        keyToIndex_.clear();
        lastReturnedValues_.clear();
        dirty_.store(false, std::memory_order_release);
    }

private:
    static bool hasChoice(const CoreOptionDefinition& def, const std::string& value) {
        for (const auto& c : def.choices) {
            if (c.value == value) {
                return true;
            }
        }
        return false;
    }

    static void appendJsonString(std::string& out, const std::string& s) {
        out.push_back('"');
        for (unsigned char ch : s) {
            switch (ch) {
                case '"':  out += "\\\""; break;
                case '\\': out += "\\\\"; break;
                case '\b': out += "\\b";  break;
                case '\f': out += "\\f";  break;
                case '\n': out += "\\n";  break;
                case '\r': out += "\\r";  break;
                case '\t': out += "\\t";  break;
                default:
                    if (ch < 0x20) {
                        char hex[7];
                        std::snprintf(hex, sizeof(hex), "\\u%04x", static_cast<unsigned>(ch));
                        out += hex;
                    } else {
                        out.push_back(static_cast<char>(ch));
                    }
                    break;
            }
        }
        out.push_back('"');
    }

    mutable std::mutex mutex_;
    std::vector<CoreOptionDefinition> definitions_;
    std::unordered_map<std::string, size_t> keyToIndex_;
    std::unordered_map<std::string, std::string> pendingOverrides_;
    std::unordered_map<std::string, std::string> lastReturnedValues_;
    std::atomic<bool> dirty_{false};
};

}  // namespace gba
