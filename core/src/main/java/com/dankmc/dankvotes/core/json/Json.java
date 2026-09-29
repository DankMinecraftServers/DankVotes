package com.dankmc.dankvotes.core.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A tiny, self-contained JSON parser and serializer.
 *
 * DankVotes only needs a small slice of JSON functionality (parse API responses, read/write
 * a small data file, build request bodies). Rather than bundle a third-party library and
 * deal with shading/relocation, we ship this ~300-line implementation. Zero dependencies.
 *
 * Supports: objects, arrays, strings, numbers (as long/double), booleans, null.
 * Not a general-purpose library — just enough, correct, and dependency-free.
 */
public final class Json {
    private Json() {}

    public static JsonObject parseObject(String s) {
        Object v = new Parser(s).parseValue();
        if (!(v instanceof JsonObject)) throw new JsonException("Expected JSON object");
        return (JsonObject) v;
    }

    public static JsonArray parseArray(String s) {
        Object v = new Parser(s).parseValue();
        if (!(v instanceof JsonArray)) throw new JsonException("Expected JSON array");
        return (JsonArray) v;
    }

    /** Parse any JSON value (object, array, primitive). */
    public static Object parse(String s) {
        return new Parser(s).parseValue();
    }

    // ─────────────────────────────────────────────────────────────────
    // JSON object
    // ─────────────────────────────────────────────────────────────────
    public static final class JsonObject {
        private final Map<String, Object> map = new LinkedHashMap<String, Object>();

        public JsonObject put(String key, Object value) { map.put(key, value); return this; }
        public boolean has(String key) { return map.containsKey(key); }
        public Object get(String key) { return map.get(key); }
        public Set<String> keys() { return map.keySet(); }
        public int size() { return map.size(); }

        public String optString(String key, String def) {
            Object o = map.get(key);
            return o == null ? def : String.valueOf(o);
        }
        public String optString(String key) { return optString(key, ""); }

        public long optLong(String key, long def) {
            Object o = map.get(key);
            if (o instanceof Number) return ((Number) o).longValue();
            try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return def; }
        }
        public long optLong(String key) { return optLong(key, 0L); }

