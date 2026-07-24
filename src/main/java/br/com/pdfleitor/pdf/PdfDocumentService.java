package br.com.pdfleitor.pdf;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationHighlight;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.printing.PDFPageable;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

public class PdfDocumentService implements AutoCloseable {
    private static final DateTimeFormatter SIGNATURE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss xxx");
    private static final String DELVIND_MARK_PREFIX = "Delvind PDF destaque";
    private PDDocument document;
    private PDFRenderer renderer;
    private Path currentPath;
    private boolean dirty;
    private final Deque<byte[]> undoStack = new ArrayDeque<>();

    public void open(Path path) throws IOException {
        close();
        currentPath = path;
        document = Loader.loadPDF(Files.readAllBytes(path));
        renderer = new PDFRenderer(document);
        dirty = false;
        undoStack.clear();
    }

    public boolean isOpen() {
        return document != null;
    }

    public Path currentPath() {
        return currentPath;
    }

    public int pageCount() {
        return document.getNumberOfPages();
    }

    public boolean isDirty() {
        return dirty;
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean printWithNativeDialog() throws PrinterException {
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setPageable(new PDFPageable(document));
        if (!job.printDialog()) {
            return false;
        }
        job.print();
        return true;
    }

    public void pushUndoState() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.save(output);
        undoStack.push(output.toByteArray());
        while (undoStack.size() > 20) {
            undoStack.removeLast();
        }
    }

    public void undo() throws IOException {
        if (undoStack.isEmpty()) {
            return;
        }
        byte[] previous = undoStack.pop();
        Path path = currentPath;
        document.close();
        document = Loader.loadPDF(previous);
        renderer = new PDFRenderer(document);
        currentPath = path;
        dirty = true;
    }

    public RenderedPage render(int pageIndex, double zoom) throws IOException {
        float dpi = (float) (96 * zoom);
        BufferedImage bufferedImage = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
        return new RenderedPage(SwingFXUtils.toFXImage(bufferedImage, null), pageIndex, zoom);
    }

