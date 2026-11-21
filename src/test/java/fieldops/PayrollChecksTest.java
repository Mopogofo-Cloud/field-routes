package fieldops;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PayrollChecksTest {
    @Test void legalLineMatchesExactCentsAndPlaceValues() {
        assertEquals("Zero and 01/100", words("0.01"));
        assertEquals("Twenty-one and 00/100", words("21.00"));
        assertEquals("One thousand nine and 05/100", words("1009.05"));
        assertEquals("One million one hundred forty thousand three hundred six and 80/100", words("1140306.800"));
        assertEquals("Nine hundred ninety-nine million nine hundred ninety-nine thousand nine hundred ninety-nine and 99/100",
            words("999999999.99"));
    }

    @Test void unsupportedAmountsCannotBeRoundedIntoPrintableChecks() {
        assertThrows(Problem.class, () -> words("0.001"));
        assertThrows(Problem.class, () -> words("-0.01"));
        assertThrows(Problem.class, () -> words("1000000000.00"));
    }

    private static String words(String amount) { return PayrollChecks.amountWords(new BigDecimal(amount)); }
}
