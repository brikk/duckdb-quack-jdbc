package com.gizmodata.quack.jdbc.sql;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * SQL context for Quack's untagged count-shaped results. This is a conservative
 * lexical classifier, not a SQL parser: unknown or multi-statement text stays a
 * ResultSet. It neither rewrites SQL nor executes metadata probes.
 */
enum SqlResultKind {
    DML_COUNT, OTHER_COUNT, ROWS_OR_UNKNOWN;

    static SqlResultKind of(String sql) {
        Tokens tokens = new Tokens(sql);
        String command = tokens.next();
        while (command.equals(";")) command = tokens.next();
        if (command.equals("WITH")) command = afterCtes(tokens);
        SqlResultKind kind = switch (command) {
            case "INSERT", "UPDATE", "DELETE", "MERGE" -> DML_COUNT;
            case "CREATE", "ALTER", "DROP", "TRUNCATE", "COPY", "ATTACH", "DETACH",
                    "INSTALL", "LOAD", "UNLOAD", "SET", "RESET", "BEGIN", "START",
                    "COMMIT", "END", "ROLLBACK", "SAVEPOINT", "RELEASE", "CHECKPOINT",
                    "FORCE", "VACUUM", "ANALYZE", "COMMENT", "GRANT", "REVOKE" -> OTHER_COUNT;
            default -> ROWS_OR_UNKNOWN;
        };
        if (kind == ROWS_OR_UNKNOWN) return kind;
        Deque<String> groups = new ArrayDeque<>();
        boolean returning = false;
        for (String token = tokens.next(); !token.isEmpty(); token = tokens.next()) {
            if (open(token)) groups.push(token);
            else if (close(token)) {
                if (groups.isEmpty() || !matches(groups.pop(), token)) return ROWS_OR_UNKNOWN;
            } else if (groups.isEmpty()) {
                if (token.equals("RETURNING")) returning = true;
                if (token.equals(";")) {
                    // No wire statement tag exists to associate a result with one
                    // command in a batch. Empty trailing statements are harmless.
                    do { token = tokens.next(); } while (token.equals(";"));
                    if (!token.isEmpty()) return ROWS_OR_UNKNOWN;
                    break;
                }
            }
        }
        if (!tokens.valid || !groups.isEmpty() || kind == DML_COUNT && returning) return ROWS_OR_UNKNOWN;
        return kind;
    }

    private static String afterCtes(Tokens tokens) {
        String name = tokens.next();
        if (name.equals("RECURSIVE")) name = tokens.next();
        while (!name.isEmpty()) {
            if (!name.equals("#") && !identifierStart(name.charAt(0))) return "";
            String token = tokens.next();
            if (token.equals("(")) {
                if (!group(tokens, token)) return "";
                token = tokens.next();
            }
            if (token.equals("USING")) {
                if (!tokens.next().equals("KEY") || !tokens.next().equals("(") || !group(tokens, "(")) return "";
                token = tokens.next();
            }
            if (!token.equals("AS")) return "";
            token = tokens.next();
            if (token.equals("NOT")) {
                if (!tokens.next().equals("MATERIALIZED")) return "";
                token = tokens.next();
            } else if (token.equals("MATERIALIZED")) token = tokens.next();
            if (!token.equals("(") || !group(tokens, token)) return "";
            token = tokens.next();
            if (!token.equals(",")) return token;
            name = tokens.next();
        }
        return "";
    }

    private static boolean group(Tokens tokens, String first) {
        Deque<String> groups = new ArrayDeque<>();
        groups.push(first);
        while (!groups.isEmpty()) {
            String token = tokens.next();
            if (token.isEmpty()) return false;
            if (open(token)) groups.push(token);
            else if (close(token) && !matches(groups.pop(), token)) return false;
        }
        return tokens.valid;
    }

    private static boolean open(String token) { return token.equals("(") || token.equals("[") || token.equals("{"); }
    private static boolean close(String token) { return token.equals(")") || token.equals("]") || token.equals("}"); }
    private static boolean matches(String left, String right) {
        return left.equals("(") && right.equals(")") || left.equals("[") && right.equals("]")
                || left.equals("{") && right.equals("}");
    }

