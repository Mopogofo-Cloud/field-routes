package fieldops;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PayrollExpressionTest {
    @Test void precedenceUnarySignsAndDecimalArithmetic() {
        Map<String, BigDecimal> job = Map.of("buried_ft", new BigDecimal("17.5"), "unit_rate", new BigDecimal("1.40"));
        assertValue("24.50", PayrollExpression.evaluate("buried_ft * unit_rate", job));
        assertValue("-26", PayrollExpression.evaluate("-(buried_ft * unit_rate + .5) - +1.", job));
        assertValue("8", PayrollExpression.evaluate("--( 20 / 5 / 2 ) * (7 - 2 - 1)", Map.of()));
        assertValue("1.5", PayrollExpression.evaluate("1 - -.50", Map.of()));
        assertValue("0.3", PayrollExpression.evaluate(".1 + .2", Map.of()));
        assertEquals(BigDecimal.ONE.divide(new BigDecimal("3"), MathContext.DECIMAL128),
            PayrollExpression.evaluate("1/3", Map.of()));
    }

    @Test void referencesAreOrderedAndDeduplicatedAndLimitedToTheEntity() {
        String expression = "unit_rate * buried_ft + unit_rate / crew_count";
        var names = PayrollExpression.variables(expression);
        assertEquals(List.of("unit_rate", "buried_ft", "crew_count"), new ArrayList<>(names));
        assertThrows(UnsupportedOperationException.class, () -> names.add("unknown"));
        assertDoesNotThrow(() -> PayrollExpression.validate(expression, Set.copyOf(names)));
        Problem disallowed = failure(() -> PayrollExpression.validate(expression, Set.of("crew_count", "buried_ft")));
        assertTrue(disallowed.getMessage().contains("unit_rate"));
        assertTrue(PayrollExpression.variables("20 * .5").isEmpty());
    }

    @Test void structuralValidationAllowsDivisorsAndPreviewExplainsMissingOrZeroInputs() {
        String ratio = "total / (crew - 1)";
        assertDoesNotThrow(() -> PayrollExpression.validate(ratio, Set.of("total", "crew")));
        assertDoesNotThrow(() -> PayrollExpression.validate("1/0", Set.of()));
        assertValue("5", PayrollExpression.evaluate(ratio, Map.of("total", BigDecimal.TEN, "crew", BigDecimal.valueOf(3))));
        Problem zero = failure(() -> PayrollExpression.evaluate(ratio, Map.of("total", BigDecimal.TEN, "crew", BigDecimal.ONE)));
        assertTrue(zero.getMessage().contains("zero"));
        var missing = new HashMap<String, BigDecimal>();
        missing.put("total", BigDecimal.TEN);
        assertTrue(failure(() -> PayrollExpression.evaluate(ratio, missing)).getMessage().contains("crew"));
        missing.put("crew", null);
        assertTrue(failure(() -> PayrollExpression.evaluate(ratio, missing)).getMessage().contains("crew"));
    }

    @Test void rejectsIncompleteExpressionsAndUnsupportedSyntax() {
        for (String formula : List.of("", " ", ".", "2.5.3", "3e5", "(1+", "(1+3", "1+3)", "()",
                "2(3+4)", "a b", "_x", "A", "a.b", "a[0]", "abs(x)", "x = 1", "1;2", "2 ** 3", "4 // 3", "x +\0")) {
            assertEquals(422, assertThrows(Problem.class, () -> PayrollExpression.variables(formula), formula).status);
        }
        assertTrue(failure(() -> PayrollExpression.variables("42 + * 5")).getMessage().contains("character 6"));
        assertThrows(Problem.class, () -> PayrollExpression.variables(null));
    }

    @Test void longOrDeepExpressionsAndExtremePreviewValuesFailCleanly() {
        assertValue("1", PayrollExpression.evaluate("(".repeat(32) + "1" + ")".repeat(32), Map.of()));
        failure(() -> PayrollExpression.variables("(".repeat(33) + "1" + ")".repeat(33)));
        failure(() -> PayrollExpression.variables("x".repeat(65)));
        assertDoesNotThrow(() -> PayrollExpression.variables("a".repeat(64)));
        assertValue("1000", PayrollExpression.evaluate("1+".repeat(999) + "1", Map.of()));
        failure(() -> PayrollExpression.variables("1+".repeat(1000) + "1"));
        for (int scale : List.of(Integer.MIN_VALUE, Integer.MAX_VALUE)) {
            failure(() -> PayrollExpression.evaluate("x * x", Map.of("x", new BigDecimal(BigInteger.ONE, scale))));
        }
        failure(() -> PayrollExpression.evaluate("x*x", Map.of("x", new BigDecimal("1E+650"))));
    }

    private static void assertValue(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), actual.toString());
    }

    private static Problem failure(Runnable task) {
        var problem = assertThrows(Problem.class, task::run);
        assertEquals(422, problem.status);
        return problem;
    }
}
