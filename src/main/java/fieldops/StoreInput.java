package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Set;

final class StoreInput {
    private StoreInput() {}

    static void fields(ObjectNode input, String... names) {
        Set<String> allowed = Set.of(names);
        input.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw new Problem(422, "Unexpected field: " + name);
        });
    }

    static String text(ObjectNode input, String key, int min, int max) {
        JsonNode value = input.get(key);
        if (value == null || !value.isTextual()) throw invalid(key);
        String string = value.textValue().strip();
        int size = string.codePointCount(0, string.length());
        if (size < min || size > max) throw invalid(key);
        return string;
    }

    static BigDecimal decimal(ObjectNode input, String key) {
        JsonNode value = input.get(key);
        if (value == null || !(value.isNumber() || value.isTextual())) throw invalid(key);
        try {
            BigDecimal number = new BigDecimal(value.asText().strip()).stripTrailingZeros();
            if (number.scale() > 2 || number.signum() < 0 ||
                    number.compareTo(BigDecimal.valueOf(10_000_000)) > 0) throw invalid(key);
            return number;
        } catch (NumberFormatException | ArithmeticException e) {
            throw invalid(key);
        }
    }

    static long integer(JsonNode node, String key) {
        if (node != null && (node.isIntegralNumber() || node.isTextual())) {
            try {
                return Long.parseLong(node.asText().strip());
            } catch (NumberFormatException ignored) {}
        }
        throw invalid(key);
    }

    static ArrayNode array(ObjectNode input, String key, int min, int max) {
        if (input.get(key) instanceof ArrayNode list && list.size() >= min && list.size() <= max) return list;
        throw invalid(key);
    }

    static Set<Long> ids(ObjectNode input, String key, int min, int max) {
        Set<Long> ids = new LinkedHashSet<>();
        for (JsonNode id : array(input, key, min, max)) ids.add(integer(id, key));
        return ids;
    }

    static String date(String value) {
        try {
            LocalDate parsed = LocalDate.parse(value);
            if (parsed.getYear() < 1 || parsed.getYear() > 9999) throw invalid("date");
            return parsed.toString();
        } catch (DateTimeParseException e) {
            throw invalid("date");
        }
    }

    static Problem invalid(String key) {
        return new Problem(422, "Invalid field: " + key);
    }
}
