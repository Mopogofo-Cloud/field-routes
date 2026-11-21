package fieldops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Quantity requirements for rates explicitly identified as road-bore rates on the entity's card. */
final class RoadBoreRules {
    private RoadBoreRules() {}

    static Map<String,String> forCard(ObjectNode card) {
        if (card == null) return Map.of();
        var bindings = new LinkedHashMap<String,String>();
        if (EntityRateFields.roadOnlyPerFoot(card) && present(card, "boreRatePerFoot"))
            bindings.put(EntityRateFields.GENERIC_BORE_KEY, "road_bore_feet");
        if (present(card, "boreRoadBoreRate"))
            bindings.put("wg_bore_road_bore_rate", "road_bore_feet");
        if (present(card, "googleLaborBoreRoadBoreRate"))
            bindings.put("wg_google_labor_bore_road_bore_rate", "road_bore_count");
        return bindings;
    }

    private static boolean present(ObjectNode card, String key) {
        return !card.path(key).asText("").isBlank();
    }

    static List<TermCheck> checks(Map<String,String> bindings) {
        if (bindings.isEmpty()) return List.of();
        var grouped = new LinkedHashMap<String,Set<String>>();
        bindings.forEach((rate, measure) -> grouped.computeIfAbsent(measure, ignored -> new HashSet<>()).add(rate));
        return grouped.entrySet().stream().map(e -> new TermCheck(e.getKey(), e.getValue())).toList();
    }

    private enum Kind {
        SCALAR,        // Decimal literals or crew-size arithmetic may scale an otherwise valid charge.
        QUANTITY,      // This particular rate's eligible, recorded road measure.
        RATE,          // An occurrence of a restricted rate still needs its quantity.
        CHARGE,        // One restricted rate multiplied by one matching road measure.
        OTHER          // An unrelated input or subexpression.
    }

    /**
     * Consume the parser's operands/operators in postfix order. A sum remembers every kind of term, so an
     * unrelated additive amount stays legal while a rate times (road + sidewalk) cannot pass as road work.
     * There are only five possible kinds per stack entry, even for long or deeply factored expressions.
     */
    static final class TermCheck {
        private final String quantity;
        private final Set<String> rates;
        private final ArrayDeque<EnumSet<Kind>> stack = new ArrayDeque<>();

        TermCheck(String quantity, Set<String> rates) {
            this.quantity = quantity;
            this.rates = rates;
        }

        void constant() { stack.push(EnumSet.of(Kind.SCALAR)); }

        void variable(String name) {
            Kind kind;
            if (rates.contains(name)) kind = Kind.RATE;
            else if (quantity.equals(name)) kind = Kind.QUANTITY;
            else if ("crew_size".equals(name)) kind = Kind.SCALAR;
            else kind = Kind.OTHER;
            stack.push(EnumSet.of(kind));
        }

        void combine(char operator) {
            var right = stack.pop();
            var left = stack.pop();
            if (operator == '+' || operator == '-') {
                left.addAll(right);
                stack.push(left);
                return;
            }
            var result = EnumSet.noneOf(Kind.class);
            for (var leftTerm : left)
                for (var rightTerm : right)
                    result.add(operator == '*' ? product(leftTerm, rightTerm) : quotient(leftTerm, rightTerm));
            stack.push(result);
        }

        private Kind product(Kind left, Kind right) {
            if (left == Kind.SCALAR) return right;
            if (right == Kind.SCALAR) return left;
            if (left == Kind.RATE && right == Kind.QUANTITY || right == Kind.RATE && left == Kind.QUANTITY)
                return Kind.CHARGE;
            if (protectedTerm(left) || protectedTerm(right)) throw incompatible();
            return Kind.OTHER;
        }

        private Kind quotient(Kind numerator, Kind denominator) {
            if (denominator == Kind.SCALAR) return numerator;
            if (protectedTerm(numerator) || protectedTerm(denominator)) throw incompatible();
            return Kind.OTHER;
        }

        void finish() {
            if (stack.pop().contains(Kind.RATE)) throw incompatible();
        }

        private static boolean protectedTerm(Kind kind) { return kind == Kind.RATE || kind == Kind.CHARGE; }

        private Problem incompatible() {
            return new Problem(422, "Pair the road bore rate with " + quantity +
                " using multiplication. Only numbers or crew_size can scale that charge.");
        }
    }
}