    private static boolean identifierStart(char c) {
        return c == '_' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= 128;
    }

    private static boolean identifierPart(char c) {
        return identifierStart(c) || c >= '0' && c <= '9' || c == '$';
    }

    private static boolean whitespace(char c) {
        // Match DuckDB's pre-lexing whitespace normalization as well as ASCII SQL space.
        return c == ' ' || c >= '\t' && c <= '\r' || c == 0x00a0 || c >= 0x2000 && c <= 0x200b
                || c == 0x202f || c == 0x205f || c == 0x2060 || c == 0x3000 || c == 0xfeff;
    }

    private static final class Tokens {
        private final String sql;
        private int position;
        private boolean valid = true;
        private boolean escapeLiteral;

        private Tokens(String sql) { this.sql = sql; }

        private String next() {
            boolean newline = trivia();
            boolean escapes = escapeLiteral && newline;
            escapeLiteral = false;
            if (!valid || position == sql.length()) return "";
            char c = sql.charAt(position++);
            if (c == '\'' || c == '"') {
                quoted(c, c == '\'' && escapes);
                escapeLiteral = c == '\'' && escapes;
                return c == '"' ? "#" : "?";
            }
            if (identifierStart(c) && !whitespace(c)) {
                int start = position - 1;
                while (position < sql.length() && identifierPart(sql.charAt(position))
                        && !whitespace(sql.charAt(position))) position++;
                String word = sql.substring(start, position).toUpperCase(Locale.ROOT);
                if (word.equals("E") && position < sql.length() && sql.charAt(position) == '\'') {
                    position++;
                    quoted('\'', true);
                    escapeLiteral = true;
                    return "?";
                }
                return word;
            }
            if (c == '$') {
                int start = position - 1;
                int end = position;
                if (end < sql.length() && identifierStart(sql.charAt(end)) && !whitespace(sql.charAt(end))) {
                    do { end++; } while (end < sql.length() && (identifierStart(sql.charAt(end))
                            || sql.charAt(end) >= '0' && sql.charAt(end) <= '9') && !whitespace(sql.charAt(end)));
                }
                if (end < sql.length() && sql.charAt(end) == '$') {
                    String delimiter = sql.substring(start, end + 1);
                    int closing = sql.indexOf(delimiter, end + 1);
                    if (closing < 0) { valid = false; return ""; }
                    position = closing + delimiter.length();
                    return "?";
                }
            }
            return switch (c) {
                case '(' -> "(";
                case ')' -> ")";
                case '[' -> "[";
                case ']' -> "]";
                case '{' -> "{";
                case '}' -> "}";
                case ',' -> ",";
                case ';' -> ";";
                default -> "?";
            };
        }

        private void quoted(char quote, boolean escapes) {
            while (position < sql.length()) {
                char c = sql.charAt(position++);
                if (escapes && c == '\\' && position < sql.length()) position++;
                else if (c == quote) {
                    if (position < sql.length() && sql.charAt(position) == quote) position++;
                    else return;
                }
            }
            valid = false;
        }

        private boolean trivia() {
            boolean newline = false;
            while (position < sql.length()) {
                char c = sql.charAt(position);
                if (whitespace(c)) {
                    newline |= c == '\n' || c == '\r';
                    position++;
                } else if (sql.startsWith("--", position)) {
                    position += 2;
                    while (position < sql.length() && sql.charAt(position) != '\n' && sql.charAt(position) != '\r') position++;
                } else if (sql.startsWith("/*", position)) {
                    position += 2;
                    int depth = 1;
                    while (position < sql.length() && depth != 0) {
                        if (sql.startsWith("/*", position)) { depth++; position += 2; }
                        else if (sql.startsWith("*/", position)) { depth--; position += 2; }
                        else { c = sql.charAt(position++); newline |= c == '\n' || c == '\r'; }
                    }
                    if (depth != 0) valid = false;
                } else break;
            }
            return newline;
        }
    }
}
