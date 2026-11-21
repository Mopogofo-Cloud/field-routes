package fieldops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;

final class PayrollWorkbook {
    private PayrollWorkbook() {}

    static void write(OutputStream stream, ObjectNode run) throws IOException {
        ExcelWorkbook.write(stream,
            new ExcelWorkbook.Sheet("Summary", rows -> WeeklyPayrollViews.exportRows(run, false, rows::write)),
            new ExcelWorkbook.Sheet("Pay detail", rows -> WeeklyPayrollViews.exportRows(run, true, rows::write)));
    }
}