        public int optInt(String key, int def) {
            Object o = map.get(key);
            if (o instanceof Number) return ((Number) o).intValue();
            try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return def; }
        }

        public boolean optBoolean(String key, boolean def) {
            Object o = map.get(key);
            if (o instanceof Boolean) return (Boolean) o;
            if (o != null) return Boolean.parseBoolean(String.valueOf(o));
            return def;
        }

        public JsonObject optObject(String key) {
            Object o = map.get(key);
            return o instanceof JsonObject ? (JsonObject) o : null;
        }

        public JsonArray optArray(String key) {
            Object o = map.get(key);
            return o instanceof JsonArray ? (JsonArray) o : null;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            Writer.writeObject(this, sb);
            return sb.toString();
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // JSON array
    // ─────────────────────────────────────────────────────────────────
    public static final class JsonArray {
        private final List<Object> list = new ArrayList<Object>();

        public JsonArray put(Object value) { list.add(value); return this; }
        public int length() { return list.size(); }
        public Object get(int i) { return list.get(i); }
        public boolean isEmpty() { return list.isEmpty(); }

        public JsonObject getObject(int i) {
            Object o = list.get(i);
            return o instanceof JsonObject ? (JsonObject) o : null;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            Writer.writeArray(this, sb);
            return sb.toString();
        }
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String msg) { super(msg); }
    }

    // ─────────────────────────────────────────────────────────────────
    // Parser
    // ─────────────────────────────────────────────────────────────────
    private static final class Parser {
        private final String s;
        private int i = 0;

        Parser(String s) { this.s = s == null ? "" : s; }

        Object parseValue() {
            skipWhitespace();
            if (i >= s.length()) throw new JsonException("Unexpected end of JSON");
            char c = s.charAt(i);
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return parseString();
                case 't': case 'f': return parseBoolean();
                case 'n': return parseNull();
                default:  return parseNumber();
            }
        }

        private JsonObject parseObject() {
            JsonObject obj = new JsonObject();
            expect('{');
            skipWhitespace();
            if (peek() == '}') { i++; return obj; }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                obj.put(key, value);
                skipWhitespace();
                char c = next();
                if (c == '}') break;
                if (c != ',') throw new JsonException("Expected ',' or '}' at " + i);
            }
            return obj;
        }

        private JsonArray parseArray() {
            JsonArray arr = new JsonArray();
            expect('[');
            skipWhitespace();
            if (peek() == ']') { i++; return arr; }
            while (true) {
                Object value = parseValue();
                arr.put(value);
                skipWhitespace();
                char c = next();
                if (c == ']') break;
                if (c != ',') throw new JsonException("Expected ',' or ']' at " + i);
            }
            return arr;
        }

        private String parseString() {
            skipWhitespace();
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char esc = s.charAt(i++);
                    switch (esc) {
                        case '"':  sb.append('"');  break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/');  break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        case 'u':
                            String hex = s.substring(i, i + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                            break;
                        default: throw new JsonException("Bad escape \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new JsonException("Unterminated string");
        }

        private Object parseNumber() {
            int start = i;
            while (i < s.length() && "-+0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String num = s.substring(start, i);
            if (num.isEmpty()) throw new JsonException("Invalid number at " + start);
            if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0) {
                return Double.parseDouble(num);
            }
            try {
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                return Double.parseDouble(num);
            }
        }

        private Boolean parseBoolean() {
            if (s.startsWith("true", i))  { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            throw new JsonException("Invalid literal at " + i);
        }

        private Object parseNull() {
            if (s.startsWith("null", i)) { i += 4; return null; }
            throw new JsonException("Invalid literal at " + i);
        }

        private void skipWhitespace() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
        private char peek() { skipWhitespace(); return i < s.length() ? s.charAt(i) : '\0'; }
        private char next() { return s.charAt(i++); }
        private void expect(char c) {
            skipWhitespace();
            if (i >= s.length() || s.charAt(i) != c) throw new JsonException("Expected '" + c + "' at " + i);
            i++;
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Writer
    // ─────────────────────────────────────────────────────────────────
    private static final class Writer {
        static void writeValue(Object v, StringBuilder sb) {
            if (v == null) { sb.append("null"); }
            else if (v instanceof JsonObject) writeObject((JsonObject) v, sb);
            else if (v instanceof JsonArray) writeArray((JsonArray) v, sb);
            else if (v instanceof String) writeString((String) v, sb);
            else if (v instanceof Boolean) sb.append(v.toString());
            else if (v instanceof Number) sb.append(v.toString());
            else writeString(String.valueOf(v), sb);
        }

        static void writeObject(JsonObject obj, StringBuilder sb) {
            sb.append('{');
            boolean first = true;
            for (String key : obj.keys()) {
                if (!first) sb.append(',');
                first = false;
                writeString(key, sb);
                sb.append(':');
                writeValue(obj.get(key), sb);
            }
            sb.append('}');
        }

        static void writeArray(JsonArray arr, StringBuilder sb) {
            sb.append('[');
            for (int j = 0; j < arr.length(); j++) {
                if (j > 0) sb.append(',');
                writeValue(arr.get(j), sb);
            }
            sb.append(']');
        }

        static void writeString(String str, StringBuilder sb) {
            sb.append('"');
            for (int j = 0; j < str.length(); j++) {
                char c = str.charAt(j);
                switch (c) {
                    case '"':  sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n");  break;
                    case '\r': sb.append("\\r");  break;
                    case '\t': sb.append("\\t");  break;
                    case '\b': sb.append("\\b");  break;
                    case '\f': sb.append("\\f");  break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            sb.append('"');
        }
    }
}