    public int findText(String query, int startPage) {
        if (!isOpen()) {
            return -1;
        }
        String normalizedQuery = query.toLowerCase();
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int offset = 0; offset < pageCount(); offset++) {
                int page = (startPage + offset) % pageCount();
                stripper.setStartPage(page + 1);
                stripper.setEndPage(page + 1);
                String text = stripper.getText(document).toLowerCase();
                if (text.contains(normalizedQuery)) {
                    return page;
                }
            }
        } catch (IOException ignored) {
            return -1;
        }
        return -1;
    }

    public List<SearchHit> findTextHits(String query, int startPage) {
        if (!isOpen() || query == null || query.isBlank()) {
            return List.of();
        }
        String normalizedQuery = query.toLowerCase();
        List<SearchHit> hits = new ArrayList<>();
        try {
            for (int offset = 0; offset < pageCount(); offset++) {
                int page = (startPage + offset) % pageCount();
                List<WordBox> pageHits = extractWordBoxes(page).stream()
                        .filter(WordBox::isHorizontal)
                        .filter(word -> word.text().toLowerCase().contains(normalizedQuery))
                        .toList();
                for (WordBox word : pageHits) {
                    hits.add(new SearchHit(page, word.rect()));
                }
            }
        } catch (IOException ignored) {
            return List.of();
        }
        return hits;
    }

    public void addHighlight(int pageIndex, double startX, double startY, double endX, double endY, double zoom, Color color) throws IOException {
        PDPage page = document.getPage(pageIndex);
        PDRectangle mediaBox = page.getMediaBox();

        double scale = 96 * zoom / 72.0;
        float x = (float) (Math.min(startX, endX) / scale);
        float width = (float) (Math.abs(endX - startX) / scale);
        float height = (float) (Math.abs(endY - startY) / scale);
        float y = (float) (mediaBox.getHeight() - (Math.max(startY, endY) / scale));

        addHighlightAnnotation(page, mediaBox, x, mediaBox.getHeight() - y - height, width, height, color);
        dirty = true;
    }

    public int addWordHighlights(int pageIndex, double startX, double startY, double endX, double endY, double zoom, Color color) throws IOException {
        PDPage page = document.getPage(pageIndex);
        PDRectangle mediaBox = page.getMediaBox();
        double scale = 96 * zoom / 72.0;
        Rectangle2D selection = new Rectangle2D.Double(
                Math.min(startX, endX) / scale,
                Math.min(startY, endY) / scale,
                Math.abs(endX - startX) / scale,
                Math.abs(endY - startY) / scale
        );
        List<WordBox> words = extractWordBoxes(pageIndex).stream()
                .filter(word -> word.isHorizontal())
                .filter(word -> selection.contains(word.centerX(), word.centerY()))
                .toList();
        if (words.isEmpty()) {
            return 0;
        }

        for (Rectangle2D.Float box : mergeAdjacentWords(words)) {
            float padX = Math.max(0.7f, box.height * 0.08f);
            float padY = Math.max(1.2f, box.height * 0.22f);
            float x = Math.max(0, box.x - padX);
            float y = Math.max(0, box.y - padY);
            float width = box.width + (padX * 2);
            float height = box.height + (padY * 2);
            addHighlightAnnotation(page, mediaBox, x, y, width, height, color);
        }
        dirty = true;
        return words.size();
    }

    public boolean removeDelvindMarkAt(int pageIndex, double viewX, double viewY, double zoom) throws IOException {
        PDPage page = document.getPage(pageIndex);
        PDRectangle mediaBox = page.getMediaBox();
        double scale = 96 * zoom / 72.0;
        float pdfX = (float) (viewX / scale);
        float pdfY = (float) (mediaBox.getHeight() - (viewY / scale));
        List<PDAnnotation> annotations = page.getAnnotations();
        for (int index = annotations.size() - 1; index >= 0; index--) {
            PDAnnotation annotation = annotations.get(index);
            if (isDelvindMark(annotation) && contains(annotation.getRectangle(), pdfX, pdfY)) {
                annotations.remove(index);
                dirty = true;
                return true;
            }
        }
        return removeLegacyDelvindHighlightAt(page, pdfX, pdfY);
    }

    private void addHighlightAnnotation(PDPage page, PDRectangle mediaBox, float x, float topY, float width, float height, Color color) throws IOException {
        float bottom = Math.max(0, mediaBox.getHeight() - topY - height);
        float top = Math.min(mediaBox.getHeight(), bottom + height);
        float left = Math.max(0, x);
        float right = Math.min(mediaBox.getWidth(), x + width);
        PDRectangle rectangle = new PDRectangle(left, bottom, Math.max(1, right - left), Math.max(1, top - bottom));

        PDAnnotationTextMarkup annotation = new PDAnnotationHighlight();
        annotation.setRectangle(rectangle);
        annotation.setQuadPoints(new float[]{
                left, top,
                right, top,
                left, bottom,
                right, bottom
        });
        annotation.setColor(new PDColor(new float[]{
                (float) color.getRed(),
                (float) color.getGreen(),
                (float) color.getBlue()
        }, PDDeviceRGB.INSTANCE));
        annotation.setConstantOpacity(textHighlightAlpha(color));
        annotation.setContents(DELVIND_MARK_PREFIX);
        annotation.setAnnotationName(DELVIND_MARK_PREFIX + " " + System.nanoTime());
        page.getAnnotations().add(annotation);
    }

    private boolean isDelvindMark(PDAnnotation annotation) {
        String name = annotation.getAnnotationName();
        String contents = annotation.getContents();
        return (name != null && name.startsWith(DELVIND_MARK_PREFIX))
                || (contents != null && contents.startsWith(DELVIND_MARK_PREFIX));
    }

    private boolean contains(PDRectangle rectangle, float x, float y) {
        if (rectangle == null) {
            return false;
        }
        return x >= rectangle.getLowerLeftX()
                && x <= rectangle.getUpperRightX()
                && y >= rectangle.getLowerLeftY()
                && y <= rectangle.getUpperRightY();
    }

    private boolean removeLegacyDelvindHighlightAt(PDPage page, float pdfX, float pdfY) throws IOException {
        COSBase contents = page.getCOSObject().getDictionaryObject(COSName.CONTENTS);
        if (contents instanceof COSArray array) {
            for (int index = array.size() - 1; index >= 0; index--) {
                COSBase item = array.getObject(index);
                if (item instanceof COSStream stream && isLegacyDelvindHighlightStream(new PDStream(stream), pdfX, pdfY)) {
                    array.remove(index);
                    dirty = true;
                    return true;
                }
            }
            return false;
        }
        if (contents instanceof COSStream stream && isLegacyDelvindHighlightStream(new PDStream(stream), pdfX, pdfY)) {
            page.getCOSObject().removeItem(COSName.CONTENTS);
            dirty = true;
            return true;
        }
        return false;
    }

    private boolean isLegacyDelvindHighlightStream(PDStream stream, float pdfX, float pdfY) throws IOException {
        List<Object> tokens = new PDFStreamParser(stream.toByteArray()).parse();
        List<Rectangle2D.Float> rectangles = new ArrayList<>();
        boolean hasGraphicsState = false;
        boolean hasFill = false;
        boolean hasColor = false;
        for (int index = 0; index < tokens.size(); index++) {
            Object token = tokens.get(index);
            if (!(token instanceof Operator operator)) {
                continue;
            }
            String name = operator.getName();
            if ("gs".equals(name)) {
                hasGraphicsState = true;
            } else if ("rg".equals(name)) {
                hasColor = true;
            } else if ("re".equals(name)) {
                Optional<Rectangle2D.Float> rectangle = rectangleBefore(tokens, index);
                if (rectangle.isPresent()) {
                    rectangles.add(rectangle.get());
                } else {
                    return false;
                }
            } else if ("f".equals(name) || "F".equals(name) || "f*".equals(name)) {
                hasFill = true;
            } else if (!isAllowedLegacyHighlightOperator(name)) {
                return false;
            }
        }
        if (!hasGraphicsState || !hasColor || !hasFill || rectangles.isEmpty()) {
            return false;
        }
        return rectangles.stream().anyMatch(rectangle -> rectangle.contains(pdfX, pdfY));
    }

    private boolean isAllowedLegacyHighlightOperator(String name) {
        return "q".equals(name)
                || "Q".equals(name)
                || "gs".equals(name)
                || "rg".equals(name)
                || "re".equals(name)
                || "f".equals(name)
                || "F".equals(name)
                || "f*".equals(name);
    }

    private Optional<Rectangle2D.Float> rectangleBefore(List<Object> tokens, int operatorIndex) {
        if (operatorIndex < 4
                || !(tokens.get(operatorIndex - 4) instanceof COSNumber x)
                || !(tokens.get(operatorIndex - 3) instanceof COSNumber y)
                || !(tokens.get(operatorIndex - 2) instanceof COSNumber width)
                || !(tokens.get(operatorIndex - 1) instanceof COSNumber height)) {
            return Optional.empty();
        }
        return Optional.of(new Rectangle2D.Float(x.floatValue(), y.floatValue(), width.floatValue(), height.floatValue()));
    }

    private List<Rectangle2D.Float> mergeAdjacentWords(List<WordBox> words) {
        List<Rectangle2D.Float> boxes = words.stream()
                .map(WordBox::rect)
                .sorted(Comparator
                        .comparingDouble((Rectangle2D.Float box) -> box.y)
                        .thenComparingDouble(box -> box.x))
                .toList();
        List<Rectangle2D.Float> merged = new ArrayList<>();
        for (Rectangle2D.Float box : boxes) {
            if (merged.isEmpty()) {
                merged.add(copyBox(box));
                continue;
            }
            Rectangle2D.Float current = merged.getLast();
            float currentCenterY = current.y + (current.height / 2f);
            float nextCenterY = box.y + (box.height / 2f);
            float lineTolerance = Math.max(current.height, box.height) * 0.48f;
            float gap = box.x - (current.x + current.width);
            float gapTolerance = Math.max(4.0f, Math.max(current.height, box.height) * 0.70f);
            if (Math.abs(currentCenterY - nextCenterY) <= lineTolerance && gap <= gapTolerance) {
                unionInto(current, box);
            } else {
                merged.add(copyBox(box));
            }
        }
        return merged;
    }

    private Rectangle2D.Float copyBox(Rectangle2D.Float box) {
        return new Rectangle2D.Float(box.x, box.y, box.width, box.height);
    }

    private void unionInto(Rectangle2D.Float target, Rectangle2D.Float source) {
        float minX = Math.min(target.x, source.x);
        float minY = Math.min(target.y, source.y);
        float maxX = Math.max(target.x + target.width, source.x + source.width);
        float maxY = Math.max(target.y + target.height, source.y + source.height);
        target.x = minX;
        target.y = minY;
        target.width = maxX - minX;
        target.height = maxY - minY;
    }

    private float highlightAlpha(Color color) {
        return Math.max(0.16f, Math.min(0.38f, (float) color.getOpacity()));
    }

    private float textHighlightAlpha(Color color) {
        return Math.max(0.14f, Math.min(0.34f, (float) color.getOpacity()));
    }

    public String extractTextInArea(int pageIndex, double startX, double startY, double endX, double endY, double zoom) throws IOException {
        double scale = 96 * zoom / 72.0;
        Rectangle2D selection = new Rectangle2D.Double(
                Math.min(startX, endX) / scale,
                Math.min(startY, endY) / scale,
                Math.abs(endX - startX) / scale,
                Math.abs(endY - startY) / scale
        );
        AreaTextStripper stripper = new AreaTextStripper(selection);
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        stripper.getText(document);
        return stripper.selectedText();
    }

    public Optional<EditableText> findEditableTextAt(int pageIndex, double viewX, double viewY, double zoom) throws IOException {
        double scale = 96 * zoom / 72.0;
        double pdfX = viewX / scale;
        double pdfY = viewY / scale;
        return editableTextLines(pageIndex).stream()
                .filter(line -> expanded(line.rect(), Math.max(3.0, line.rect().height * 0.35)).contains(pdfX, pdfY))
                .min(Comparator.comparingDouble(line -> distance(
                        line.rect().x + (line.rect().width / 2f),
                        line.rect().y + (line.rect().height / 2f),
                        pdfX,
                        pdfY
                )));
    }

    public List<EditableText> editableTextLines(int pageIndex) throws IOException {
        List<WordBox> words = extractWordBoxes(pageIndex).stream()
                .filter(WordBox::isHorizontal)
                .sorted(Comparator
                        .comparingDouble((WordBox word) -> word.rect().y)
                        .thenComparingDouble(word -> word.rect().x))
                .toList();
        List<List<WordBox>> grouped = new ArrayList<>();
        for (WordBox word : words) {
            Optional<List<WordBox>> line = grouped.stream()
                    .filter(existing -> sameTextLine(existing.getFirst(), word))
                    .findFirst();
            if (line.isPresent()) {
                line.get().add(word);
            } else {
                List<WordBox> newLine = new ArrayList<>();
                newLine.add(word);
                grouped.add(newLine);
            }
        }
        List<EditableText> lines = new ArrayList<>();
        for (List<WordBox> lineWords : grouped) {
            lineWords.sort(Comparator.comparingDouble(word -> word.rect().x));
            Rectangle2D.Float rect = copyBox(lineWords.getFirst().rect());
            StringBuilder text = new StringBuilder();
            for (WordBox word : lineWords) {
                unionInto(rect, word.rect());
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(word.text());
            }
            float padX = Math.max(1.5f, rect.height * 0.12f);
            float padY = Math.max(1.2f, rect.height * 0.18f);
            rect.x = Math.max(0, rect.x - padX);
            rect.y = Math.max(0, rect.y - padY);
            rect.width += padX * 2;
            rect.height += padY * 2;
            lines.add(new EditableText(pageIndex, rect, text.toString()));
        }
        lines.sort(Comparator.comparingDouble((EditableText line) -> line.rect().y).thenComparingDouble(line -> line.rect().x));
        return lines;
    }

    private boolean sameTextLine(WordBox a, WordBox b) {
        float tolerance = Math.max(Math.max(a.rect().height, b.rect().height) * 0.62f, 3.5f);
        return Math.abs(a.centerY() - b.centerY()) <= tolerance;
    }

    private Rectangle2D expanded(Rectangle2D.Float rect, double padding) {
        return new Rectangle2D.Double(rect.x - padding, rect.y - padding, rect.width + padding * 2, rect.height + padding * 2);
    }

    private double distance(double ax, double ay, double bx, double by) {
        double dx = ax - bx;
        double dy = ay - by;
        return Math.sqrt((dx * dx) + (dy * dy));
    }

    public void replaceTextInArea(int pageIndex, double startX, double startY, double endX, double endY, double zoom, String newText) throws IOException {
        PDPage page = document.getPage(pageIndex);
        PDRectangle mediaBox = page.getMediaBox();
        double scale = 96 * zoom / 72.0;
        float x = (float) (Math.min(startX, endX) / scale);
        float width = (float) (Math.abs(endX - startX) / scale);
        float height = (float) (Math.abs(endY - startY) / scale);
        float y = (float) (mediaBox.getHeight() - (Math.max(startY, endY) / scale));
        PDFont font = editableFont();
        float padding = Math.max(1.5f, Math.min(4f, height * 0.08f));
        float availableWidth = Math.max(8f, width - (padding * 2));
        float fontSize = bestEditFontSize(pageIndex, startX, startY, endX, endY, zoom, height);
        float lineGap = Math.max(1.2f, fontSize * 0.25f);

        List<String> lines = wrapEditableText(safeEditableText(newText), font, fontSize, availableWidth);
        int maxLines = Math.max(1, (int) ((height - padding * 2) / Math.max(1f, fontSize + lineGap)));
        if (lines.size() > maxLines) {
            lines = new ArrayList<>(lines.subList(0, maxLines));
            lines.set(lines.size() - 1, ellipsize(lines.getLast(), font, fontSize, availableWidth));
        }

        try (PDPageContentStream stream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            stream.setNonStrokingColor(1f, 1f, 1f);
            stream.addRect(x, y, width, height);
            stream.fill();
            stream.setNonStrokingColor(0f, 0f, 0f);
            float cursorY = y + height - padding - fontSize;
            for (String line : lines) {
                if (line.isBlank()) {
                    cursorY -= fontSize + lineGap;
                    continue;
                }
                if (cursorY < y + padding) {
                    break;
                }
                stream.beginText();
                stream.setFont(font, fontSize);
                stream.newLineAtOffset(x + padding, cursorY);
                stream.showText(line);
                stream.endText();
                cursorY -= fontSize + lineGap;
            }
        }
        dirty = true;
    }

    private PDFont editableFont() throws IOException {
        String windir = System.getenv("WINDIR");
        if (windir != null) {
            Path arial = Path.of(windir, "Fonts", "arial.ttf");
            if (Files.isRegularFile(arial)) {
                return PDType0Font.load(document, arial.toFile());
            }
        }
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    private float bestEditFontSize(int pageIndex, double startX, double startY, double endX, double endY, double zoom, float fallbackHeight) throws IOException {
        double scale = 96 * zoom / 72.0;
        Rectangle2D selection = new Rectangle2D.Double(
                Math.min(startX, endX) / scale,
                Math.min(startY, endY) / scale,
                Math.abs(endX - startX) / scale,
                Math.abs(endY - startY) / scale
        );
        List<WordBox> words = extractWordBoxes(pageIndex).stream()
                .filter(WordBox::isHorizontal)
                .filter(word -> selection.contains(word.centerX(), word.centerY()))
                .toList();
        if (words.isEmpty()) {
            return Math.max(6f, Math.min(12f, fallbackHeight * 0.32f));
        }
        double average = words.stream().mapToDouble(word -> word.rect().height).average().orElse(fallbackHeight * 0.32f);
        return Math.max(5.5f, Math.min(16f, (float) (average * 0.92)));
    }

    private List<String> wrapEditableText(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String rawLine : text.split("\\R", -1)) {
            if (rawLine.isBlank()) {
                lines.add("");
            } else {
                lines.addAll(wrapText(rawLine, font, fontSize, maxWidth));
            }
        }
        return lines;
    }

    private String safeEditableText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\p{Cntrl}&&[^\n\t]]", "");
    }

    private List<WordBox> extractWordBoxes(int pageIndex) throws IOException {
        WordBoxStripper stripper = new WordBoxStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        stripper.getText(document);
        return stripper.wordBoxes();
    }

    public void addOverlayImage(int pageIndex, WritableImage overlay, double zoom) throws IOException {
        PDPage page = document.getPage(pageIndex);
        PDRectangle mediaBox = page.getMediaBox();
        BufferedImage bufferedImage = SwingFXUtils.fromFXImage(overlay, null);
        PDImageXObject image = LosslessFactory.createFromImage(document, bufferedImage);
        try (PDPageContentStream stream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            stream.drawImage(image, 0, 0, mediaBox.getWidth(), mediaBox.getHeight());
        }
        dirty = true;
    }

    public void addSignatureMark(int pageIndex, double startX, double startY, double endX, double endY, double zoom, String signerName) throws IOException {
        PDPage page = document.getPage(pageIndex);
        PDRectangle mediaBox = page.getMediaBox();
        double scale = 96 * zoom / 72.0;
        float x = (float) (Math.min(startX, endX) / scale);
        float width = (float) (Math.abs(endX - startX) / scale);
        float height = (float) (Math.abs(endY - startY) / scale);
        float y = (float) (mediaBox.getHeight() - (Math.max(startY, endY) / scale));

        try (PDPageContentStream stream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            drawFittedSignatureText(stream, x, y, width, height, safePdfText(signerName));
        }
        dirty = true;
    }

    private void drawFittedSignatureText(PDPageContentStream stream, float x, float y, float width, float height, String signerName) throws IOException {
        PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        SignatureCertificateText certificateText = signatureCertificateText(signerName);
        List<String> signatureLines = certificateText.lines();
        float padding = Math.max(3f, Math.min(7f, width * 0.05f));
        float textX = x + padding;
        float availableWidth = Math.max(18f, x + width - textX - padding);
        float bodySize = Math.max(5.0f, Math.min(8.5f, height * 0.15f));

        List<String> signerLines = wrapSignatureLines(signatureLines, regular, bodySize, availableWidth);
        float lineGap = Math.max(2f, bodySize * 0.35f);
        float totalHeight = signerLines.size() * (bodySize + lineGap);
        while (totalHeight > height - (padding * 2) && bodySize > 5.0f) {
            bodySize -= 0.5f;
            signerLines = wrapSignatureLines(signatureLines, regular, bodySize, availableWidth);
            lineGap = Math.max(1.5f, bodySize * 0.32f);
            totalHeight = signerLines.size() * (bodySize + lineGap);
        }

        int maxLines = Math.max(1, (int) ((height - padding * 2) / Math.max(1f, bodySize + lineGap)));
        if (signerLines.size() > maxLines) {
            signerLines = new ArrayList<>(signerLines.subList(0, maxLines));
            String last = signerLines.getLast();
            signerLines.set(signerLines.size() - 1, ellipsize(last, regular, bodySize, availableWidth));
        }

        float cursorY = y + height - padding - bodySize;
        stream.setNonStrokingColor(0.04f, 0.04f, 0.04f);
        for (String line : signerLines) {
            if (cursorY < y + padding) {
                break;
            }
            stream.beginText();
            stream.setFont(regular, bodySize);
            stream.newLineAtOffset(textX, cursorY);
            stream.showText(line);
            stream.endText();
            cursorY -= bodySize + lineGap;
        }
    }

    private List<String> wrapSignatureLines(List<String> lines, PDFont font, float fontSize, float maxWidth) throws IOException {
        List<String> wrapped = new ArrayList<>();
        for (String line : lines) {
            wrapped.addAll(wrapText(line, font, fontSize, maxWidth));
        }
        return wrapped;
    }

    private List<String> wrapText(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (word.isBlank()) {
                continue;
            }
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (textWidth(candidate, font, fontSize) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
            } else {
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                lines.addAll(splitLongWord(word, font, fontSize, maxWidth));
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines.isEmpty() ? List.of("Certificado digital") : lines;
    }

    private List<String> splitLongWord(String word, PDFont font, float fontSize, float maxWidth) throws IOException {
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            String candidate = part.toString() + word.charAt(i);
            if (textWidth(candidate, font, fontSize) <= maxWidth || part.isEmpty()) {
                part.append(word.charAt(i));
            } else {
                parts.add(part.toString());
                part.setLength(0);
                part.append(word.charAt(i));
            }
        }
        if (!part.isEmpty()) {
            parts.add(part.toString());
        }
        return parts;
    }

    private String ellipsize(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
        if (textWidth(text, font, fontSize) <= maxWidth) {
            return text;
        }
        String value = text;
        while (value.length() > 1 && textWidth(value + "...", font, fontSize) > maxWidth) {
            value = value.substring(0, value.length() - 1);
        }
        return value + "...";
    }

    private float textWidth(String text, PDFont font, float fontSize) throws IOException {
        return font.getStringWidth(text) / 1000f * fontSize;
    }

    private String safePdfText(String value) {
        if (value == null || value.isBlank()) {
            return "Certificado digital";
        }
        return value.replaceAll("[^\\p{ASCII}]", "").replace("(", "").replace(")", "");
    }

    private SignatureCertificateText signatureCertificateText(String subject) {
        String safe = safePdfText(subject);
        List<String> ous = new ArrayList<>();
        String cn = "";
        String organization = "";
        String country = "";
        for (String part : safe.split(",")) {
            String trimmed = part.trim();
            if (trimmed.regionMatches(true, 0, "CN=", 0, 3)) {
                cn = trimmed.substring(3).trim();
            } else if (trimmed.regionMatches(true, 0, "OU=", 0, 3)) {
                ous.add(trimmed.substring(3).trim());
            } else if (trimmed.regionMatches(true, 0, "O=", 0, 2)) {
                organization = trimmed.substring(2).trim();
            } else if (trimmed.regionMatches(true, 0, "C=", 0, 2)) {
                country = trimmed.substring(2).trim();
            }
        }

        String name = cn.isBlank() ? safe : cn;
        String document = "";
        int separator = name.lastIndexOf(':');
        if (separator > 0 && separator < name.length() - 1) {
            document = onlyDigits(name.substring(separator + 1));
            name = name.substring(0, separator).trim();
        }
        String certificateType = firstContaining(ous, "RFB e-");
        String issuerHint = firstContaining(ous, "Secretaria da Receita Federal");
        if (issuerHint.isBlank()) {
            issuerHint = organization;
        }

        List<String> lines = new ArrayList<>();
        lines.add("Assinado digitalmente");
        lines.add("Assinado por: " + name);
        if (!document.isBlank()) {
            lines.add((document.length() > 11 ? "CNPJ: " : "CPF: ") + document);
        }
        if (!certificateType.isBlank()) {
            lines.add("Certificado: " + certificateType);
        }
        if (!issuerHint.isBlank()) {
            lines.add("Autoridade: " + issuerHint);
        }
        if (!country.isBlank()) {
            lines.add("Pais: " + country);
        }
        lines.add("Dados: " + SIGNATURE_DATE_FORMAT.format(OffsetDateTime.now()));
        return new SignatureCertificateText(name, lines);
    }

    private String firstContaining(List<String> values, String text) {
        for (String value : values) {
            if (value.toLowerCase().contains(text.toLowerCase())) {
                return value;
            }
        }
        return "";
    }

    private String onlyDigits(String value) {
        return value.replaceAll("\\D", "");
    }

    private record SignatureCertificateText(String name, List<String> lines) {
    }

    public void saveAs(Path output) throws IOException {
        saveReplacing(output);
        currentPath = output;
        dirty = false;
    }

    public void saveCopy(Path output) throws IOException {
        saveReplacing(output);
    }

    private void saveReplacing(Path output) throws IOException {
        Path absoluteOutput = output.toAbsolutePath();
        Path parent = absoluteOutput.getParent();
        Path temp = Files.createTempFile(parent, "delvind-save-", ".pdf");
        try {
            document.save(temp.toFile());
            Files.move(temp, absoluteOutput, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public void close() throws IOException {
        if (document != null) {
            document.close();
            document = null;
            renderer = null;
            currentPath = null;
            dirty = false;
        }
    }

    public record SearchHit(int pageIndex, Rectangle2D.Float rect) {
    }

    public record EditableText(int pageIndex, Rectangle2D.Float rect, String text) {
    }

    private record WordBox(Rectangle2D.Float rect, float direction, String text) {
        float centerX() {
            return rect.x + (rect.width / 2f);
        }

        float centerY() {
            return rect.y + (rect.height / 2f);
        }

        boolean isHorizontal() {
            return Math.abs(direction) < 1.0f || Math.abs(direction - 360f) < 1.0f;
        }
    }

    private static class WordBoxStripper extends PDFTextStripper {
        private final List<WordBox> wordBoxes = new ArrayList<>();

        WordBoxStripper() throws IOException {
        }

        List<WordBox> wordBoxes() {
            return wordBoxes;
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            List<TextPosition> currentWord = new ArrayList<>();
            TextPosition previous = null;
            for (TextPosition position : textPositions) {
                String unicode = position.getUnicode();
                boolean gap = previous != null
                        && position.getXDirAdj() - (previous.getXDirAdj() + previous.getWidthDirAdj()) > Math.max(1.5f, previous.getWidthOfSpace() * 0.45f);
                if (unicode == null || unicode.isBlank() || gap) {
                    flushWord(currentWord);
                    previous = unicode == null || unicode.isBlank() ? null : position;
                    if (unicode != null && !unicode.isBlank()) {
                        currentWord.add(position);
                    }
                    continue;
                }
                currentWord.add(position);
                previous = position;
            }
            flushWord(currentWord);
        }

        private void flushWord(List<TextPosition> currentWord) {
            if (currentWord.isEmpty()) {
                return;
            }
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxX = 0;
            float maxY = 0;
            float direction = currentWord.getFirst().getDir();
            for (TextPosition position : currentWord) {
                float x = position.getXDirAdj();
                float y = position.getYDirAdj() - position.getHeightDir();
                float width = position.getWidthDirAdj();
                float height = Math.max(position.getHeightDir(), position.getFontSizeInPt());
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x + width);
                maxY = Math.max(maxY, y + height);
            }
            if (maxX > minX && maxY > minY) {
                StringBuilder text = new StringBuilder();
                for (TextPosition position : currentWord) {
                    text.append(position.getUnicode());
                }
                wordBoxes.add(new WordBox(new Rectangle2D.Float(minX, minY, maxX - minX, maxY - minY), direction, text.toString()));
            }
            currentWord.clear();
        }
    }

    private static class AreaTextStripper extends PDFTextStripper {
        private final Rectangle2D selection;
        private final StringBuilder selectedText = new StringBuilder();
        private boolean wroteTextOnLine;

        AreaTextStripper(Rectangle2D selection) throws IOException {
            this.selection = selection;
        }

        String selectedText() {
            return selectedText.toString().trim();
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            String unicode = text.getUnicode();
            if (unicode == null || unicode.isEmpty()) {
                return;
            }
            Rectangle2D.Float box = new Rectangle2D.Float(
                    text.getXDirAdj(),
                    text.getYDirAdj() - text.getHeightDir(),
                    text.getWidthDirAdj(),
                    text.getHeightDir()
            );
            if (selection.intersects(box)) {
                selectedText.append(unicode);
                wroteTextOnLine = true;
            }
        }

        @Override
        protected void writeWordSeparator() {
            if (wroteTextOnLine && !selectedText.isEmpty() && selectedText.charAt(selectedText.length() - 1) != ' ') {
                selectedText.append(' ');
            }
        }

        @Override
        protected void writeLineSeparator() {
            if (wroteTextOnLine && !selectedText.isEmpty() && selectedText.charAt(selectedText.length() - 1) != '\n') {
                selectedText.append(System.lineSeparator());
            }
            wroteTextOnLine = false;
        }
    }
}
