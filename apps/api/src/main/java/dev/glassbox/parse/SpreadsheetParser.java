package dev.glassbox.parse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/** Excel workbooks (.xlsx and legacy .xls): one heading per visible sheet, one row per line, formulas as their values. */
public class SpreadsheetParser implements DocumentParser {
    private static final int MAX_SHEET_ROWS = Tabular.MAX_ROWS + 100;

    @Override
    public ParsedDocument parse(byte[] data) throws IOException {
        TextBuilder tb = new TextBuilder();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            DataFormatter fmt = new DataFormatter();
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                if (wb.isSheetHidden(i) || wb.isSheetVeryHidden(i)) continue;
                Sheet sheet = wb.getSheetAt(i);
                List<List<String>> rows = new ArrayList<>();
                for (Row row : sheet) {
                    if (rows.size() >= MAX_SHEET_ROWS) break;
                    List<String> cells = new ArrayList<>();
                    int last = Math.min(row.getLastCellNum(), Tabular.MAX_COLUMNS);
                    for (int c = 0; c < last; c++) cells.add(text(row.getCell(c), fmt, evaluator));
                    rows.add(cells);
                }
                if (rows.stream().allMatch(r -> r.stream().allMatch(String::isBlank))) continue;
                tb.heading(1, sheet.getSheetName());
                Tabular.append(tb, rows);
            }
        } catch (EncryptedDocumentException e) {
            throw new UnsupportedDocumentException("Password-protected workbooks are not supported");
        }
        return DocumentParser.nonEmpty(tb, "The workbook");
    }

    private static String text(Cell cell, DataFormatter fmt, FormulaEvaluator evaluator) {
        if (cell == null) return "";
        try {
            return fmt.formatCellValue(cell, evaluator);
        } catch (RuntimeException e) { // external links, unsupported functions: fall back to the value Excel last saved
            if (cell.getCellType() == CellType.FORMULA) {
                return switch (cell.getCachedFormulaResultType()) {
                    case STRING -> cell.getStringCellValue();
                    case NUMERIC -> String.valueOf(cell.getNumericCellValue());
                    case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                    default -> "";
                };
            }
            return "";
        }
    }
}
