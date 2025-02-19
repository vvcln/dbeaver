/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2022 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jkiss.dbeaver.ui.controls.resultset.plaintext;

import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.text.IFindReplaceTarget;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.StyledTextPrintOptions;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.printing.PrintDialog;
import org.eclipse.swt.printing.Printer;
import org.eclipse.swt.printing.PrinterData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.themes.ITheme;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ModelPreferences;
import org.jkiss.dbeaver.model.DBConstants;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDDisplayFormat;
import org.jkiss.dbeaver.model.impl.data.DBDValueError;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.ui.UIStyles;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.StyledTextFindReplaceTarget;
import org.jkiss.dbeaver.ui.controls.resultset.*;
import org.jkiss.dbeaver.ui.editors.TextEditorUtils;
import org.jkiss.utils.CommonUtils;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Empty presentation.
 * Used when RSV has no results (initially).
 */
public class PlainTextPresentation extends AbstractPresentation implements IAdaptable {

    public static final int FIRST_ROW_LINE = 2;

    private StyledText text;
    private DBDAttributeBinding curAttribute;
    private StyledTextFindReplaceTarget findReplaceTarget;
    public boolean activated;
    private Color curLineColor;

    private int[] colWidths;
    private StyleRange curLineRange;
    private int totalRows = 0;
    private String curSelection;
    private Font monoFont;
    private boolean showNulls;
    private boolean rightJustifyNumbers;
    private boolean rightJustifyDateTime;

