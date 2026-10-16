/* Minimal JSON reader, enough for the scene.json files written by CyclesSceneExporter. */

#pragma once

#include <cstdlib>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

struct Json {
  enum Type { NUL, BOOL, NUMBER, STRING, ARRAY, OBJECT } type = NUL;
  bool boolean = false;
  double number = 0;
  std::string string;
  std::vector<Json> array;
  std::vector<std::pair<std::string, Json>> object;

  const Json &operator[](const std::string &key) const
  {
    static const Json null;
    for (const auto &member : object) {
      if (member.first == key) {
        return member.second;
      }
    }
    return null;
  }
  const Json &operator[](const size_t index) const
  {
    return array[index];
  }
  bool has(const std::string &key) const
  {
    return (*this)[key].type != NUL;
  }
  bool is_null() const
  {
    return type == NUL;
  }
  double num(const double fallback = 0) const
  {
    return type == NUMBER ? number : fallback;
  }
  std::string str(const std::string &fallback = "") const
  {
    return type == STRING ? string : fallback;
  }
  size_t size() const
  {
    return type == ARRAY ? array.size() : object.size();
  }

  static Json parse(const std::string &text)
  {
    const char *p = text.c_str();
    Json value = parse_value(p);
    skip_whitespace(p);
    if (*p) {
      throw std::runtime_error("Trailing characters in JSON");
    }
    return value;
  }

 private:
  static void skip_whitespace(const char *&p)
  {
    while (*p == ' ' || *p == '\n' || *p == '\r' || *p == '\t') {
      p++;
    }
  }

  static std::string parse_string(const char *&p)
  {
    std::string result;
    p++; /* Opening quote. */
    while (*p && *p != '"') {
      if (*p == '\\') {
        p++;
        switch (*p) {
          case 'n': result += '\n'; break;
          case 't': result += '\t'; break;
          case 'r': result += '\r'; break;
          case 'b': result += '\b'; break;
          case 'f': result += '\f'; break;
          case 'u': {
            const unsigned code = std::strtoul(std::string(p + 1, 4).c_str(), nullptr, 16);
            if (code < 0x80) {
              result += char(code);
            }
            else if (code < 0x800) {
              result += char(0xC0 | (code >> 6));
              result += char(0x80 | (code & 0x3F));
            }
            else {
              result += char(0xE0 | (code >> 12));
              result += char(0x80 | ((code >> 6) & 0x3F));
              result += char(0x80 | (code & 0x3F));
            }
            p += 4;
            break;
          }
          default: result += *p; break;
        }
        p++;
      }
      else {
        result += *p++;
      }
    }
    if (*p != '"') {
      throw std::runtime_error("Unterminated JSON string");
    }
    p++;
    return result;
  }

  static Json parse_value(const char *&p)
  {
    skip_whitespace(p);
    Json value;
    if (*p == '{') {
      value.type = OBJECT;
      p++;
      skip_whitespace(p);
      while (*p && *p != '}') {
        skip_whitespace(p);
        std::string key = parse_string(p);
        skip_whitespace(p);
        if (*p++ != ':') {
          throw std::runtime_error("Expected ':' in JSON object");
        }
        value.object.emplace_back(std::move(key), parse_value(p));
        skip_whitespace(p);
        if (*p == ',') {
          p++;
        }
      }
      if (*p++ != '}') {
        throw std::runtime_error("Unterminated JSON object");
      }
    }
    else if (*p == '[') {
      value.type = ARRAY;
      p++;
      skip_whitespace(p);
      while (*p && *p != ']') {
        value.array.push_back(parse_value(p));
        skip_whitespace(p);
        if (*p == ',') {
          p++;
        }
      }
      if (*p++ != ']') {
        throw std::runtime_error("Unterminated JSON array");
      }
    }
    else if (*p == '"') {
      value.type = STRING;
      value.string = parse_string(p);
    }
    else if (std::string(p, 4) == "true") {
      value.type = BOOL;
      value.boolean = true;
      p += 4;
    }
    else if (std::string(p, 5) == "false") {
      value.type = BOOL;
      p += 5;
    }
    else if (std::string(p, 4) == "null") {
      p += 4;
    }
    else {
      char *end;
      value.type = NUMBER;
      value.number = std::strtod(p, &end);
      if (end == p) {
        throw std::runtime_error("Invalid JSON value");
      }
      p = end;
    }
    return value;
  }
};
