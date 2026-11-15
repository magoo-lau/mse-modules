package vip.mseapp.ai.kb.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表格数据读取助手：Excel（POI，多 sheet）与 CSV（带引号转义的简单解析）统一读成行列矩阵。
 *
 * <p>POI 经 spring-ai-tika-document-reader 传递带入（tika-parsers-standard-package），
 * 无需显式声明版本。CSV 假定 UTF-8（容忍 BOM），逗号分隔，支持双引号包裹与转义。
 */
public final class TabularReader {

    private TabularReader() {
    }

    /** 文件类型是否可按表格读取 */
    public static boolean supports(String fileName) {
        String ext = extOf(fileName);
        return ext.equals("xlsx") || ext.equals("xls") || ext.equals("csv");
    }

    /**
     * 读取全部行（含表头行）。空行剔除；Excel 各 sheet 依次拼接。
     * 非表格文件返回 null（调用方回落文本解析路径）。
     */
    public static List<List<String>> read(org.springframework.core.io.Resource resource, String fileName) {
        String ext = extOf(fileName);
        try (var in = resource.getInputStream()) {
            if (ext.equals("csv")) {
                return readCsv(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
            if (ext.equals("xlsx") || ext.equals("xls")) {
                return readExcel(in);
            }
            return null;
        } catch (java.io.IOException e) {
            throw new vip.mseapp.core.exception.ServiceException(400, "表格文件读取失败：" + e.getMessage());
        }
    }

    private static List<List<String>> readExcel(java.io.InputStream in) throws java.io.IOException {
        List<List<String>> rows = new ArrayList<>();
        try (org.apache.poi.ss.usermodel.Workbook workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(in)) {
            org.apache.poi.ss.usermodel.DataFormatter formatter = new org.apache.poi.ss.usermodel.DataFormatter();
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(s);
                for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
                    org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                    if (row == null) {
                        continue;
                    }
                    List<String> cells = new ArrayList<>();
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        org.apache.poi.ss.usermodel.Cell cell = row.getCell(c, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        cells.add(cell == null ? "" : formatter.formatCellValue(cell).strip());
                    }
                    if (cells.stream().anyMatch(v -> !v.isEmpty())) {
                        rows.add(cells);
                    }
                }
            }
        }
        return rows;
    }

    /** CSV 简单解析：逗号分隔、双引号包裹、" "" " 转义 */
    static List<List<String>> readCsv(String content) {
        List<List<String>> rows = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return rows;
        }
        String normalized = content.startsWith("\uFEFF") ? content.substring(1) : content;
        List<String> field = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        boolean hasCell = false;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < normalized.length() && normalized.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
                hasCell = true;
            } else if (c == ',') {
                field.add(cell.toString());
                cell.setLength(0);
                hasCell = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < normalized.length() && normalized.charAt(i + 1) == '\n') {
                    i++;
                }
                if (hasCell || !cell.isEmpty()) {
                    field.add(cell.toString());
                    rows.add(field);
                    field = new ArrayList<>();
                    cell.setLength(0);
                    hasCell = false;
                }
            } else {
                cell.append(c);
                hasCell = true;
            }
        }
        if (hasCell || !cell.isEmpty()) {
            field.add(cell.toString());
            rows.add(field);
        }
        return rows.stream().map(row -> row.stream().map(String::strip).toList())
                .filter(row -> row.stream().anyMatch(v -> !v.isEmpty()))
                .toList();
    }

    static String extOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }

    /** 问答对（qa 切片用） */
    public record QaPair(String question, String answer) {

        public String render() {
            return "问：" + question() + "\n答：" + answer();
        }
    }

    /** 从表格矩阵提取问答对：第 1 列为问题、第 2 列为答案（表头行自动跳过） */
    public static List<QaPair> readQaPairs(List<List<String>> rows) {
        List<QaPair> pairs = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return pairs;
        }
        int start = looksLikeQaHeader(rows.get(0)) ? 1 : 0;
        for (int i = start; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.size() < 2 || row.get(0).isEmpty() || row.get(1).isEmpty()) {
                continue;
            }
            pairs.add(new QaPair(row.get(0), row.get(1)));
        }
        return pairs;
    }

    private static final Pattern QA_HEADER = Pattern.compile("(?i)^(问|答|问题|答案|question|answer|q|a)\\s*$");

    private static boolean looksLikeQaHeader(List<String> row) {
        return row.size() >= 2 && QA_HEADER.matcher(row.get(0)).matches()
                && QA_HEADER.matcher(row.get(1)).matches();
    }

    /** 从文本行提取问答对（Q:/A:、问：/答： 标记，答案可跨行） */
    public static List<QaPair> parseQaText(String text) {
        Pattern questionMark = Pattern.compile("^\\s*(?:Q|问)[：:.、]\\s*(.*)$");
        Pattern answerMark = Pattern.compile("^\\s*(?:A|答)[：:.、]\\s*(.*)$");
        List<QaPair> pairs = new ArrayList<>();
        String question = null;
        StringBuilder answer = new StringBuilder();
        for (String line : text.replace("\r\n", "\n").split("\n")) {
            Matcher q = questionMark.matcher(line);
            Matcher a = answerMark.matcher(line);
            if (q.matches()) {
                flushQa(pairs, question, answer);
                question = q.group(1).strip();
            } else if (a.matches()) {
                if (question != null) {
                    if (answer.length() > 0) {
                        answer.append('\n');
                    }
                    answer.append(a.group(1).strip());
                }
                // 无问题的孤儿答句忽略
            } else if (question != null && answer.length() > 0 && !line.isBlank()) {
                answer.append('\n').append(line.strip());
            }
        }
        flushQa(pairs, question, answer);
        return pairs;
    }

    private static void flushQa(List<QaPair> pairs, String question, StringBuilder answer) {
        if (question != null && !question.isEmpty() && answer.length() > 0) {
            pairs.add(new QaPair(question, answer.toString().strip()));
        }
        answer.setLength(0);
    }
}