    @Override
    public void createPresentation(@NotNull final IResultSetController controller, @NotNull Composite parent) {
        super.createPresentation(controller, parent);

        UIUtils.createHorizontalLine(parent);
        text = new StyledText(parent, SWT.READ_ONLY | SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
        text.setBlockSelection(true);
        text.setCursor(parent.getDisplay().getSystemCursor(SWT.CURSOR_IBEAM));
        text.setMargins(4, 4, 4, 4);
        text.setForeground(UIStyles.getDefaultTextForeground());
        text.setBackground(UIStyles.getDefaultTextBackground());
        text.setTabs(controller.getPreferenceStore().getInt(ResultSetPreferences.RESULT_TEXT_TAB_SIZE));
        text.setTabStops(null);
        text.setFont(UIUtils.getMonospaceFont());
        text.setLayoutData(new GridData(GridData.FILL_BOTH));
        text.addCaretListener(event -> onCursorChange(event.caretOffset));
        text.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                curSelection = text.getSelectionText();
                fireSelectionChanged(new PlainTextSelectionImpl());
            }
        });

        final ScrollBar verticalBar = text.getVerticalBar();
        verticalBar.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                if (verticalBar.getSelection() + verticalBar.getPageIncrement() >= verticalBar.getMaximum()) {
                    if (controller.getPreferenceStore().getBoolean(ResultSetPreferences.RESULT_SET_AUTO_FETCH_NEXT_SEGMENT) &&
                        !controller.isRecordMode() &&
                        controller.isHasMoreData()) {
                        controller.readNextSegment();
                    }
                }
            }
        });
        findReplaceTarget = new StyledTextFindReplaceTarget(text);
        TextEditorUtils.enableHostEditorKeyBindingsSupport(controller.getSite(), text);

        applyCurrentThemeSettings();

        registerContextMenu();
        activateTextKeyBindings(controller, text);
        trackPresentationControl();
    }

    @Override
    public void dispose() {
        if (monoFont != null) {
            UIUtils.dispose(monoFont);
            monoFont = null;
        }
        super.dispose();
    }

    @Override
    protected void applyThemeSettings(ITheme currentTheme) {
        curLineColor = currentTheme.getColorRegistry().get(ThemeConstants.COLOR_SQL_RESULT_CELL_ODD_BACK);

        Font rsFont = currentTheme.getFontRegistry().get(ThemeConstants.FONT_SQL_RESULT_SET);
        if (rsFont != null) {
            int fontHeight = rsFont.getFontData()[0].getHeight();
            Font font = UIUtils.getMonospaceFont();

            FontData[] fontData = font.getFontData();
            fontData[0].setHeight(fontHeight);
            Font newFont = new Font(font.getDevice(), fontData[0]);

            this.text.setFont(newFont);

            if (monoFont != null) {
                UIUtils.dispose(monoFont);
            }
            monoFont = newFont;

        }
    }

    private void onCursorChange(int offset) {
        ResultSetModel model = controller.getModel();

        int lineNum = text.getLineAtOffset(offset);
        int lineOffset = text.getOffsetAtLine(lineNum);
        int horizontalOffset = offset - lineOffset;

        int lineCount = text.getLineCount();

        boolean delimLeading = getController().getPreferenceStore().getBoolean(ResultSetPreferences.RESULT_TEXT_DELIMITER_LEADING);

        int rowNum = lineNum - FIRST_ROW_LINE; //First 2 lines is header
        if (controller.isRecordMode()) {
            if (rowNum < 0) {
                rowNum = 0;
            }
            if (rowNum >= 0 && rowNum < model.getVisibleAttributeCount()) {
                curAttribute = model.getVisibleAttribute(rowNum);
            }
        } else {
            int colNum = 0;
            int horOffsetBegin = 0, horOffsetEnd = 0;
            if (delimLeading) horOffsetEnd++;
            for (int i = 0; i < colWidths.length; i++) {
                horOffsetBegin = horOffsetEnd;
                horOffsetEnd += colWidths[i] + 1;
                if (horizontalOffset < horOffsetEnd) {
                    colNum = i;
                    break;
                }
            }
            if (rowNum < 0 && model.getRowCount() > 0) {
                rowNum = 0;
            }
            if (rowNum >= 0 && rowNum < model.getRowCount() && colNum >= 0 && colNum < model.getVisibleAttributeCount()) {
                controller.setCurrentRow(model.getRow(rowNum));
                curAttribute = model.getVisibleAttribute(colNum);
            }
            controller.updateEditControls();

            {
                // Highlight row
                if (curLineRange == null || curLineRange.start != lineOffset + horOffsetBegin) {
                    curLineRange = new StyleRange(
                        lineOffset + horOffsetBegin,
                        horOffsetEnd - horOffsetBegin - 1,
                        null,
                        curLineColor);
                    text.setStyleRanges(new StyleRange[]{curLineRange});
                    text.redraw();
                }
            }

            if (lineNum == lineCount - 1 &&
                controller.isHasMoreData() &&
                controller.getPreferenceStore().getBoolean(ResultSetPreferences.RESULT_SET_AUTO_FETCH_NEXT_SEGMENT)) {
                controller.readNextSegment();
            }
        }
        fireSelectionChanged(new PlainTextSelectionImpl());
    }

    @Override
    public Control getControl() {
        return text;
    }

    @Override
    public void refreshData(boolean refreshMetadata, boolean append, boolean keepState) {
        colWidths = null;

        DBPPreferenceStore prefs = getController().getPreferenceStore();
        rightJustifyNumbers = prefs.getBoolean(ResultSetPreferences.RESULT_SET_RIGHT_JUSTIFY_NUMBERS);
        rightJustifyDateTime = prefs.getBoolean(ResultSetPreferences.RESULT_SET_RIGHT_JUSTIFY_DATETIME);

        if (controller.isRecordMode()) {
            printRecord();
        } else {
            printGrid(append);
        }
    }

    private void printGrid(boolean append) {
        DBPPreferenceStore prefs = getController().getPreferenceStore();
        int maxColumnSize = prefs.getInt(ResultSetPreferences.RESULT_TEXT_MAX_COLUMN_SIZE);
        boolean delimLeading = prefs.getBoolean(ResultSetPreferences.RESULT_TEXT_DELIMITER_LEADING);
        boolean delimTrailing = prefs.getBoolean(ResultSetPreferences.RESULT_TEXT_DELIMITER_TRAILING);
        boolean delimTop = prefs.getBoolean(ResultSetPreferences.RESULT_TEXT_DELIMITER_TOP);
        boolean delimBottom = prefs.getBoolean(ResultSetPreferences.RESULT_TEXT_DELIMITER_BOTTOM);
        boolean extraSpaces = prefs.getBoolean(ResultSetPreferences.RESULT_TEXT_EXTRA_SPACES);
        this.showNulls = getController().getPreferenceStore().getBoolean(ResultSetPreferences.RESULT_TEXT_SHOW_NULLS);

        DBDDisplayFormat displayFormat = DBDDisplayFormat.safeValueOf(prefs.getString(ResultSetPreferences.RESULT_TEXT_VALUE_FORMAT));

        StringBuilder grid = new StringBuilder(512);
        ResultSetModel model = controller.getModel();
        List<DBDAttributeBinding> attrs = model.getVisibleAttributes();

        List<ResultSetRow> allRows = model.getAllRows();
        int extraSpacesNum = extraSpaces ? 2 : 0;
        if (colWidths == null) {
            // Calculate column widths
            colWidths = new int[attrs.size()];

            for (int i = 0; i < attrs.size(); i++) {
                DBDAttributeBinding attr = attrs.get(i);
                colWidths[i] = getAttributeName(attr).length() + extraSpacesNum;
                if (showNulls && !attr.isRequired()) {
                    colWidths[i] = Math.max(colWidths[i], DBConstants.NULL_VALUE_LABEL.length());
                }
                for (ResultSetRow row : allRows) {
                    String displayString = getCellString(model, attr, row, displayFormat);
                    colWidths[i] = Math.max(colWidths[i], getStringWidth(displayString) + extraSpacesNum);
                }
            }
            for (int i = 0; i < colWidths.length; i++) {
                if (colWidths[i] > maxColumnSize) {
                    colWidths[i] = maxColumnSize;
                }
            }
        }

        if (delimTop) {
            // Print divider before header
            printSeparator(delimLeading, delimTrailing, colWidths, grid);
        }
        // Print header
        if (delimLeading) grid.append("|");
        for (int i = 0; i < attrs.size(); i++) {
            if (i > 0) grid.append("|");
            if (extraSpaces) grid.append(" ");
            DBDAttributeBinding attr = attrs.get(i);
            String attrName = getAttributeName(attr);
            grid.append(attrName);
            for (int k = colWidths[i] - attrName.length() - extraSpacesNum; k > 0; k--) {
                grid.append(" ");
            }
            if (extraSpaces) grid.append(" ");
        }
        if (delimTrailing) grid.append("|");
        grid.append("\n");

        // Print divider
        printSeparator(delimLeading, delimTrailing, colWidths, grid);

        // Print rows
        for (ResultSetRow row : allRows) {
            if (delimLeading) grid.append("|");
            for (int k = 0; k < attrs.size(); k++) {
                if (k > 0) grid.append("|");
                DBDAttributeBinding attr = attrs.get(k);
                String displayString = getCellString(model, attr, row, displayFormat);
                if (displayString.length() >= colWidths[k]) {
                    displayString = CommonUtils.truncateString(displayString, colWidths[k]);
                }

                int stringWidth = getStringWidth(displayString);

                if (extraSpaces) grid.append(" ");
                DBPDataKind dataKind = attr.getDataKind();
                if ((dataKind == DBPDataKind.NUMERIC && rightJustifyNumbers) ||
                    (dataKind == DBPDataKind.DATETIME && rightJustifyDateTime))
                {
                    // Right justify value
                    for (int j = colWidths[k] - stringWidth - extraSpacesNum; j > 0; j--) {
                        grid.append(" ");
                    }
                    grid.append(displayString);
                } else {
                    grid.append(displayString);
                    for (int j = colWidths[k] - stringWidth - extraSpacesNum; j > 0; j--) {
                        grid.append(" ");
                    }
                }
                if (extraSpaces) grid.append(" ");
            }
            if (delimTrailing) grid.append("|");
            grid.append("\n");
        }
        if (delimBottom) {
            // Print divider after rows
            printSeparator(delimLeading, delimTrailing, colWidths, grid);
        }
        grid.setLength(grid.length() - 1); // cut last line feed

        final int topIndex = text.getTopIndex();
        final int horizontalIndex = text.getHorizontalIndex();
        final int caretOffset = text.getCaretOffset();

        text.setText(grid.toString());

        if (append) {
            // Restore scroll and caret position
            text.setTopIndex(topIndex);
            text.setHorizontalIndex(horizontalIndex);
            text.setCaretOffset(caretOffset);
        }

        totalRows = allRows.size();
    }

    private int getStringWidth(String str) {
        int width = 0;
        if (str != null && str.length() > 0) {
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);
                if (c == '\t') {
                    width += controller.getPreferenceStore().getInt(ResultSetPreferences.RESULT_TEXT_TAB_SIZE);
                } else {
                    width++;
                }
            }
        }
        return width;
    }

    private static String getAttributeName(DBDAttributeBinding attr) {
        if (CommonUtils.isEmpty(attr.getLabel())) {
            return attr.getName();
        } else {
            return attr.getLabel();
        }
    }

    StringBuilder fixBuffer = new StringBuilder();

    private String getCellString(ResultSetModel model, DBDAttributeBinding attr, ResultSetRow row, DBDDisplayFormat displayFormat) {
        Object cellValue = model.getCellValue(attr, row);
        if (cellValue instanceof DBDValueError) {
            return ((DBDValueError) cellValue).getErrorTitle();
        }
        if (cellValue instanceof Number && controller.getPreferenceStore().getBoolean(ModelPreferences.RESULT_NATIVE_NUMERIC_FORMAT)) {
            displayFormat = DBDDisplayFormat.NATIVE;
        }

        String displayString = attr.getValueHandler().getValueDisplayString(attr, cellValue, displayFormat);

        if (displayString.isEmpty() &&
            showNulls &&
            DBUtils.isNullValue(cellValue))
        {
            displayString = DBConstants.NULL_VALUE_LABEL;
        }

        fixBuffer.setLength(0);
        for (int i = 0; i < displayString.length(); i++) {
            char c = displayString.charAt(i);
            switch (c) {
                case '\n':
                    c = CommonUtils.PARAGRAPH_CHAR;
                    break;
                case '\r':
                    continue;
                case 0:
                case 255:
                case '\t':
                    c = ' ';
                    break;
            }
            if (c < ' '/* || (c > 127 && c < 255)*/) {
                c = ' ';
            }
            fixBuffer.append(c);
        }

        return fixBuffer.toString();
    }

    private void printRecord() {
        DBPPreferenceStore prefs = getController().getPreferenceStore();
        boolean delimLeading = prefs.getBoolean(ResultSetPreferences.RESULT_TEXT_DELIMITER_LEADING);
        boolean delimTrailing = prefs.getBoolean(ResultSetPreferences.RESULT_TEXT_DELIMITER_TRAILING);
        boolean delimTop = prefs.getBoolean(ResultSetPreferences.RESULT_TEXT_DELIMITER_TOP);
        boolean delimBottom = prefs.getBoolean(ResultSetPreferences.RESULT_TEXT_DELIMITER_BOTTOM);
        DBDDisplayFormat displayFormat = DBDDisplayFormat.safeValueOf(prefs.getString(ResultSetPreferences.RESULT_TEXT_VALUE_FORMAT));
        boolean extraSpaces = prefs.getBoolean(ResultSetPreferences.RESULT_TEXT_EXTRA_SPACES);
        String indent = extraSpaces ? " " : "";

        StringBuilder grid = new StringBuilder(512);
        ResultSetModel model = controller.getModel();
        List<DBDAttributeBinding> attrs = model.getVisibleAttributes();
        String[] values = new String[attrs.size()];
        ResultSetRow currentRow = controller.getCurrentRow();

        // Calculate column widths
        int nameWidth = 4, valueWidth = 5;
        for (int i = 0; i < attrs.size(); i++) {
            DBDAttributeBinding attr = attrs.get(i);
            nameWidth = Math.max(nameWidth, getAttributeName(attr).length());
            if (currentRow != null) {
                String displayString = getCellString(model, attr, currentRow, displayFormat);
                values[i] = displayString;
                valueWidth = Math.max(valueWidth, values[i].length());
            }
        }
        final int extraSpacesNum = extraSpaces ? 2 : 0;
        final int[] colWidths = {nameWidth + extraSpacesNum, valueWidth + extraSpacesNum};

        if (delimTop) {
            // Print divider before header
            printSeparator(delimLeading, delimTrailing, colWidths, grid);
        }

        // Header
        if (delimLeading) grid.append("|");
        grid.append(indent).append("Name");
        for (int j = nameWidth - 4; j > 0; j--) {
            grid.append(" ");
        }
        grid.append(indent).append("|").append(indent).append("Value");
        for (int j = valueWidth - 5; j > 0; j--) {
            grid.append(" ");
        }
        grid.append(indent);
        if (delimTrailing) grid.append("|");
        grid.append("\n");

        // Print divider between header and data
        printSeparator(delimLeading, delimTrailing, colWidths, grid);

        if (currentRow != null) {
            // Values
            for (int i = 0; i < attrs.size(); i++) {
                DBDAttributeBinding attr = attrs.get(i);
                String name = getAttributeName(attr);
                if (delimLeading) grid.append("|");
                grid.append(indent);
                grid.append(name);
                grid.append(indent);
                for (int j = nameWidth - name.length(); j > 0; j--) {
                    grid.append(" ");
                }
                grid.append("|");
                grid.append(indent);
                grid.append(values[i]);
                for (int j = valueWidth - values[i].length(); j > 0; j--) {
                    grid.append(" ");
                }
                grid.append(indent);

                if (delimTrailing) grid.append("|");
                grid.append("\n");
            }
        }
        if (delimBottom) {
            // Print divider after record
            printSeparator(delimLeading, delimTrailing, colWidths, grid);
        }
        grid.setLength(grid.length() - 1); // cut last line feed
        text.setText(grid.toString());
    }

    private void printSeparator(boolean delimLeading, boolean delimTrailing, int[] colWidths, StringBuilder output) {
        if (delimLeading) {
            output.append('+');
        }
        for (int i = 0; i < colWidths.length; i++) {
            if (i > 0) output.append('+');
            for (int k = colWidths[i]; k > 0; k--) {
                output.append('-');
            }
        }
        if (delimTrailing) {
            output.append('+');
        }
        output.append('\n');
    }

    @Override
    public void formatData(boolean refreshData) {
        //controller.refreshData(null);
    }

    @Override
    public void clearMetaData() {
        colWidths = null;
        curLineRange = null;
        totalRows = 0;
    }

    @Override
    public void updateValueView() {

    }

    @Override
    public void fillMenu(@NotNull IMenuManager menu) {

    }

    @Override
    public void changeMode(boolean recordMode) {
        text.setSelection(0);
        text.setBlockSelectionBounds(new Rectangle(0, 0, 0, 0));
        curSelection = null;
    }

    @Override
    public void scrollToRow(@NotNull RowPosition position) {
        if (controller.isRecordMode()) {
            super.scrollToRow(position);
        } else {
            int caretOffset = text.getCaretOffset();
            if (caretOffset < 0) caretOffset = 0;
            int lineNum = text.getLineAtOffset(caretOffset);
            if (lineNum < FIRST_ROW_LINE) {
                lineNum = FIRST_ROW_LINE;
            }
            int lineOffset = text.getOffsetAtLine(lineNum);
            int xOffset = caretOffset - lineOffset;
            int totalLines = text.getLineCount();
            switch (position) {
                case FIRST:
                    lineNum = FIRST_ROW_LINE;
                    break;
                case PREVIOUS:
                    lineNum--;
                    break;
                case NEXT:
                    lineNum++;
                    break;
                case LAST:
                    lineNum = totalLines - 1;
                    break;
                case CURRENT:
                    lineNum = controller.getCurrentRow().getVisualNumber() + FIRST_ROW_LINE;
                    break;
            }
            if (lineNum < FIRST_ROW_LINE || lineNum >= totalLines) {
                return;
            }
            int newOffset = text.getOffsetAtLine(lineNum);
            newOffset += xOffset;
            text.setCaretOffset(newOffset);
            //text.setSelection(newOffset, 0);
            text.showSelection();
        }
    }

    @Nullable
    @Override
    public DBDAttributeBinding getCurrentAttribute() {
        return curAttribute;
    }

    @NotNull
    @Override
    public Map<Transfer, Object> copySelection(ResultSetCopySettings settings) {
        return Collections.singletonMap(
            TextTransfer.getInstance(),
            text.getSelectionText());
    }

    private static PrinterData fgPrinterData= null;

    @Override
    public void printResultSet() {
        final Shell shell = getControl().getShell();
        StyledTextPrintOptions options = new StyledTextPrintOptions();
        options.printTextFontStyle = true;
        options.printTextForeground = true;

        if (Printer.getPrinterList().length == 0) {
            UIUtils.showMessageBox(shell, "No printers", "Printers not found", SWT.ICON_ERROR);
            return;
        }

        final PrintDialog dialog = new PrintDialog(shell, SWT.PRIMARY_MODAL);
        dialog.setPrinterData(fgPrinterData);
        final PrinterData data = dialog.open();

        if (data != null) {
            final Printer printer = new Printer(data);
            final Runnable styledTextPrinter = text.print(printer, options);
            new Thread("Printing") { //$NON-NLS-1$
                public void run() {
                    styledTextPrinter.run();
                    printer.dispose();
                }
            }.start();

			/*
             * FIXME:
			 * 	Should copy the printer data to avoid threading issues,
			 *	but this is currently not possible, see http://bugs.eclipse.org/297957
			 */
            fgPrinterData = data;
            fgPrinterData.startPage = 1;
            fgPrinterData.endPage = 1;
            fgPrinterData.scope = PrinterData.ALL_PAGES;
            fgPrinterData.copyCount = 1;
        }
    }

    @Override
    protected void performHorizontalScroll(int scrollCount) {
        ScrollBar hsb = text.getHorizontalBar();
        if (hsb != null && hsb.isVisible()) {
            int curPosition = text.getHorizontalPixel();
            int pageIncrement = UIUtils.getFontHeight(text.getFont()) * 10;
            if (scrollCount > 0) {
                if (curPosition > 0) {
                    curPosition -= pageIncrement;
                }
            } else {
                curPosition += pageIncrement;
            }
            if (curPosition < 0) curPosition = 0;
            text.setHorizontalPixel(curPosition);
            //text.setHorizontalIndex();
        }
    }

    @Override
    public <T> T getAdapter(Class<T> adapter) {
        if (adapter == IFindReplaceTarget.class) {
            return adapter.cast(findReplaceTarget);
        }
        return null;
    }

    @Override
    public ISelection getSelection() {
        return new PlainTextSelectionImpl();
    }

    private class PlainTextSelectionImpl implements IResultSetSelection {

        @Nullable
        @Override
        public Object getFirstElement()
        {
            return curSelection;
        }

        @Override
        public Iterator<String> iterator()
        {
            return toList().iterator();
        }

        @Override
        public int size()
        {
            return curSelection == null ? 0 : 1;
        }

        @Override
        public Object[] toArray()
        {
            return curSelection == null ?
                new Object[0] :
                new Object[] { curSelection };
        }

        @Override
        public List<String> toList()
        {
            return curSelection == null ?
                Collections.emptyList() :
                Collections.singletonList(curSelection);
        }

        @Override
        public boolean isEmpty()
        {
            return false;
        }

        @NotNull
        @Override
        public IResultSetController getController()
        {
            return controller;
        }

        @NotNull
        @Override
        public List<DBDAttributeBinding> getSelectedAttributes() {
            if (curAttribute == null) {
                return Collections.emptyList();
            }
            return Collections.singletonList(curAttribute);
        }

        @NotNull
        @Override
        public List<ResultSetRow> getSelectedRows()
        {
            ResultSetRow currentRow = controller.getCurrentRow();
            if (currentRow == null) {
                return Collections.emptyList();
            }
            return Collections.singletonList(currentRow);
        }

        @Override
        public DBDAttributeBinding getElementAttribute(Object element) {
            return curAttribute;
        }

        @Override
        public ResultSetRow getElementRow(Object element) {
            return getController().getCurrentRow();
        }
    }

}
