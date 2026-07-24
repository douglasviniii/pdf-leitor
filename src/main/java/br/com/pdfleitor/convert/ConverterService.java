package br.com.pdfleitor.convert;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConverterService {
    private static final Pattern OFX_TRANSACTION = Pattern.compile("(?is)<STMTTRN>(.*?)(?=<STMTTRN>|</BANKTRANLIST>|</STMTTRN>)");
    private static final Pattern PDF_LINE_DATE = Pattern.compile("^(\\d{2}/\\d{2}/\\d{4})\\s+(.+)$");
    private static final Pattern MONEY_VALUE = Pattern.compile("-?\\d{1,3}(?:\\.\\d{3})*,\\d{2}|-?\\d+,\\d{2}");
    private static final Pattern DOCUMENT_TOKEN = Pattern.compile("[A-Z]{2,}[A-Z0-9_./-]*\\d*|\\d{3,}[A-Z0-9_./-]*");
    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter OFX_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    public Path convert(Path input) throws IOException {
        String name = input.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".ofx")) {
            Path output = sibling(input, "-convertido.pdf");
            ofxToPdf(input, output);
            return output;
        }
        if (name.endsWith(".pdf")) {
            Path output = sibling(input, "-convertido.ofx");
            pdfToOfx(input, output);
            return output;
        }
        throw new IOException("Escolha um arquivo OFX ou PDF.");
    }

    private void ofxToPdf(Path input, Path output) throws IOException {
        String ofx = readText(input);
        List<OfxTransaction> transactions = parseOfxTransactions(ofx);
        String account = firstTag(ofx, "ACCTID");
        String bank = firstTag(ofx, "BANKID");
        String currency = firstTag(ofx, "CURDEF");

        try (PDDocument document = new PDDocument()) {
            PdfWriter writer = new PdfWriter(document);
            writer.line("Extrato OFX convertido");
            writer.line("");
            writer.line("Banco: " + valueOrDash(bank));
            writer.line("Conta: " + valueOrDash(account));
            writer.line("Moeda: " + valueOrDash(currency));
            writer.line("");
            writer.line("Data        Valor          Tipo        Descricao");
            writer.line("------------------------------------------------------------");
            for (OfxTransaction transaction : transactions) {
                writer.line(String.format(Locale.ROOT, "%-11s %-14s %-10s %s",
                        formatOfxDate(transaction.date()),
                        transaction.amount(),
                        transaction.type(),
                        transaction.memo()));
            }
            if (transactions.isEmpty()) {
                writer.line("Nenhum lancamento encontrado no OFX.");
            }
            writer.close();
            document.save(output.toFile());
        }
    }

    private void pdfToOfx(Path input, Path output) throws IOException {
        String text;
        try (PDDocument document = Loader.loadPDF(input.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            text = stripper.getText(document);
        }

        List<OfxTransaction> transactions = new ArrayList<>();
        int index = 1;
        for (String line : text.split("\\R")) {
            PdfTransaction transaction = parsePdfTransactionLine(line);
            if (transaction == null) {
                continue;
            }
            LocalDate date = LocalDate.parse(transaction.date(), BR_DATE);
            String amount = signedAmount(transaction);
            String type = amount.startsWith("-") ? "DEBIT" : "CREDIT";
            transactions.add(new OfxTransaction(
                    date.format(OFX_DATE),
                    amount,
                    type,
                    transaction.memo(),
                    "PDF" + index++,
                    transaction.document()
            ));
        }
        if (transactions.isEmpty()) {
            throw new IOException("Nao encontrei lancamentos no PDF. PDF de banco varia muito; para converter bem, preciso do modelo do extrato desse banco.");
        }

        Files.writeString(output, buildOfx(transactions), Charset.forName("windows-1252"));
    }

    private List<OfxTransaction> parseOfxTransactions(String ofx) {
        List<OfxTransaction> transactions = new ArrayList<>();
        Matcher matcher = OFX_TRANSACTION.matcher(ofx);
        while (matcher.find()) {
            String block = matcher.group(1);
            transactions.add(new OfxTransaction(
                    firstTag(block, "DTPOSTED"),
                    firstTag(block, "TRNAMT"),
                    firstTag(block, "TRNTYPE"),
                    cleanMemo(firstNonBlank(firstTag(block, "MEMO"), firstTag(block, "NAME"))),
                    firstTag(block, "FITID"),
                    firstNonBlank(firstTag(block, "CHECKNUM"), firstTag(block, "REFNUM"))
            ));
        }
        return transactions;
    }

    private String buildOfx(List<OfxTransaction> transactions) {
        transactions = transactions.stream()
                .sorted(Comparator.comparing(OfxTransaction::date))
                .toList();
        String now = LocalDate.now().format(OFX_DATE) + "120000";
        String start = transactions.getFirst().date() + "000000";
        String end = transactions.getLast().date() + "235959";
        String balance = transactions.getLast().amount();
        StringBuilder ofx = new StringBuilder();
        ofx.append("OFXHEADER:100\r\n");
        ofx.append("DATA:OFXSGML\r\n");
        ofx.append("VERSION:102\r\n");
        ofx.append("SECURITY:NONE\r\n");
        ofx.append("ENCODING:USASCII\r\n");
        ofx.append("CHARSET:1252\r\n");
        ofx.append("COMPRESSION:NONE\r\n");
        ofx.append("OLDFILEUID:NONE\r\n");
        ofx.append("NEWFILEUID:NONE\r\n\r\n");
        ofx.append("<OFX>\r\n");
        ofx.append("<SIGNONMSGSRSV1>\r\n");
        ofx.append("<SONRS>\r\n");
        ofx.append("<STATUS>\r\n");
        ofx.append("<CODE>0\r\n");
        ofx.append("<SEVERITY>INFO\r\n");
        ofx.append("</STATUS>\r\n");
        ofx.append("<DTSERVER>").append(now).append("\r\n");
        ofx.append("<LANGUAGE>POR\r\n");
        ofx.append("</SONRS>\r\n");
        ofx.append("</SIGNONMSGSRSV1>\r\n");
        ofx.append("<BANKMSGSRSV1>\r\n");
        ofx.append("<STMTTRNRS>\r\n");
        ofx.append("<TRNUID>1\r\n");
        ofx.append("<STATUS>\r\n");
        ofx.append("<CODE>0\r\n");
        ofx.append("<SEVERITY>INFO\r\n");
        ofx.append("</STATUS>\r\n");
        ofx.append("<STMTRS>\r\n");
        ofx.append("<CURDEF>BRL\r\n");
        ofx.append("<BANKACCTFROM>\r\n");
        ofx.append("<BANKID>000\r\n");
        ofx.append("<ACCTID>000000\r\n");
        ofx.append("<ACCTTYPE>CHECKING\r\n");
        ofx.append("</BANKACCTFROM>\r\n");
        ofx.append("<BANKTRANLIST>\r\n");
        ofx.append("<DTSTART>").append(start).append("\r\n");
        ofx.append("<DTEND>").append(end).append("\r\n");
        for (OfxTransaction transaction : transactions) {
            ofx.append("<STMTTRN>\r\n")
                    .append("<TRNTYPE>").append(transaction.type()).append("\r\n")
                    .append("<DTPOSTED>").append(transaction.date()).append("000000\r\n")
                    .append("<TRNAMT>").append(transaction.amount()).append("\r\n")
                    .append("<FITID>").append(safeFitId(transaction, transactions.indexOf(transaction) + 1)).append("\r\n")
                    .append("<CHECKNUM>").append(escapeOfx(documentNumber(transaction, transactions.indexOf(transaction) + 1), 32)).append("\r\n")
                    .append("<REFNUM>").append(escapeOfx(documentNumber(transaction, transactions.indexOf(transaction) + 1), 32)).append("\r\n")
                    .append("<NAME>").append(escapeOfx(transaction.memo(), 32)).append("\r\n")
                    .append("<MEMO>").append(escapeOfx(transaction.memo(), 255)).append("\r\n")
                    .append("</STMTTRN>\r\n");
        }
        ofx.append("</BANKTRANLIST>\r\n");
        ofx.append("<LEDGERBAL>\r\n");
        ofx.append("<BALAMT>").append(balance).append("\r\n");
        ofx.append("<DTASOF>").append(now).append("\r\n");
        ofx.append("</LEDGERBAL>\r\n");
        ofx.append("</STMTRS>\r\n");
        ofx.append("</STMTTRNRS>\r\n");
        ofx.append("</BANKMSGSRSV1>\r\n");
        ofx.append("</OFX>\r\n");
        return ofx.toString();
    }

    private Path sibling(Path input, String suffix) {
        String fileName = input.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return input.getParent().resolve(base + suffix);
    }

    private PdfTransaction parsePdfTransactionLine(String line) {
        String normalizedLine = line == null ? "" : line.replaceAll("\\s+", " ").trim();
        Matcher lineMatcher = PDF_LINE_DATE.matcher(normalizedLine);
        if (!lineMatcher.find()) {
            return null;
        }

        String date = lineMatcher.group(1);
        String remainder = lineMatcher.group(2).trim();
        List<Matcher> moneyMatches = MONEY_VALUE.matcher(remainder).results()
                .map(result -> {
                    Matcher matcher = MONEY_VALUE.matcher(remainder);
                    matcher.region(result.start(), result.end());
                    matcher.find();
                    return matcher;
                })
                .toList();
        if (moneyMatches.isEmpty()) {
            return null;
        }

        Matcher amountMatch = moneyMatches.size() >= 2 ? moneyMatches.get(moneyMatches.size() - 2) : moneyMatches.getFirst();
        String amount = amountMatch.group();
        String beforeAmount = remainder.substring(0, amountMatch.start()).trim();
        String[] parts = beforeAmount.split("\\s+");
        if (parts.length == 0) {
            return null;
        }

        String document = parts[parts.length - 1];
        if (!DOCUMENT_TOKEN.matcher(document).matches() && parts.length >= 2) {
            document = parts[parts.length - 2] + parts[parts.length - 1];
        }
        String memo = beforeAmount;
        if (memo.endsWith(document)) {
            memo = memo.substring(0, memo.length() - document.length()).trim();
        }
        return new PdfTransaction(date, cleanMemo(memo), cleanDocument(document), amount);
    }

    private String readText(Path input) throws IOException {
        byte[] bytes = Files.readAllBytes(input);
        String utf = new String(bytes, StandardCharsets.UTF_8);
        if (utf.indexOf('\uFFFD') >= 0) {
            return new String(bytes, Charset.forName("windows-1252"));
        }
        return utf;
    }

    private String firstTag(String text, String tag) {
        Matcher matcher = Pattern.compile("(?is)<" + tag + ">([^\\r\\n<]*)").matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String formatOfxDate(String value) {
        if (value == null || value.length() < 8) {
            return valueOrDash(value);
        }
        String digits = value.replaceAll("\\D", "");
        if (digits.length() < 8) {
            return valueOrDash(value);
        }
        return digits.substring(6, 8) + "/" + digits.substring(4, 6) + "/" + digits.substring(0, 4);
    }

    private String normalizeAmount(String amount) {
        return amount.replace(".", "").replace(",", ".");
    }

    private String signedAmount(PdfTransaction transaction) {
        String amount = normalizeAmount(transaction.amount());
        String absolute = amount.startsWith("-") ? amount.substring(1) : amount;
        return isDebit(transaction.memo()) ? "-" + absolute : absolute;
    }

    private boolean isDebit(String memo) {
        String text = normalizeForClassification(memo);
        if (containsAny(text,
                "PIX CRED", "PIX_CRED", "CREDITO", "CRED ", "RECEBIMENTO", "RECEB ", "DEPOSITO",
                "TED RECEB", "DOC RECEB", "TRANSFERENCIA RECEB", "ESTORNO", "RESGATE", "SALDO ANTERIOR")) {
            return false;
        }
        return containsAny(text,
                "PIX DEB", "PIX_DEB", "PAGAMENTO", "PAGTO", "BOLETO", "LIQUIDACAO", "CHEQUE",
                "DEBITO", "DEB ", "COMPRA", "TARIFA", "IOF", "JUROS", "SAQUE", "TRANSFERENCIA",
                "TED ", "DOC ", "CARTAO", "CONTA LUZ", "CONTA AGUA", "IMPOSTO", "DARF", "GPS");
    }

    private String normalizeForClassification(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT)
                .trim();
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String cleanMemo(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[<>]", "")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.isBlank() ? "Lancamento importado" : normalized;
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? valueOrDash(second) : first;
    }

    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String safeFitId(OfxTransaction transaction, int index) {
        String value = !transaction.document().isBlank() ? transaction.document() : transaction.fitId();
        if (value == null || value.isBlank()) {
            value = transaction.date() + "-" + transaction.amount() + "-" + index;
        }
        String clean = value.replaceAll("[^A-Za-z0-9._-]", "");
        return clean.isBlank() ? transaction.date() + "-" + index : clean;
    }

    private String documentNumber(OfxTransaction transaction, int index) {
        String document = cleanDocument(transaction.document());
        return document.isBlank() ? "PDF" + index : document;
    }

    private String cleanDocument(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[^A-Za-z0-9._/-]", "").trim();
    }

    private String escapeOfx(String value, int maxLength) {
        String clean = value == null ? "" : value;
        clean = clean.replace("&", "E")
                .replace("<", "")
                .replace(">", "")
                .replace("\"", "")
                .replace("'", "")
                .replaceAll("[^\\p{ASCII}]", "")
                .trim();
        if (clean.isBlank()) {
            clean = "LANCAMENTO IMPORTADO";
        }
        return clean.length() > maxLength ? clean.substring(0, maxLength) : clean;
    }

    private record PdfTransaction(String date, String memo, String document, String amount) {
    }

    private record OfxTransaction(String date, String amount, String type, String memo, String fitId, String document) {
    }

    private static class PdfWriter {
        private final PDDocument document;
        private PDPage page;
        private PDPageContentStream stream;
        private float y;

        PdfWriter(PDDocument document) throws IOException {
            this.document = document;
            newPage();
        }

        void line(String text) throws IOException {
            if (y < 44) {
                newPage();
            }
            stream.beginText();
            stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 9);
            stream.newLineAtOffset(42, y);
            stream.showText(safePdfText(text));
            stream.endText();
            y -= 14;
        }

        void close() throws IOException {
            if (stream != null) {
                stream.close();
            }
        }

        private void newPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = page.getMediaBox().getHeight() - 42;
        }

        private String safePdfText(String value) {
            return value == null ? "" : value.replaceAll("[^\\p{ASCII}]", "");
        }
    }
}
