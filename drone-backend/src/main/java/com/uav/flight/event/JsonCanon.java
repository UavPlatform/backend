package com.uav.flight.event;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict JSON reader plus RFC 8785 canonical bytes for the integer profile only.
 * Floats, exponents, leading zeros, -0, duplicate keys, unpaired surrogates, invalid UTF-8 and a BOM are rejected
 * before canonicalization. This is not a general-number JCS implementation and does not normalize Unicode.
 * erdtman java-json-canonicalization 1.1 is intentionally not used here: it is not a strict parser.
 */
public final class JsonCanon {
    private JsonCanon() { }

    public static JsonValue parse(byte[] raw) {
        if (raw == null || raw.length == 0) throw EventFormatException.malformed("SYNTAX");
        if (raw.length > EventLimits.MAX_BODY_BYTES) throw EventFormatException.malformed("LIMIT");
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) {
            throw EventFormatException.malformed("BOM");
        }
        var parser = new Parser(decode(raw));
        JsonValue value = parser.value(0);
        parser.skipWs();
        if (!parser.end()) throw EventFormatException.malformed("TRAILING");
        return value;
    }

    public static byte[] canonical(JsonValue value) {
        var out = new ByteArrayOutputStream();
        write(value, out);
        return out.toByteArray();
    }

    public static String canonicalText(JsonValue value) {
        return new String(canonical(value), StandardCharsets.UTF_8);
    }

    public static JsonValue.Obj without(JsonValue.Obj object, String key) {
        var copy = new LinkedHashMap<String, JsonValue>();
        object.fields().forEach((name, child) -> {
            if (!name.equals(key)) copy.put(name, child);
        });
        return new JsonValue.Obj(copy);
    }

    private static String decode(byte[] raw) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            throw EventFormatException.malformed("UTF8");
        }
    }

    private static void write(JsonValue value, ByteArrayOutputStream out) {
        switch (value) {
            case JsonValue.Obj object -> {
                out.write('{');
                var keys = new ArrayList<>(object.fields().keySet());
                keys.sort(String::compareTo);
                for (int i = 0; i < keys.size(); i++) {
                    if (i > 0) out.write(',');
                    writeString(keys.get(i), out);
                    out.write(':');
                    write(object.fields().get(keys.get(i)), out);
                }
                out.write('}');
            }
            case JsonValue.Arr array -> {
                out.write('[');
                for (int i = 0; i < array.items().size(); i++) {
                    if (i > 0) out.write(',');
                    write(array.items().get(i), out);
                }
                out.write(']');
            }
            case JsonValue.Str string -> writeString(string.text(), out);
            case JsonValue.Int number -> ascii(Long.toString(number.value()), out);
            case JsonValue.Bool bool -> ascii(bool.value() ? "true" : "false", out);
            case JsonValue.Nil nil -> ascii("null", out);
        }
    }

    private static void writeString(String text, ByteArrayOutputStream out) {
        out.write('"');
        for (int i = 0; i < text.length(); ) {
            char c = text.charAt(i);
            if (c == '"') { ascii("\\\"", out); i++; continue; }
            if (c == '\\') { ascii("\\\\", out); i++; continue; }
            if (c == '\b') { ascii("\\b", out); i++; continue; }
            if (c == '\f') { ascii("\\f", out); i++; continue; }
            if (c == '\n') { ascii("\\n", out); i++; continue; }
            if (c == '\r') { ascii("\\r", out); i++; continue; }
            if (c == '\t') { ascii("\\t", out); i++; continue; }
            if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                ascii(String.format("\\u%04x", (int) c), out);
                i++;
                continue;
            }
            int cp;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    throw EventFormatException.malformed("SURROGATE");
                }
                cp = Character.toCodePoint(c, text.charAt(i + 1));
                i += 2;
            } else if (Character.isLowSurrogate(c)) {
                throw EventFormatException.malformed("SURROGATE");
            } else {
                cp = c;
                i++;
            }
            utf8(cp, out);
        }
        out.write('"');
    }

    private static void ascii(String text, ByteArrayOutputStream out) {
        for (int i = 0; i < text.length(); i++) out.write(text.charAt(i));
    }

    private static void utf8(int cp, ByteArrayOutputStream out) {
        if (cp < 0x80) out.write(cp);
        else if (cp < 0x800) {
            out.write(0xC0 | (cp >> 6));
            out.write(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out.write(0xE0 | (cp >> 12));
            out.write(0x80 | ((cp >> 6) & 0x3F));
            out.write(0x80 | (cp & 0x3F));
        } else {
            out.write(0xF0 | (cp >> 18));
            out.write(0x80 | ((cp >> 12) & 0x3F));
            out.write(0x80 | ((cp >> 6) & 0x3F));
            out.write(0x80 | (cp & 0x3F));
        }
    }

    private static final class Parser {
        private final String text;
        private int index;

        private Parser(String text) { this.text = text; }

        private JsonValue value(int depth) {
            if (depth > EventLimits.MAX_DEPTH) throw EventFormatException.malformed("DEPTH");
            skipWs();
            if (end()) throw EventFormatException.malformed("SYNTAX");
            return switch (text.charAt(index)) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> new JsonValue.Str(string());
                case 't' -> literal("true", new JsonValue.Bool(true));
                case 'f' -> literal("false", new JsonValue.Bool(false));
                case 'n' -> literal("null", new JsonValue.Nil());
                case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> number();
                default -> throw EventFormatException.malformed("SYNTAX");
            };
        }

        private JsonValue object(int depth) {
            take();
            var fields = new LinkedHashMap<String, JsonValue>();
            skipWs();
            if (peek('}')) { take(); return new JsonValue.Obj(fields); }
            while (true) {
                skipWs();
                if (end() || text.charAt(index) != '"') throw EventFormatException.malformed("SYNTAX");
                String key = string();
                if (fields.containsKey(key)) throw EventFormatException.malformed("DUPLICATE_KEY");
                if (fields.size() >= EventLimits.MAX_OBJECT_KEYS) throw EventFormatException.malformed("LIMIT");
                skipWs();
                expect(':');
                fields.put(key, value(depth + 1));
                skipWs();
                if (peek('}')) { take(); return new JsonValue.Obj(Map.copyOf(fields)); }
                expect(',');
            }
        }

        private JsonValue array(int depth) {
            take();
            var items = new ArrayList<JsonValue>();
            skipWs();
            if (peek(']')) { take(); return new JsonValue.Arr(items); }
            while (true) {
                if (items.size() >= EventLimits.MAX_ARRAY) throw EventFormatException.malformed("LIMIT");
                items.add(value(depth + 1));
                skipWs();
                if (peek(']')) { take(); return new JsonValue.Arr(List.copyOf(items)); }
                expect(',');
            }
        }

        private JsonValue number() {
            int start = index;
            if (peek('-')) take();
            if (end() || !digit(text.charAt(index))) throw EventFormatException.malformed("NUMBER");
            if (text.charAt(index) == '0') {
                take();
                if (!end() && digit(text.charAt(index))) throw EventFormatException.malformed("NUMBER");
            } else {
                while (!end() && digit(text.charAt(index))) take();
            }
            if (!end() && (text.charAt(index) == '.' || text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                throw EventFormatException.malformed("NUMBER");
            }
            String token = text.substring(start, index);
            if ("-".equals(token) || "-0".equals(token)) throw EventFormatException.malformed("NUMBER");
            BigInteger magnitude = new BigInteger(token);
            if (magnitude.abs().compareTo(BigInteger.valueOf(EventLimits.MAX_SAFE_INTEGER)) > 0) {
                throw EventFormatException.malformed("NUMBER");
            }
            return new JsonValue.Int(magnitude.longValueExact());
        }

        private String string() {
            expect('"');
            var out = new StringBuilder();
            while (!end()) {
                char c = take();
                if (c == '"') {
                    if (out.length() > EventLimits.MAX_STRING_CHARS) throw EventFormatException.malformed("LIMIT");
                    return out.toString();
                }
                if (c == '\\') { appendEscape(out); continue; }
                if (c < 0x20) throw EventFormatException.malformed("SYNTAX");
                appendRaw(out, c);
            }
            throw EventFormatException.malformed("SYNTAX");
        }

        private void appendRaw(StringBuilder out, char c) {
            if (Character.isHighSurrogate(c)) {
                if (end() || !Character.isLowSurrogate(text.charAt(index))) throw EventFormatException.malformed("SURROGATE");
                out.append(c).append(take());
                return;
            }
            if (Character.isLowSurrogate(c)) throw EventFormatException.malformed("SURROGATE");
            out.append(c);
        }

        private void appendEscape(StringBuilder out) {
            if (end()) throw EventFormatException.malformed("SYNTAX");
            char c = take();
            switch (c) {
                case '"', '\\', '/' -> out.append(c);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> appendUnicode(out);
                default -> throw EventFormatException.malformed("SYNTAX");
            }
        }

        private void appendUnicode(StringBuilder out) {
            int unit = hex4();
            if (Character.isLowSurrogate((char) unit)) throw EventFormatException.malformed("SURROGATE");
            if (Character.isHighSurrogate((char) unit)) {
                if (end() || take() != '\\' || end() || take() != 'u') throw EventFormatException.malformed("SURROGATE");
                int low = hex4();
                if (!Character.isLowSurrogate((char) low)) throw EventFormatException.malformed("SURROGATE");
                out.append((char) unit).append((char) low);
                return;
            }
            out.append((char) unit);
        }

        private int hex4() {
            int value = 0;
            for (int i = 0; i < 4; i++) {
                if (end()) throw EventFormatException.malformed("SYNTAX");
                int nibble = Character.digit(take(), 16);
                if (nibble < 0) throw EventFormatException.malformed("SYNTAX");
                value = (value << 4) | nibble;
            }
            return value;
        }

        private JsonValue literal(String word, JsonValue value) {
            for (int i = 0; i < word.length(); i++) {
                if (end() || take() != word.charAt(i)) throw EventFormatException.malformed("SYNTAX");
            }
            if (!end()) {
                char c = text.charAt(index);
                if (Character.isLetterOrDigit(c) || c == '_') throw EventFormatException.malformed("SYNTAX");
            }
            return value;
        }

        private void skipWs() {
            while (!end()) {
                char c = text.charAt(index);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') return;
                index++;
            }
        }

        private void expect(char c) {
            if (end() || take() != c) throw EventFormatException.malformed("SYNTAX");
        }

        private boolean peek(char c) { return !end() && text.charAt(index) == c; }
        private char take() { return text.charAt(index++); }
        private boolean end() { return index >= text.length(); }
        private static boolean digit(char c) { return c >= '0' && c <= '9'; }
    }
}
