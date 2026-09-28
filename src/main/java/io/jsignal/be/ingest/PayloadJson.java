package io.jsignal.be.ingest;

import com.fasterxml.jackson.databind.JsonNode;

final class PayloadJson {

    private PayloadJson() {
    }

    static JsonNode firstPresent(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    static Double parseDouble(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            String s = node.asText().trim();
            if (s.isEmpty()) {
                return null;
            }
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String s = node.asText().trim();
        return s.isEmpty() ? null : s;
    }

    static Float toFloat(Double d) {
        return d == null ? null : d.floatValue();
    }

    static Float bearing(Double d) {
        if (d == null || d < 0 || d >= 360) {
            return null;
        }
        return d.floatValue();
    }
}
