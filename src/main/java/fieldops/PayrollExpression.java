package fieldops;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Decimal arithmetic for entity payroll formulas. Validation never evaluates an expression. */
final class PayrollExpression {
    static final int MAX_LENGTH = 2000;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_DECIMAL_SIZE = 1000;
    private static final MathContext PRECISION = MathContext.DECIMAL128;

    private final String source;
    private final Map<String, BigDecimal> inputs;
    private final List<RoadBoreRules.TermCheck> checks;
    private final Set<String> names = new LinkedHashSet<>();
    private int offset;

    private PayrollExpression(String source, Map<String, BigDecimal> inputs, Map<String,String> bindings) {
        if (source == null || source.isBlank()) throw new Problem(422, "Enter a payroll formula.");
        if (source.length() > MAX_LENGTH) throw new Problem(422, "A payroll formula can contain up to " + MAX_LENGTH + " characters.");
        this.source = source;
        this.inputs = inputs;
        this.checks = RoadBoreRules.checks(bindings);
    }

    static Set<String> variables(String expression) {
        var parser = new PayrollExpression(expression, null, Map.of());
        parser.parse();
        return Collections.unmodifiableSet(parser.names);
    }

    static void validate(String expression, Set<String> allowedVariables) {
        validate(expression, allowedVariables, Map.of());
    }

    static void validate(String expression, Set<String> allowedVariables, Map<String,String> bindings) {
        var parser = new PayrollExpression(expression, null, bindings);
        parser.parse();
        for (String name : parser.names) {
            if (!allowedVariables.contains(name))
                throw new Problem(422, "Unknown payroll variable '" + name + "'. Choose an input or rate available for this entity.");
        }
    }

    static void validateRateQuantities(String expression, Map<String,String> bindings) {
        if (!bindings.isEmpty()) new PayrollExpression(expression, null, bindings).parse();
    }

    static BigDecimal evaluate(String expression, Map<String, BigDecimal> variables) {
        return new PayrollExpression(expression, variables == null ? Map.of() : variables, Map.of()).parse();
    }

    private BigDecimal parse() {
        BigDecimal value = sum(0);
        next();
        if (offset != source.length()) throw error("Expected an arithmetic operator or the end of the formula");
        for (var check : checks) check.finish();
        return value;
    }

    private BigDecimal sum(int depth) {
        BigDecimal value = product(depth);
        for (char op = next(); op == '+' || op == '-'; op = next()) {
            offset++;
            value = combine(op, value, product(depth));
        }
        return value;
    }

    private BigDecimal product(int depth) {
        BigDecimal value = signedOperand(depth);
        for (char op = next(); op == '*' || op == '/'; op = next()) {
            offset++;
            value = combine(op, value, signedOperand(depth));
        }
        return value;
    }

    private BigDecimal signedOperand(int depth) {
        boolean negative = false;
        for (char sign = next(); sign == '+' || sign == '-'; sign = next()) {
            if (sign == '-') negative = !negative;
            offset++;
        }
        char start = next();
        BigDecimal value;
        if (start == '(') {
            if (depth >= MAX_DEPTH) throw error("Use at most " + MAX_DEPTH + " levels of parentheses");
            offset++;
            value = sum(depth + 1);
            if (next() != ')') throw error("Expected a closing parenthesis");
            offset++;
        } else if (digit(start) || start == '.') {
            value = number();
        } else if (letter(start)) {
            value = variable();
        } else {
            throw error("Expected a number, lowercase variable name, or opening parenthesis");
        }
        return negative ? value.negate() : value;
    }

    private BigDecimal number() {
        int start = offset;
        while (offset < source.length() && digit(source.charAt(offset))) offset++;
        if (offset < source.length() && source.charAt(offset) == '.') {
            offset++;
            while (offset < source.length() && digit(source.charAt(offset))) offset++;
        }
        String literal = source.substring(start, offset);
        if (literal.equals(".")) throw error("Expected digits in the decimal number");
        for (var check : checks) check.constant();
        return bounded(new BigDecimal(literal)).round(PRECISION);
    }

    private BigDecimal variable() {
        int start = offset++;
        while (offset < source.length()) {
            char c = source.charAt(offset);
            if (!(letter(c) || digit(c) || c == '_')) break;
            offset++;
        }
        String name = source.substring(start, offset);
        if (name.length() > 64) throw error("Variable names can contain up to 64 characters");
        names.add(name);
        for (var check : checks) check.variable(name);
        if (inputs == null) return BigDecimal.ZERO;
        BigDecimal value = inputs.get(name);
        if (value == null) throw new Problem(422, "A value is required for '" + name + "'.");
        return bounded(value).round(PRECISION);
    }

    private BigDecimal combine(char operator, BigDecimal left, BigDecimal right) {
        for (var check : checks) check.combine(operator);
        if (inputs == null) return BigDecimal.ZERO;
        return bounded(switch (operator) {
            case '+' -> left.add(right, PRECISION);
            case '-' -> left.subtract(right, PRECISION);
            case '*' -> left.multiply(right, PRECISION);
            case '/' -> {
                if (right.signum() == 0) throw new Problem(422, "This payroll formula divides by zero for these values.");
                yield left.divide(right, PRECISION);
            }
            default -> throw new IllegalStateException("Unexpected arithmetic operator.");
        });
    }

    // Bound extreme input scales before arithmetic or formatting a result as a plain decimal.
    private static BigDecimal bounded(BigDecimal value) {
        if (value.precision() > MAX_DECIMAL_SIZE || Math.abs((long) value.scale()) > MAX_DECIMAL_SIZE)
            throw new Problem(422, "A payroll number is too large or has too many decimal places.");
        return value;
    }

    private char next() {
        while (offset < source.length() && Character.isWhitespace(source.charAt(offset))) offset++;
        return offset == source.length() ? '\0' : source.charAt(offset);
    }

    private static boolean digit(char c) { return c >= '0' && c <= '9'; }
    private static boolean letter(char c) { return c >= 'a' && c <= 'z'; }
    private Problem error(String message) { return new Problem(422, message + " at character " + (offset + 1) + "."); }
}
