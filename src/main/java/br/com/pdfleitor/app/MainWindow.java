package br.com.pdfleitor.app;

import br.com.pdfleitor.convert.ConverterService;
import br.com.pdfleitor.pdf.PdfDocumentService;
import br.com.pdfleitor.pdf.RenderedPage;
import br.com.pdfleitor.sign.CertificateStore;
import br.com.pdfleitor.sign.CertificateStore.CertificateStatus;
import br.com.pdfleitor.sign.CertificateStore.SavedCertificate;
import br.com.pdfleitor.sign.SignatureService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Parent;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainWindow {
    private static final int MAX_OPEN_TABS = 20;

    private final Stage stage;
    private final BorderPane root = new BorderPane();
    private final ConverterService converterService = new ConverterService();
    private final SignatureService signatureService = new SignatureService();
    private final CertificateStore certificateStore = new CertificateStore();
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pdf-render");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pdf-io");
        thread.setDaemon(true);
        return thread;
    });

    private final TabPane tabPane = new TabPane();
    private final StackPane documentHost = new StackPane();
    private final ImageView emptyLogo = new ImageView(new Image(MainWindow.class.getResourceAsStream("/icons/empty-logo.png")));
    private final Label emptyTitle = new Label("Delvind PDF");
    private final Label developerCredit = new Label("Desenvolvido por DOUGLAS");
    private final VBox emptyContent = new VBox(14, emptyLogo, emptyTitle, developerCredit);
    private final StackPane emptyState = new StackPane(emptyContent);
    private final ProgressIndicator editSavingIndicator = new ProgressIndicator();
    private final Label editSavingLabel = new Label("Salvando edicao...");
    private final VBox editSavingContent = new VBox(10, editSavingIndicator, editSavingLabel);
    private final StackPane editSavingOverlay = new StackPane(editSavingContent);
    private final StackPane workspace = new StackPane(documentHost, emptyState, editSavingOverlay);
    private final Label status = new Label("Abra um PDF para comecar");
    private final Label pageInfo = new Label("0 / 0");
    private final Button saveButton = new Button("Salvar");
    private final TextField pageField = new TextField();
    private final TextField searchField = new TextField();
    private final ToggleGroup toolGroup = new ToggleGroup();
    private final ColorPicker colorPicker = new ColorPicker(Color.rgb(255, 214, 80, 0.32));
    private final ContextMenu selectionMenu = new ContextMenu();
    private final Map<TextInputControl, InlineTextEdit> textEditControls = new HashMap<>();
    private String selectedText = "";
    private PendingSignature pendingSignature;

    private double dragStartX;
    private double dragStartY;

    public MainWindow(Stage stage) {
        this.stage = stage;
        root.getStyleClass().add("app-root");
        root.setFocusTraversable(true);
        tabPane.getStyleClass().add("top-tabs");
        VBox top = new VBox(tabHeader(), topBar());
        root.setTop(top);
        root.setLeft(sideMenu());
        root.setCenter(center());
        root.setBottom(bottomBar());
        configureDragAndDrop();
        configureKeyboardShortcuts();
        configureSearchBehavior();
        configureToolBehavior();
        MenuItem copyItem = new MenuItem("Copiar");
        copyItem.setOnAction(event -> copyStoredSelection());
        selectionMenu.getItems().add(copyItem);
        tabPane.getSelectionModel().selectedItemProperty().addListener((observable, oldTab, newTab) -> {
            commitTextEditOverlays();
            updateEmptyState();
            updatePageLabels();
            DocumentSession session = activeSession();
            if (session != null) {
                documentHost.getChildren().setAll(session.scrollPane);
                stage.setTitle("Delvind PDF - " + session.path.getFileName());
                refreshTextEditMode();
            }
        });
        stage.setOnCloseRequest(event -> {
            if (!requestClose()) {
                event.consume();
            }
        });
    }

    public Parent root() {
        return root;
    }

    public void bringToFront() {
        bringWindowToFront();
    }

    private void configureKeyboardShortcuts() {
        root.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!event.isControlDown() && event.getCode() == KeyCode.DOWN && canNavigateSearch()) {
                moveSearchHit(1);
                event.consume();
                return;
            }
            if (!event.isControlDown() && event.getCode() == KeyCode.UP && canNavigateSearch()) {
                moveSearchHit(-1);
                event.consume();
                return;
            }
            if (!event.isControlDown()) {
                return;
            }
            KeyCode code = event.getCode();
            if (code == KeyCode.PLUS || code == KeyCode.ADD || code == KeyCode.EQUALS) {
                changeZoom(0.1);
                event.consume();
            } else if (code == KeyCode.MINUS || code == KeyCode.SUBTRACT || code == KeyCode.UNDERSCORE) {
                changeZoom(-0.1);
                event.consume();
            }
        });
    }

    private void configureSearchBehavior() {
        searchField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == null || newValue.isBlank()) {
                clearSearchHighlights();
            }
        });
    }

    private void configureToolBehavior() {
        toolGroup.selectedToggleProperty().addListener((observable, oldToggle, newToggle) -> {
            if (oldToggle != null && "edit-text".equals(oldToggle.getUserData())) {
                commitTextEditOverlays();
                clearTextEditOverlays();
            }
            updateSaveButtonMode();
            refreshTextEditMode();
        });
    }

    private void configureSaveButton() {
        saveButton.setMaxWidth(Double.MAX_VALUE);
        saveButton.setOnAction(event -> handleSaveButton());
        updateSaveButtonMode();
    }

    private void updateSaveButtonMode() {
        boolean editing = "edit-text".equals(currentTool());
        saveButton.setText(editing ? "Salvar edicao" : "Salvar");
        saveButton.setGraphic(null);
        saveButton.setDisable(false);
    }

    private void handleSaveButton() {
        if ("edit-text".equals(currentTool())) {
            saveTextEdition();
        } else {
            saveAs();
        }
    }

    private void saveTextEdition() {
        if (activeSession() == null) {
            status.setText("Abra um PDF para editar");
            return;
        }
        saveButton.setDisable(true);
        showEditSaving(true);
        status.setText("Salvando edicao...");
        javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(80));
        delay.setOnFinished(event -> {
            try {
                commitTextEditOverlays();
                refreshTextEditMode();
                status.setText("Edicao aplicada ao PDF");
            } finally {
                showEditSaving(false);
                saveButton.setDisable(false);
            }
        });
        delay.play();
    }

    private void showEditSaving(boolean visible) {
        editSavingOverlay.setVisible(visible);
        editSavingOverlay.setManaged(visible);
        if (visible) {
            editSavingOverlay.toFront();
        }
    }

    private boolean canNavigateSearch() {
        DocumentSession session = activeSession();
        return session != null
                && !session.searchHits.isEmpty()
                && !(root.getScene() != null && root.getScene().getFocusOwner() instanceof TextInputControl)
                && !searchField.isFocused()
                && !pageField.isFocused();
    }

    public void openPdf(Path path) {
        AppLog.write("MainWindow.openPdf requested path=" + path);
        bringWindowToFront();
        if (path == null || !path.toString().toLowerCase().endsWith(".pdf")) {
            AppLog.write("MainWindow.openPdf ignored non-pdf path=" + path);
            status.setText("Arquivo ignorado: use PDF");
            return;
        }
        Optional<Tab> alreadyOpen = tabPane.getTabs().stream()
                .filter(tab -> tab.getUserData() instanceof DocumentSession)
                .filter(tab -> samePath(((DocumentSession) tab.getUserData()).path, path))
                .findFirst();
        if (alreadyOpen.isPresent()) {
            tabPane.getSelectionModel().select(alreadyOpen.get());
            AppLog.write("MainWindow.openPdf selected already open tab path=" + path);
            status.setText("PDF ja esta aberto");
            return;
        }
        if (tabPane.getTabs().size() >= MAX_OPEN_TABS) {
            AppLog.write("MainWindow.openPdf refused tab limit path=" + path);
            status.setText("Limite de 20 PDFs abertos");
            showError("Limite de abas", new IllegalStateException("Voce pode abrir ate 20 PDFs ao mesmo tempo."));
            return;
        }
        DocumentSession session = new DocumentSession(path);
        Tab tab = new Tab(path.getFileName().toString());
        Pane tabContent = new Pane();
        tabContent.setMinHeight(0);
        tabContent.setPrefHeight(0);
        tabContent.setMaxHeight(0);
        tab.setContent(tabContent);
        tab.setUserData(session);
        tab.setOnCloseRequest(event -> {
            tabPane.getSelectionModel().select(tab);
            if (!confirmSaveOrDiscard(session)) {
                event.consume();
                return;
            }
            try {
                session.pdfService.close();
            } catch (IOException ignored) {
                // Closing a tab should not block the UI on cleanup.
            }
        });
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
        stage.setTitle("Delvind PDF - " + path.getFileName());
        updateEmptyState();
        updatePageLabels();
        status.setText("Abrindo PDF...");

        Task<Void> openTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                AppLog.write("PDF background open started path=" + path);
                session.pdfService.open(path);
                AppLog.write("PDF background open finished path=" + path + " pages=" + session.pdfService.pageCount());
                return null;
            }
        };
        openTask.setOnSucceeded(event -> {
            if (!tabPane.getTabs().contains(tab)) {
                try {
                    session.pdfService.close();
                } catch (IOException ignored) {
                    // The tab was closed while loading.
                }
                return;
            }
            tabPane.getSelectionModel().select(tab);
            updatePageLabels();
            buildPagePlaceholders(session);
            renderAllPages(session);
        });
        openTask.setOnFailed(event -> {
            tabPane.getTabs().remove(tab);
            updateEmptyState();
            AppLog.write("PDF background open failed path=" + path, openTask.getException());
            showError("Nao foi possivel abrir o PDF", openTask.getException());
        });
        ioExecutor.submit(openTask);
    }

    private void bringWindowToFront() {
        stage.setIconified(false);
        stage.setMaximized(true);
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    private boolean samePath(Path first, Path second) {
        try {
            return first.toRealPath().equals(second.toRealPath());
        } catch (IOException ex) {
            return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
        }
    }

    private void configureDragAndDrop() {
        root.setOnDragOver(event -> {
            Dragboard dragboard = event.getDragboard();
            if (dragboard.hasFiles() && dragboard.getFiles().stream().anyMatch(file -> file.getName().toLowerCase().endsWith(".pdf"))) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        root.setOnDragDropped(event -> {
            Dragboard dragboard = event.getDragboard();
            boolean success = false;
            if (dragboard.hasFiles()) {
                dragboard.getFiles().stream()
                        .filter(file -> file.getName().toLowerCase().endsWith(".pdf"))
                        .limit(Math.max(0, MAX_OPEN_TABS - tabPane.getTabs().size()))
                        .forEach(file -> openPdf(file.toPath()));
                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    private Parent topBar() {
        Button open = button("Abrir novo", this::choosePdf);
        configureSaveButton();
        Button undo = button("Desfazer", this::undoLastChange);
        Button print = button("Imprimir", this::printPdf);
        Button previous = button("<", () -> movePage(-1));
        Button next = button(">", () -> movePage(1));
        Button zoomOut = button("-", () -> changeZoom(-0.1));
        Button zoomIn = button("+", () -> changeZoom(0.1));

        pageField.setPrefWidth(56);
        pageField.setAlignment(Pos.CENTER);
        pageField.setPromptText("Pag.");
        pageField.setOnAction(event -> goToTypedPage());

        searchField.setPromptText("Pesquisar texto");
        searchField.setPrefWidth(280);
        searchField.setOnAction(event -> {
            searchText();
            event.consume();
        });
        Button previousSearch = button("˄", () -> moveSearchHit(-1));
        Button nextSearch = button("˅", () -> moveSearchHit(1));
        Button search = button("Buscar", this::searchText);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(8,
                open, saveButton, undo,
                separator(),
                previous, pageField, pageInfo, next,
                separator(),
                zoomOut, zoomIn,
                spacer,
                print,
                searchField, previousSearch, nextSearch, search
        );
        bar.setPadding(new Insets(10, 12, 10, 12));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("top-bar");
        return bar;
    }

    private Parent tabHeader() {
        ImageView logo = new ImageView(new Image(MainWindow.class.getResourceAsStream("/icons/app-icon.png")));
        logo.setFitWidth(18);
        logo.setFitHeight(18);
        logo.setPreserveRatio(true);

        Label name = new Label("Delvind PDF");
        name.getStyleClass().add("header-title");

        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        tabPane.setMaxHeight(34);
        HBox.setHgrow(tabPane, Priority.ALWAYS);

        HBox header = new HBox(8, logo, name, tabPane);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(3, 8, 0, 8));
        header.getStyleClass().add("tab-header");
        return header;
    }

    private Parent sideMenu() {
        ToggleButton select = tool("Selecionar", "select");
        ToggleButton highlight = tool("Grifar area", "highlight");
        ToggleButton line = tool("Destacar texto", "line");
        ToggleButton pen = tool("Caneta", "pen");
        ToggleButton editText = tool("Editar texto", "edit-text");
        ToggleButton eraser = tool("Borracha", "eraser");
        select.setSelected(true);

        GridPane palette = colorPalette();
        colorPicker.setMaxWidth(Double.MAX_VALUE);
        colorPicker.setPromptText("Cor personalizada");
        VBox colors = new VBox(8, new Label("Cores"), palette, colorPicker);
        colors.getStyleClass().add("side-section");

        Button sign = button("Assinar A1", this::signA1);
        sign.getStyleClass().add("primary-side-button");
        Button converter = button("Conversor", this::startConversion);
        converter.getStyleClass().add("primary-side-button");

        VBox menu = new VBox(12,
                title("Ferramentas"),
                select,
                highlight,
                line,
                pen,
                editText,
                eraser,
                colors,
                sign,
                converter
        );
        menu.setPadding(new Insets(14));
        menu.setPrefWidth(190);
        menu.getStyleClass().add("side-menu");
        return menu;
    }

    private Parent center() {
        emptyLogo.setPreserveRatio(true);
        emptyLogo.setFitWidth(260);
        emptyLogo.setOpacity(0.96);
        emptyContent.setAlignment(Pos.CENTER);
        emptyTitle.getStyleClass().add("empty-title");
        developerCredit.getStyleClass().add("developer-credit");
        emptyState.getStyleClass().add("empty-state");
        editSavingIndicator.setMaxSize(44, 44);
        editSavingContent.setAlignment(Pos.CENTER);
        editSavingContent.getStyleClass().add("edit-saving-box");
        editSavingOverlay.getStyleClass().add("edit-saving-overlay");
        showEditSaving(false);
        documentHost.setVisible(false);
        documentHost.setManaged(false);
        return workspace;
    }

    private Parent bottomBar() {
        HBox box = new HBox(status);
        box.setPadding(new Insets(6, 10, 6, 10));
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("status-bar");
        return box;
    }

    private void buildPagePlaceholders(DocumentSession session) {
        int ticket = session.renderTicket.incrementAndGet();
        session.pageSurfaces.clear();
        session.documentBox.getChildren().clear();

        for (int index = 0; index < session.pdfService.pageCount(); index++) {
            ImageView imageView = new ImageView();
            imageView.setPreserveRatio(true);
            imageView.setSmooth(true);

            // Allocate the large drawing buffer only when this page is rendered.
            Canvas canvas = new Canvas();
            StackPane stack = new StackPane(imageView, canvas);
            stack.getStyleClass().add("page-stack");
            stack.setMinSize(650, 850);
            stack.setPrefSize(650, 850);
            stack.setAlignment(Pos.CENTER);

            Label pageNumber = new Label("Pagina " + (index + 1));
            pageNumber.getStyleClass().add("page-number");

            VBox pageCard = new VBox(8, stack, pageNumber);
            pageCard.setAlignment(Pos.CENTER);
            pageCard.getStyleClass().add("page-card");

            PageSurface surface = new PageSurface(pageCard, stack, imageView, canvas);
            session.pageSurfaces.put(index, surface);
            session.documentBox.getChildren().add(pageCard);
            configureOverlay(session, index, canvas);
        }

        session.renderTicket.set(ticket);
    }

    private void renderAllPages() {
        DocumentSession session = activeSession();
        if (session == null) {
            return;
        }
        renderAllPages(session);
    }

    private void renderAllPages(DocumentSession session) {
        if (!session.pdfService.isOpen()) {
            status.setText("Abrindo PDF...");
            return;
        }
        int ticket = session.renderTicket.incrementAndGet();
        session.renderedPages.clear();
        session.renderingPages.clear();
        status.setText("Carregando paginas...");
        int total = session.pdfService.pageCount();
        if (total <= 0) {
            return;
        }
        int first = Math.max(0, Math.min(total - 1, session.pageIndex));
        renderPage(session, first, ticket);
        renderAroundPage(session, first);
    }

    private void renderPage(DocumentSession session, int page, int ticket) {
        if (page < 0 || !session.pdfService.isOpen() || page >= session.pdfService.pageCount()) {
            return;
        }
        if (!session.renderingPages.add(page)) {
            return;
        }
        double requestedZoom = session.zoom;
        Task<RenderedPage> task = new Task<>() {
            @Override
            protected RenderedPage call() throws Exception {
                if (ticket != session.renderTicket.get()) {
                    return null;
                }
                return session.pdfService.render(page, requestedZoom);
            }
        };
        task.setOnSucceeded(event -> {
            session.renderingPages.remove(page);
            if (ticket != session.renderTicket.get() || task.getValue() == null) {
                return;
            }
            session.renderedPages.add(page);
            applyRenderedPage(session, task.getValue());
        });
        task.setOnFailed(event -> {
            session.renderingPages.remove(page);
            if (ticket == session.renderTicket.get()) {
                showError("Falha ao renderizar a pagina", task.getException());
            }
        });
        renderExecutor.submit(task);
    }

    private void renderPageIfNeeded(DocumentSession session, int page) {
        if (session.renderedPages.contains(page) || session.renderingPages.contains(page)) {
            return;
        }
        renderPage(session, page, session.renderTicket.get());
    }

    private void renderAroundPage(DocumentSession session, int page) {
        if (!session.pdfService.isOpen()) {
            return;
        }
        int total = session.pdfService.pageCount();
        int start = Math.max(0, page - 1);
        int end = Math.min(total - 1, page + 3);
        for (int index = start; index <= end; index++) {
            renderPageIfNeeded(session, index);
        }
    }

    private void applyRenderedPage(DocumentSession session, RenderedPage rendered) {
        PageSurface surface = session.pageSurfaces.get(rendered.pageIndex());
        if (surface == null) {
            return;
        }
        Image image = rendered.image();
        surface.imageView().setImage(image);
        surface.canvas().setWidth(image.getWidth());
        surface.canvas().setHeight(image.getHeight());
        surface.stack().setMinSize(image.getWidth(), image.getHeight());
        surface.stack().setPrefSize(image.getWidth(), image.getHeight());
        surface.stack().setMaxSize(image.getWidth(), image.getHeight());
        clearOverlay(surface.canvas());
        drawSearchHits(session, rendered.pageIndex());
        currentSearchHit(session)
                .filter(hit -> hit.pageIndex() == rendered.pageIndex())
                .ifPresent(hit -> scrollToSearchHit(session, hit));
        status.setText("Paginas carregadas em " + Math.round(session.zoom * 100) + "%");
    }

    private Button button(String text, Runnable action) {
        Button button = new Button(text);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(event -> action.run());
        return button;
    }

    private ToggleButton tool(String text, String value) {
        ToggleButton button = new ToggleButton(text);
        button.setToggleGroup(toolGroup);
        button.setUserData(value);
        button.setMaxWidth(Double.MAX_VALUE);
        return button;
    }

    private Label title(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("side-title");
        return label;
    }

    private Region separator() {
        Region region = new Region();
        region.getStyleClass().add("toolbar-separator");
        region.setPrefWidth(1);
        region.setMinWidth(1);
        region.setMaxWidth(1);
        return region;
    }

    private Optional<Path> clipboardPath() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (!clipboard.hasString()) {
            return Optional.empty();
        }
        String value = clipboard.getString();
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        value = value.trim();
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            Path path = Path.of(value);
            return Files.exists(path) ? Optional.of(path) : Optional.empty();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private void configureInitialDirectoryFromClipboard(FileChooser chooser) {
        clipboardPath()
                .map(path -> Files.isDirectory(path) ? path : path.getParent())
                .filter(path -> path != null && Files.isDirectory(path))
                .ifPresent(path -> chooser.setInitialDirectory(path.toFile()));
    }

    private void configureInitialDirectoryFromSession(FileChooser chooser, DocumentSession session) {
        if (session != null && session.path != null && session.path.getParent() != null && Files.isDirectory(session.path.getParent())) {
            chooser.setInitialDirectory(session.path.getParent().toFile());
            chooser.setInitialFileName(session.path.getFileName().toString());
            return;
        }
        configureInitialDirectoryFromClipboard(chooser);
    }

    private GridPane colorPalette() {
        Color[] colors = {
                Color.rgb(255, 245, 157, 0.32), Color.rgb(255, 224, 130, 0.32), Color.rgb(255, 202, 40, 0.32), Color.rgb(255, 167, 38, 0.30), Color.rgb(255, 112, 67, 0.28), Color.rgb(239, 83, 80, 0.25), Color.rgb(236, 64, 122, 0.24), Color.rgb(171, 71, 188, 0.24),
                Color.rgb(126, 87, 194, 0.25), Color.rgb(92, 107, 192, 0.25), Color.rgb(66, 165, 245, 0.27), Color.rgb(41, 182, 246, 0.27), Color.rgb(38, 198, 218, 0.27), Color.rgb(38, 166, 154, 0.27), Color.rgb(102, 187, 106, 0.27), Color.rgb(156, 204, 101, 0.28),
                Color.rgb(212, 225, 87, 0.30), Color.rgb(255, 238, 88, 0.32), Color.rgb(255, 213, 79, 0.32), Color.rgb(255, 183, 77, 0.30), Color.rgb(255, 138, 101, 0.28), Color.rgb(229, 115, 115, 0.25), Color.rgb(240, 98, 146, 0.24), Color.rgb(186, 104, 200, 0.24),
                Color.rgb(149, 117, 205, 0.25), Color.rgb(121, 134, 203, 0.25), Color.rgb(100, 181, 246, 0.27), Color.rgb(79, 195, 247, 0.27), Color.rgb(77, 208, 225, 0.27), Color.rgb(77, 182, 172, 0.27), Color.rgb(129, 199, 132, 0.27), Color.rgb(174, 213, 129, 0.28),
                Color.rgb(255, 255, 255, 0.30), Color.rgb(238, 238, 238, 0.28), Color.rgb(224, 224, 224, 0.27), Color.rgb(189, 189, 189, 0.24), Color.rgb(158, 158, 158, 0.21), Color.rgb(117, 117, 117, 0.18), Color.rgb(80, 80, 80, 0.16), Color.rgb(45, 45, 45, 0.14),
                Color.rgb(255, 235, 59, 0.34), Color.rgb(255, 193, 7, 0.33), Color.rgb(255, 152, 0, 0.31), Color.rgb(244, 67, 54, 0.26), Color.rgb(233, 30, 99, 0.24), Color.rgb(156, 39, 176, 0.24), Color.rgb(63, 81, 181, 0.24), Color.rgb(33, 150, 243, 0.27),
                Color.rgb(0, 188, 212, 0.27), Color.rgb(0, 150, 136, 0.27), Color.rgb(76, 175, 80, 0.27), Color.rgb(139, 195, 74, 0.28), Color.rgb(205, 220, 57, 0.30), Color.rgb(121, 85, 72, 0.18), Color.rgb(96, 125, 139, 0.18), Color.rgb(0, 0, 0, 0.14)
        };
        GridPane grid = new GridPane();
        grid.setHgap(4);
        grid.setVgap(4);
        for (int i = 0; i < colors.length; i++) {
            grid.add(swatch(colors[i]), i % 8, i / 8);
        }
        return grid;
    }

    private Button swatch(Color color) {
        Button button = new Button();
        button.setMinSize(17, 17);
        button.setPrefSize(17, 17);
        button.setMaxSize(17, 17);
        button.setOnAction(event -> colorPicker.setValue(color));
        button.getStyleClass().add("color-swatch");
        button.setStyle("-fx-background-color: rgba(" + Math.round(color.getRed() * 255) + ", "
                + Math.round(color.getGreen() * 255) + ", "
                + Math.round(color.getBlue() * 255) + ", "
                + color.getOpacity() + ");");
        return button;
    }

    private void choosePdf() {
        Optional<Path> clipboardPath = clipboardPath();
        if (clipboardPath.isPresent()) {
            Path path = clipboardPath.get();
            if (Files.isRegularFile(path) && path.toString().toLowerCase().endsWith(".pdf")) {
                openPdf(path);
                return;
            }
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Abrir PDF");
        configureInitialDirectoryFromClipboard(chooser);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
        Optional.ofNullable(chooser.showOpenDialog(stage)).map(File::toPath).ifPresent(this::openPdf);
    }

    private void saveAs() {
        DocumentSession session = activeSession();
        if (session == null || !session.pdfService.isOpen()) {
            ensureOpen();
            return;
        }
        commitTextEditOverlays();
        saveSessionToChosenPath(session);
    }

    private void printPdf() {
        DocumentSession session = activeSession();
        if (session == null || !session.pdfService.isOpen()) {
            ensureOpen();
            return;
        }
        try {
            commitTextEditOverlays();
            status.setText("Abrindo impressao...");
            boolean printed = session.pdfService.printWithNativeDialog();
            status.setText(printed ? "Impressao enviada" : "Impressao cancelada");
        } catch (Exception ex) {
            showError("Nao foi possivel imprimir o PDF", ex);
        }
    }

    private void movePage(int delta) {
        DocumentSession session = activeSession();
        if (session == null || !session.pdfService.isOpen()) {
            ensureOpen();
            return;
        }
        int next = Math.max(0, Math.min(session.pdfService.pageCount() - 1, session.pageIndex + delta));
        session.pageIndex = next;
        updatePageLabels();
        scrollToPage(session, session.pageIndex);
    }

    private void goToTypedPage() {
        DocumentSession session = activeSession();
        if (session == null || !session.pdfService.isOpen()) {
            ensureOpen();
            return;
        }
        try {
            int requested = Integer.parseInt(pageField.getText().trim()) - 1;
            session.pageIndex = Math.max(0, Math.min(session.pdfService.pageCount() - 1, requested));
            updatePageLabels();
            scrollToPage(session, session.pageIndex);
        } catch (NumberFormatException ignored) {
            updatePageLabels();
        }
    }

    private void changeZoom(double delta) {
        DocumentSession session = activeSession();
        if (session == null || !session.pdfService.isOpen()) {
            ensureOpen();
            return;
        }
        commitTextEditOverlays();
        session.zoom = Math.max(0.35, Math.min(3.0, session.zoom + delta));
        renderAllPages(session);
        Platform.runLater(() -> scrollToPage(session, session.pageIndex));
    }

    private void configureOverlay(DocumentSession session, int page, Canvas canvas) {
        canvas.setCursor(cursorForCurrentTool());
        canvas.setOnMouseMoved(event -> canvas.setCursor(cursorForCurrentTool()));
        canvas.setOnScroll(event -> {
            if (event.isControlDown()) {
                changeZoom(event.getDeltaY() > 0 ? 0.1 : -0.1);
                event.consume();
            }
        });
        canvas.setOnMousePressed(event -> {
            event.consume();
            if (event.getButton() != MouseButton.PRIMARY || !ensureOpen()) {
                return;
            }
            session.pageIndex = page;
            updatePageLabels();
            dragStartX = event.getX();
            dragStartY = event.getY();
            if (pendingSignature != null) {
                clearOverlay(canvas);
                return;
            }
            if ("edit-text".equals(currentTool())) {
                status.setText("Clique diretamente em uma linha de texto para editar");
                return;
            }
            if ("eraser".equals(currentTool())) {
                try {
                    session.pdfService.pushUndoState();
                    boolean removed = session.pdfService.removeDelvindMarkAt(page, event.getX(), event.getY(), session.zoom);
                    if (removed) {
                        renderPage(session, page, session.renderTicket.get());
                        status.setText("Destaque removido");
                    } else {
                        session.pdfService.undo();
                        status.setText("Nenhum destaque removivel neste ponto");
                    }
                } catch (IOException ex) {
                    showError("Nao foi possivel remover o destaque", ex);
                }
                return;
            }
            if ("select".equals(currentTool())) {
                clearOverlay(canvas);
            } else if ("pen".equals(currentTool())) {
                GraphicsContext gc = canvas.getGraphicsContext2D();
                gc.setStroke(opaqueToolColor());
                gc.setLineWidth(2.5);
                gc.beginPath();
                gc.moveTo(event.getX(), event.getY());
            }
        });
        canvas.setOnMouseDragged(event -> {
            event.consume();
            if (pendingSignature != null) {
                clearOverlay(canvas);
                drawSignaturePreview(canvas, event.getX(), event.getY());
                return;
            }
            String tool = currentTool();
            if ("pen".equals(tool)) {
                GraphicsContext gc = canvas.getGraphicsContext2D();
                gc.lineTo(event.getX(), event.getY());
                gc.stroke();
                return;
            }
            if ("select".equals(tool)) {
                clearOverlay(canvas);
                GraphicsContext gc = canvas.getGraphicsContext2D();
                gc.setFill(Color.rgb(66, 133, 244, 0.22));
                gc.setStroke(Color.rgb(66, 133, 244, 0.80));
                gc.setLineWidth(1.0);
                double x = Math.min(dragStartX, event.getX());
                double y = Math.min(dragStartY, event.getY());
                double width = Math.abs(event.getX() - dragStartX);
                double height = Math.abs(event.getY() - dragStartY);
                gc.fillRect(x, y, width, height);
                gc.strokeRect(x, y, width, height);
                return;
            }
            if ("highlight".equals(tool) || "line".equals(tool)) {
                clearOverlay(canvas);
                GraphicsContext gc = canvas.getGraphicsContext2D();
                gc.setFill(colorPicker.getValue());
                double x = Math.min(dragStartX, event.getX());
                double y = Math.min(dragStartY, event.getY());
                double width = Math.abs(event.getX() - dragStartX);
                double height = Math.abs(event.getY() - dragStartY);
                gc.fillRect(x, y, width, height);
            }
        });
        canvas.setOnMouseReleased(event -> {
            event.consume();
            if (!ensureOpen()) {
                return;
            }
            try {
                if (pendingSignature != null) {
                    finishSignature(session, page, event.getX(), event.getY());
                    clearOverlay(canvas);
                    return;
                }
                String tool = currentTool();
                if ("edit-text".equals(tool)) {
                    clearOverlay(canvas);
                    return;
                }
                if (selectionTooSmall(event.getX(), event.getY())) {
                    clearOverlay(canvas);
                    status.setText("Selecione uma area maior");
                    return;
                }
                if ("select".equals(tool)) {
                    selectText(page, event.getX(), event.getY(), event.getScreenX(), event.getScreenY());
                } else if ("highlight".equals(tool)) {
                    session.pdfService.pushUndoState();
                    session.pdfService.addHighlight(page, dragStartX, dragStartY, event.getX(), event.getY(), session.zoom, colorPicker.getValue());
                    renderPage(session, page, session.renderTicket.get());
                } else if ("line".equals(tool)) {
                    session.pdfService.pushUndoState();
                    int words = session.pdfService.addWordHighlights(page, dragStartX, dragStartY, event.getX(), event.getY(), session.zoom, colorPicker.getValue());
                    status.setText(words == 0 ? "Nenhuma palavra selecionada" : "Palavras destacadas: " + words);
                    renderPage(session, page, session.renderTicket.get());
                } else if ("pen".equals(tool)) {
                    session.pdfService.pushUndoState();
                    SnapshotParameters params = new SnapshotParameters();
                    params.setFill(Color.TRANSPARENT);
                    session.pdfService.addOverlayImage(page, canvas.snapshot(params, null), session.zoom);
                    renderPage(session, page, session.renderTicket.get());
                }
            } catch (IOException ex) {
                showError("Nao foi possivel gravar a anotacao", ex);
            }
        });
    }

    private void refreshTextEditMode() {
        clearTextEditOverlays();
        DocumentSession session = activeSession();
        if (session == null || !"edit-text".equals(currentTool())) {
            return;
        }
        try {
            for (int page = 0; page < session.pdfService.pageCount(); page++) {
                PageSurface surface = session.pageSurfaces.get(page);
                if (surface != null) {
                    addTextEditOverlays(session, page, surface);
                }
            }
            status.setText("PDF em modo editavel");
        } catch (IOException ex) {
            showError("Nao foi possivel ativar edicao de texto", ex);
        }
    }

    private void addTextEditOverlays(DocumentSession session, int page, PageSurface surface) throws IOException {
        double scale = 96 * session.zoom / 72.0;
        for (PdfDocumentService.EditableText line : session.pdfService.editableTextLines(page)) {
            double x = line.rect().x * scale;
            double y = Math.max(0, (line.rect().y * scale) - 2);
            double fontSize = Math.max(6, line.rect().height * scale * 0.68);
            double width = Math.max(18, line.rect().width * scale + 10);
            double height = Math.max(18, Math.max(line.rect().height * scale + 10, fontSize * 1.65));
            TextField editor = new TextField(line.text());
            editor.setPrefSize(width, height);
            editor.setMinSize(width, height);
            editor.setMaxSize(width, height);
            editor.setCursor(Cursor.TEXT);
            editor.setStyle(textEditStyle(fontSize, false));
            StackPane.setAlignment(editor, Pos.TOP_LEFT);
            editor.setTranslateX(x);
            editor.setTranslateY(y);
            editor.focusedProperty().addListener((observable, wasFocused, focused) -> {
                editor.setStyle(textEditStyle(fontSize, focused));
                InlineTextEdit edit = textEditControls.get(editor);
                if (!focused && edit != null && !editor.getText().equals(edit.originalText())) {
                    commitTextEditOverlays();
                }
            });
            editor.setOnAction(event -> {
                commitTextEditOverlays();
                event.consume();
            });
            editor.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.ESCAPE) {
                    editor.setText(textEditControls.get(editor).originalText());
                    editor.getParent().requestFocus();
                    event.consume();
                }
            });
            InlineTextEdit edit = new InlineTextEdit(session, page, x, y, x + width, y + height, line.text(), editor);
            textEditControls.put(editor, edit);
            surface.stack().getChildren().add(editor);
        }
    }

    private String textEditStyle(double fontSize, boolean focused) {
        if (focused) {
            return "-fx-font-family: 'Arial'; -fx-font-size: " + fontSize
                    + "px; -fx-text-fill: #111111; -fx-background-color: rgba(255,255,255,0.98); "
                    + "-fx-background-insets: 0; -fx-border-color: #7b4bff; -fx-border-width: 1; "
                    + "-fx-padding: 0 2 0 2;";
        }
        return "-fx-font-family: 'Arial'; -fx-font-size: " + fontSize
                + "px; -fx-text-fill: transparent; -fx-background-color: transparent; "
                + "-fx-background-insets: 0; -fx-border-color: transparent; -fx-padding: 0 2 0 2;";
    }

    private void commitTextEditOverlays() {
        if (textEditControls.isEmpty()) {
            return;
        }
        Map<DocumentSession, Boolean> undoPushed = new HashMap<>();
        boolean changed = false;
        for (InlineTextEdit edit : new ArrayList<>(textEditControls.values())) {
            String updated = edit.editor().getText();
            if (updated == null || updated.equals(edit.originalText())) {
                continue;
            }
            try {
                if (!undoPushed.containsKey(edit.session())) {
                    edit.session().pdfService.pushUndoState();
                    undoPushed.put(edit.session(), true);
                }
                edit.session().pdfService.replaceTextInArea(edit.page(), edit.startX(), edit.startY(), edit.endX(), edit.endY(), edit.session().zoom, updated);
                changed = true;
            } catch (IOException ex) {
                showError("Nao foi possivel gravar edicao de texto", ex);
                return;
            }
        }
        if (changed) {
            clearTextEditOverlays();
            DocumentSession session = activeSession();
            if (session != null) {
                renderAllPages(session);
                if ("edit-text".equals(currentTool())) {
                    Platform.runLater(this::refreshTextEditMode);
                }
            }
            status.setText("Texto editado");
        }
    }

    private void clearTextEditOverlays() {
        if (textEditControls.isEmpty()) {
            return;
        }
        for (InlineTextEdit edit : new ArrayList<>(textEditControls.values())) {
            Parent parent = edit.editor().getParent();
            if (parent instanceof Pane pane) {
                pane.getChildren().remove(edit.editor());
            }
        }
        textEditControls.clear();
    }

    private void drawSignaturePreview(Canvas canvas, double endX, double endY) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double x = Math.min(dragStartX, endX);
        double y = Math.min(dragStartY, endY);
        double width = Math.abs(endX - dragStartX);
        double height = Math.abs(endY - dragStartY);
        gc.setFill(Color.rgb(255, 255, 255, 0.72));
        gc.setStroke(Color.rgb(45, 77, 145, 0.95));
        gc.setLineWidth(1.4);
        gc.fillRect(x, y, width, height);
        gc.strokeRect(x, y, width, height);
        gc.setFill(Color.rgb(28, 42, 72, 0.92));
        gc.fillText("Assinatura digital", x + 8, y + Math.min(22, Math.max(14, height - 8)));
    }

    private boolean selectionTooSmall(double endX, double endY) {
        return Math.abs(endX - dragStartX) < 5 || Math.abs(endY - dragStartY) < 5;
    }

    private Cursor cursorForCurrentTool() {
        return "edit-text".equals(currentTool()) ? Cursor.TEXT : Cursor.CROSSHAIR;
    }

    private void undoLastChange() {
        DocumentSession session = activeSession();
        if (session == null) {
            ensureOpen();
            return;
        }
        if (!session.pdfService.canUndo()) {
            status.setText("Nada para desfazer");
            return;
        }
        try {
            session.pdfService.undo();
            renderAllPages(session);
            status.setText("Ultima alteracao desfeita");
        } catch (IOException ex) {
            showError("Nao foi possivel desfazer", ex);
        }
    }

    private void selectText(int page, double endX, double endY, double screenX, double screenY) throws IOException {
        DocumentSession session = activeSession();
        if (session == null) {
            return;
        }
        selectedText = session.pdfService.extractTextInArea(page, dragStartX, dragStartY, endX, endY, session.zoom);
        if (selectedText.isBlank()) {
            status.setText("Nenhum texto selecionado");
            selectionMenu.hide();
            return;
        }
        selectionMenu.show(stage, screenX, screenY);
        status.setText("Texto selecionado");
    }

    private void copyStoredSelection() {
        if (selectedText == null || selectedText.isBlank()) {
            status.setText("Nenhum texto selecionado");
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(selectedText);
        Clipboard.getSystemClipboard().setContent(content);
        status.setText("Texto copiado");
    }

    private Color opaqueToolColor() {
        Color color = colorPicker.getValue();
        return Color.color(color.getRed(), color.getGreen(), color.getBlue(), 1.0);
    }

    private void searchText() {
        DocumentSession session = activeSession();
        if (session == null) {
            ensureOpen();
            return;
        }
        String query = searchField.getText().trim();
        if (query.isEmpty()) {
            clearSearchHighlights();
            return;
        }
        status.setText("Pesquisando...");
        session.searchHits = session.pdfService.findTextHits(query, session.pageIndex);
        session.searchQuery = query;
        session.searchHitIndex = 0;
        if (!session.searchHits.isEmpty()) {
            showSearchHit(session, 0);
        } else {
            session.searchHitIndex = -1;
            status.setText("Texto nao encontrado");
            rerenderVisiblePage(session);
        }
        Platform.runLater(root::requestFocus);
    }

    private void moveSearchHit(int delta) {
        DocumentSession session = activeSession();
        if (session == null) {
            ensureOpen();
            return;
        }
        String query = searchField.getText().trim();
        if (query.isEmpty()) {
            return;
        }
        if (session.searchHits.isEmpty() || !query.equals(session.searchQuery)) {
            searchText();
            return;
        }
        int size = session.searchHits.size();
        int next = Math.floorMod(session.searchHitIndex + delta, size);
        showSearchHit(session, next);
    }

    private void showSearchHit(DocumentSession session, int hitIndex) {
        if (session.searchHits.isEmpty()) {
            return;
        }
        session.searchHitIndex = Math.max(0, Math.min(hitIndex, session.searchHits.size() - 1));
        PdfDocumentService.SearchHit hit = session.searchHits.get(session.searchHitIndex);
        session.pageIndex = hit.pageIndex();
        updatePageLabels();
        renderPage(session, hit.pageIndex(), session.renderTicket.get());
        scrollToSearchHit(session, hit);
        status.setText("Resultado " + (session.searchHitIndex + 1) + " / " + session.searchHits.size()
                + " - pagina " + (hit.pageIndex() + 1));
        Platform.runLater(root::requestFocus);
    }

    private Optional<PdfDocumentService.SearchHit> currentSearchHit(DocumentSession session) {
        if (session == null || session.searchHitIndex < 0 || session.searchHitIndex >= session.searchHits.size()) {
            return Optional.empty();
        }
        return Optional.of(session.searchHits.get(session.searchHitIndex));
    }

    private void clearSearchHighlights() {
        DocumentSession session = activeSession();
        if (session == null) {
            return;
        }
        if (session.searchHits.isEmpty() && session.searchQuery.isBlank()) {
            return;
        }
        session.searchHits = java.util.List.of();
        session.searchHitIndex = -1;
        session.searchQuery = "";
        status.setText("Pesquisa limpa");
        renderAllPages(session);
    }

    private void rerenderVisiblePage(DocumentSession session) {
        if (session != null && session.pdfService.isOpen()) {
            renderPage(session, session.pageIndex, session.renderTicket.get());
        }
    }

    private void startValidation() {
        DocumentSession session = activeSession();
        if (session == null) {
            ensureOpen();
            return;
        }
        showValidationResult(session);
    }

    private void startConversion() {
        Optional<Path> clipboardPath = clipboardPath();
        if (clipboardPath.isPresent()) {
            Path path = clipboardPath.get();
            if (Files.isRegularFile(path) && path.toString().toLowerCase().endsWith(".pdf")) {
                convertFile(path.toFile());
                return;
            }
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Converter PDF para OFX");
        configureInitialDirectoryFromClipboard(chooser);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
        File input = chooser.showOpenDialog(stage);
        if (input == null) {
            return;
        }
        convertFile(input);
    }

    private void convertFile(File input) {
        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws Exception {
                return converterService.convert(input.toPath());
            }
        };
        task.setOnRunning(event -> status.setText("Convertendo arquivo..."));
        task.setOnSucceeded(event -> {
            Path output = task.getValue();
            status.setText("OFX gerado: " + output.getFileName());
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Conversor");
            alert.setHeaderText("OFX gerado");
            alert.setContentText(output.toString());
            alert.showAndWait();
        });
        task.setOnFailed(event -> showError("Nao foi possivel converter", task.getException()));
        renderExecutor.submit(task);
    }

    private void showValidationResult(DocumentSession session) {
        try {
            SignatureService.ValidationReport report = signatureService.validatePdf(session.path);
            Alert alert = new Alert(report.valid() ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING);
            alert.setTitle("Validador de assinatura");
            alert.setHeaderText(validationHeader(report));
            TextArea details = new TextArea(validationText(report));
            details.setEditable(false);
            details.setWrapText(true);
            details.setPrefColumnCount(82);
            details.setPrefRowCount(18);
            details.setMaxWidth(Double.MAX_VALUE);
            details.setMaxHeight(Double.MAX_VALUE);
            alert.getDialogPane().setContent(details);
            alert.getDialogPane().setMinWidth(720);
            alert.getDialogPane().setMaxHeight(560);
            alert.showAndWait();
            status.setText(report.summary());
        } catch (Exception ex) {
            showError("Nao foi possivel validar a assinatura", ex);
        }
    }

    private String validationHeader(SignatureService.ValidationReport report) {
        if (report.signatures().isEmpty()) {
            return "Sem assinatura digital";
        }
        if (report.signatures().stream().anyMatch(SignatureService.ValidationSignature::inconclusive)) {
            return "Assinatura encontrada - validacao inconclusiva";
        }
        return report.valid() ? "Assinatura valida" : "Assinatura invalida ou com alerta";
    }

    private String validationText(SignatureService.ValidationReport report) {
        StringBuilder text = new StringBuilder(report.summary()).append(System.lineSeparator()).append(System.lineSeparator());
        for (SignatureService.ValidationSignature signature : report.signatures()) {
            text.append("Assinatura ").append(signature.index()).append(System.lineSeparator());
            String result = signature.valid() ? "VALIDA" : signature.inconclusive() ? "INCONCLUSIVA" : "COM ALERTA / INVALIDA";
            text.append("Resultado: ").append(result).append(System.lineSeparator());
            text.append("Status: ").append(cleanValidationText(signature.status())).append(System.lineSeparator());
            text.append("Criptografia: ").append(signature.cryptographicValid() ? "valida" : "invalida").append(System.lineSeparator());
            text.append("Documento inteiro: ").append(signature.coversWholeDocument() ? "sim" : "nao/ha atualizacoes posteriores").append(System.lineSeparator());
            if (!signature.subject().isBlank()) {
                text.append("Assinado por: ").append(cleanValidationText(signature.subject())).append(System.lineSeparator());
                text.append("Emissor: ").append(cleanValidationText(signature.issuer())).append(System.lineSeparator());
                text.append("Validade: ").append(signature.validFrom()).append(" ate ").append(signature.validUntil()).append(System.lineSeparator());
                text.append("Data da assinatura: ").append(signature.signedAt()).append(System.lineSeparator());
            }
            text.append("Revogacao/ICP-Brasil online: nao verificada ainda").append(System.lineSeparator()).append(System.lineSeparator());
        }
        return text.toString().trim();
    }

    private String cleanValidationText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String clean = value.replaceAll("[^\\p{Print}\\r\\n\\t]", " ");
        return clean.length() > 600 ? clean.substring(0, 600) + "..." : clean;
    }

    private void signA1() {
        DocumentSession session = activeSession();
        if (session == null) {
            ensureOpen();
            return;
        }
        Optional<SavedCertificate> certificate = chooseCertificate();
        if (certificate.isEmpty()) {
            return;
        }
        SavedCertificate savedCertificate = certificate.get();
        if (savedCertificate.status() == CertificateStatus.EXPIRED) {
            showError("Certificado vencido", new IllegalStateException("Esse certificado esta vencido. Escolha um certificado ativo."));
            return;
        }
        if (savedCertificate.status() == CertificateStatus.NEEDS_PASSWORD || savedCertificate.password().isBlank()) {
            Optional<SavedCertificate> registered = registerCertificate(savedCertificate.path());
            if (registered.isEmpty()) {
                return;
            }
            savedCertificate = registered.get();
        }
        pendingSignature = new PendingSignature(savedCertificate, savedCertificate.passwordChars());
        status.setText("Selecione no PDF a area onde a assinatura deve aparecer");
    }

    private Optional<SavedCertificate> chooseCertificate() {
        Dialog<SavedCertificate> dialog = new Dialog<>();
        dialog.setTitle("Certificado digital");
        dialog.setHeaderText("Escolha um certificado A1 cadastrado");

        ListView<SavedCertificate> list = new ListView<>();
        list.getItems().addAll(certificateStore.loadCertificates());
        list.setPrefHeight(260);
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(SavedCertificate item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setContextMenu(null);
                    getStyleClass().removeAll("cert-active", "cert-expiring", "cert-expired", "cert-needs-password");
                    return;
                }
                setText(item.displayName()
                        + System.lineSeparator()
                        + item.validityText()
                        + " - " + item.status().label()
                        + System.lineSeparator()
                        + item.path());
                getStyleClass().removeAll("cert-active", "cert-expiring", "cert-expired", "cert-needs-password");
                getStyleClass().add(switch (item.status()) {
                    case ACTIVE -> "cert-active";
                    case EXPIRING -> "cert-expiring";
                    case EXPIRED -> "cert-expired";
                    case NEEDS_PASSWORD -> "cert-needs-password";
                });

                MenuItem remove = new MenuItem("Remover certificado");
                remove.setOnAction(event -> {
                    certificateStore.removeCertificate(item);
                    list.getItems().setAll(certificateStore.loadCertificates());
                });
                setContextMenu(new ContextMenu(remove));
            }
        });

        Button add = new Button("Adicionar certificado");
        add.setOnAction(event -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Selecionar certificado A1");
            configureInitialDirectoryFromClipboard(chooser);
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Certificado A1", "*.pfx", "*.p12"));
            File file = chooser.showOpenDialog(stage);
            if (file != null) {
                registerCertificate(file.toPath()).ifPresent(registered -> {
                    list.getItems().setAll(certificateStore.loadCertificates());
                    list.getItems().stream()
                            .filter(item -> item.path().equals(registered.path()))
                            .findFirst()
                            .ifPresent(item -> list.getSelectionModel().select(item));
                });
            }
        });

        VBox content = new VBox(10, list, add);
        content.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(content);
        ButtonType use = new ButtonType("Usar certificado", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(use, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == ButtonType.OK ? list.getSelectionModel().getSelectedItem() : null);
        dialog.setResultConverter(button -> button == use ? list.getSelectionModel().getSelectedItem() : null);
        return dialog.showAndWait();
    }

    private Optional<SavedCertificate> registerCertificate(Path path) {
        PasswordDialog passwordDialog = new PasswordDialog();
        Optional<String> password = passwordDialog.showAndWait();
        if (password.isEmpty()) {
            return Optional.empty();
        }
        char[] passwordChars = password.get().toCharArray();
        try {
            SignatureService.CertificateInfo info = signatureService.inspectA1(path, passwordChars);
            certificateStore.addCertificate(path, passwordChars, info);
            return certificateStore.loadCertificates().stream()
                    .filter(item -> item.path().equals(path))
                    .findFirst();
        } catch (Exception ex) {
            showError("Nao foi possivel ler o certificado", ex);
            return Optional.empty();
        } finally {
            Arrays.fill(passwordChars, '\0');
        }
    }

    private void finishSignature(DocumentSession session, int page, double endX, double endY) throws IOException {
        if (pendingSignature == null) {
            return;
        }
        if (selectionTooSmall(endX, endY)) {
            status.setText("Selecione uma area maior para a assinatura");
            return;
        }
        Path output = signedOutputPath(session.path);
        Path temp = null;
        try {
            session.pdfService.pushUndoState();
            session.pdfService.addSignatureMark(page, dragStartX, dragStartY, endX, endY, session.zoom, pendingSignature.certificate().displayName());
            temp = Files.createTempFile("Delvind-PDF-sign-", ".pdf");
            session.pdfService.saveCopy(temp);
            signatureService.signWithA1(temp, output, pendingSignature.certificate().path(), pendingSignature.password());
            loadSignedPdfInCurrentTab(session, output);
            status.setText("PDF assinado salvo e aberto: " + output.getFileName());
        } catch (Exception ex) {
            showError("Nao foi possivel assinar o PDF", ex);
        } finally {
            if (pendingSignature != null) {
                Arrays.fill(pendingSignature.password(), '\0');
            }
            pendingSignature = null;
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Temporary signing file can be removed by the OS later if still locked.
                }
            }
        }
    }

    private void loadSignedPdfInCurrentTab(DocumentSession session, Path output) throws IOException {
        session.pdfService.open(output);
        session.path = output;
        session.pageIndex = 0;
        session.searchHits = java.util.List.of();
        session.searchHitIndex = -1;
        session.searchQuery = "";
        Tab tab = activeTab();
        if (tab != null) {
            tab.setText(output.getFileName().toString());
        }
        stage.setTitle("Delvind PDF - " + output.getFileName());
        buildPagePlaceholders(session);
        updatePageLabels();
        renderAllPages(session);
    }

    private Path signedOutputPath(Path input) {
        Path parent = input == null ? null : input.getParent();
        if (parent == null) {
            parent = Path.of(System.getProperty("user.home"), "Documents");
        }
        String fileName = input == null ? "documento.pdf" : input.getFileName().toString();
        String baseName = fileName.toLowerCase().endsWith(".pdf") ? fileName.substring(0, fileName.length() - 4) : fileName;
        Path output = parent.resolve(baseName + "-assinado.pdf");
        int index = 2;
        while (Files.exists(output)) {
            output = parent.resolve(baseName + "-assinado-" + index + ".pdf");
            index++;
        }
        return output;
    }

    private void scrollToPage(DocumentSession session, int page) {
        PageSurface surface = session.pageSurfaces.get(page);
        if (surface == null) {
            return;
        }
        Platform.runLater(() -> {
            renderAroundPage(session, page);
            Bounds contentBounds = session.documentBox.getLayoutBounds();
            Bounds viewportBounds = session.scrollPane.getViewportBounds();
            double scrollableHeight = contentBounds.getHeight() - viewportBounds.getHeight();
            if (scrollableHeight <= 0) {
                session.scrollPane.setVvalue(0);
                return;
            }
            double targetY = surface.root().getBoundsInParent().getMinY();
            session.scrollPane.setVvalue(Math.max(0, Math.min(1, targetY / scrollableHeight)));
        });
    }

    private void scrollToSearchHit(DocumentSession session, PdfDocumentService.SearchHit hit) {
        PageSurface surface = session.pageSurfaces.get(hit.pageIndex());
        if (surface == null) {
            scrollToPage(session, hit.pageIndex());
            return;
        }
        Platform.runLater(() -> {
            Bounds contentBounds = session.documentBox.getLayoutBounds();
            Bounds viewportBounds = session.scrollPane.getViewportBounds();
            double scrollableHeight = contentBounds.getHeight() - viewportBounds.getHeight();
            if (scrollableHeight <= 0) {
                session.scrollPane.setVvalue(0);
                return;
            }
            double scale = 96 * session.zoom / 72.0;
            double pageTop = surface.root().getBoundsInParent().getMinY();
            double stackTop = surface.stack().getBoundsInParent().getMinY();
            double hitCenterY = (hit.rect().y + (hit.rect().height / 2.0)) * scale;
            double targetY = pageTop + stackTop + hitCenterY - (viewportBounds.getHeight() * 0.42);
            session.scrollPane.setVvalue(Math.max(0, Math.min(1, targetY / scrollableHeight)));
        });
    }

    private void updateCurrentPageFromScroll(DocumentSession session) {
        if (!session.pdfService.isOpen() || session.documentBox.getChildren().isEmpty()) {
            return;
        }
        Bounds viewportBounds = session.scrollPane.getViewportBounds();
        double scrollableHeight = session.documentBox.getLayoutBounds().getHeight() - viewportBounds.getHeight();
        double top = session.scrollPane.getVvalue() * Math.max(0, scrollableHeight);
        int current = session.pageIndex;
        for (int index = 0; index < session.pdfService.pageCount(); index++) {
            PageSurface surface = session.pageSurfaces.get(index);
            if (surface != null && surface.root().getBoundsInParent().getMinY() <= top + 80) {
                current = index;
            }
        }
        if (current != session.pageIndex) {
            session.pageIndex = current;
            updatePageLabels();
            renderAroundPage(session, current);
        }
    }

    private void updatePageLabels() {
        DocumentSession session = activeSession();
        if (session == null || !session.pdfService.isOpen()) {
            pageField.setText("");
            pageInfo.setText("0 / 0");
            return;
        }
        pageField.setText(String.valueOf(session.pageIndex + 1));
        pageInfo.setText("/ " + session.pdfService.pageCount());
    }

    private void clearOverlay(Canvas canvas) {
        canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    private void drawSearchHits(DocumentSession session, int pageIndex) {
        PageSurface surface = session.pageSurfaces.get(pageIndex);
        if (surface == null || session.searchHits.isEmpty()) {
            return;
        }
        GraphicsContext gc = surface.canvas().getGraphicsContext2D();
        double scale = 96 * session.zoom / 72.0;
        for (int index = 0; index < session.searchHits.size(); index++) {
            PdfDocumentService.SearchHit hit = session.searchHits.get(index);
            if (hit.pageIndex() != pageIndex) {
                continue;
            }
            boolean current = index == session.searchHitIndex;
            gc.setFill(current ? Color.rgb(255, 193, 7, 0.45) : Color.rgb(126, 87, 194, 0.24));
            gc.setStroke(current ? Color.rgb(255, 143, 0, 0.95) : Color.rgb(126, 87, 194, 0.65));
            float padY = Math.max(1.2f, hit.rect().height * 0.22f);
            double x = hit.rect().x * scale;
            double y = Math.max(0, (hit.rect().y - padY) * scale);
            double width = hit.rect().width * scale;
            double height = (hit.rect().height + (padY * 2)) * scale;
            gc.fillRect(x, y, width, height);
            gc.strokeRect(x, y, width, height);
        }
    }

    private String currentTool() {
        Toggle selected = toolGroup.getSelectedToggle();
        return selected == null ? "select" : String.valueOf(selected.getUserData());
    }

    private boolean ensureOpen() {
        DocumentSession session = activeSession();
        if (session != null && session.pdfService.isOpen()) {
            return true;
        }
        status.setText(session == null ? "Nenhum PDF aberto" : "PDF ainda esta abrindo");
        return false;
    }

    private boolean requestClose() {
        commitTextEditOverlays();
        for (Tab tab : tabPane.getTabs()) {
            DocumentSession session = (DocumentSession) tab.getUserData();
            tabPane.getSelectionModel().select(tab);
            if (!confirmSaveOrDiscard(session)) {
                return false;
            }
        }
        renderExecutor.shutdownNow();
        ioExecutor.shutdownNow();
        for (Tab tab : tabPane.getTabs()) {
            DocumentSession session = (DocumentSession) tab.getUserData();
            try {
                session.pdfService.close();
            } catch (IOException ignored) {
                // Closing the app should not block on a cleanup alert.
            }
        }
        return true;
    }

    private boolean confirmSaveOrDiscard(DocumentSession session) {
        if (session == null || !session.pdfService.isOpen() || !session.pdfService.isDirty()) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Delvind PDF");
        alert.setHeaderText("O PDF tem alteracoes nao salvas");
        alert.setContentText("Salve as alteracoes antes de continuar ou descarte o que foi alterado.");
        ButtonType save = new ButtonType("Salvar", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Descartar", ButtonBar.ButtonData.NO);
        ButtonType cancel = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(save, discard, cancel);
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() == cancel) {
            return false;
        }
        if (result.get() == discard) {
            return true;
        }
        return saveAsForPrompt(session);
    }

    private boolean saveAsForPrompt(DocumentSession session) {
        return saveSessionToChosenPath(session);
    }

    private boolean saveSessionToChosenPath(DocumentSession session) {
        File output = chooseSaveTarget(session);
        if (output == null) {
            return false;
        }
        try {
            session.pdfService.saveAs(output.toPath());
            session.path = output.toPath();
            session.lastSaveDirectory = output.toPath().getParent();
            Tab tab = activeTab();
            if (tab != null) {
                tab.setText(output.getName());
            }
            status.setText("Arquivo salvo: " + output.getName());
            return true;
        } catch (IOException ex) {
            showError("Nao foi possivel salvar o PDF", ex);
            return false;
        }
    }

    private File chooseSaveTarget(DocumentSession session) {
        File initialDirectory = initialSaveDirectory(session);
        String initialName = originalPdfName(session);
        while (true) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Salvar PDF");
            if (initialDirectory != null && initialDirectory.isDirectory()) {
                chooser.setInitialDirectory(initialDirectory);
            }
            chooser.setInitialFileName(initialName);
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
            File output = chooser.showSaveDialog(stage);
            if (output == null) {
                return null;
            }
            if (output.isDirectory()) {
                initialDirectory = output;
                session.lastSaveDirectory = output.toPath();
                initialName = originalPdfName(session);
                continue;
            }
            Path outputPath = output.toPath();
            if (Files.exists(outputPath) && Files.isDirectory(outputPath)) {
                initialDirectory = output;
                session.lastSaveDirectory = outputPath;
                initialName = originalPdfName(session);
                continue;
            }
            return ensurePdfExtension(output);
        }
    }

    private File ensurePdfExtension(File output) {
        String name = output.getName();
        if (name.toLowerCase().endsWith(".pdf")) {
            return output;
        }
        return output.toPath().resolveSibling(name + ".pdf").toFile();
    }

    private File initialSaveDirectory(DocumentSession session) {
        if (session != null && session.lastSaveDirectory != null && Files.isDirectory(session.lastSaveDirectory)) {
            return session.lastSaveDirectory.toFile();
        }
        if (session != null && session.path != null && session.path.getParent() != null && Files.isDirectory(session.path.getParent())) {
            return session.path.getParent().toFile();
        }
        return clipboardPath()
                .map(path -> Files.isDirectory(path) ? path : path.getParent())
                .filter(path -> path != null && Files.isDirectory(path))
                .map(Path::toFile)
                .orElse(null);
    }

    private String originalPdfName(DocumentSession session) {
        if (session != null && session.path != null && session.path.getFileName() != null) {
            return session.path.getFileName().toString();
        }
        return "documento.pdf";
    }

    private void showError(String title, Throwable throwable) {
        Platform.runLater(() -> {
            status.setText(title);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Delvind PDF");
            alert.setHeaderText(title);
            alert.setContentText(throwable == null ? "" : throwable.getMessage());
            alert.showAndWait();
        });
    }

    private Tab activeTab() {
        return tabPane.getSelectionModel().getSelectedItem();
    }

    private DocumentSession activeSession() {
        Tab tab = activeTab();
        return tab == null ? null : (DocumentSession) tab.getUserData();
    }

    private void updateEmptyState() {
        boolean hasTabs = !tabPane.getTabs().isEmpty();
        tabPane.setVisible(hasTabs);
        tabPane.setManaged(hasTabs);
        documentHost.setVisible(hasTabs);
        documentHost.setManaged(hasTabs);
        emptyState.setVisible(!hasTabs);
        emptyState.setManaged(!hasTabs);
        if (!hasTabs) {
            documentHost.getChildren().clear();
            stage.setTitle("Delvind PDF");
        }
    }

    private class DocumentSession {
        private final PdfDocumentService pdfService = new PdfDocumentService();
        private final VBox documentBox = new VBox(22);
        private final ScrollPane scrollPane = new ScrollPane(documentBox);
        private final Map<Integer, PageSurface> pageSurfaces = new HashMap<>();
        private final Set<Integer> renderedPages = ConcurrentHashMap.newKeySet();
        private final Set<Integer> renderingPages = ConcurrentHashMap.newKeySet();
        private final AtomicInteger renderTicket = new AtomicInteger();
        private java.util.List<PdfDocumentService.SearchHit> searchHits = java.util.List.of();
        private int searchHitIndex = -1;
        private String searchQuery = "";
        private Path path;
        private Path lastSaveDirectory;
        private int pageIndex;
        private double zoom = 1.0;

        DocumentSession(Path path) {
            this.path = path;
            this.lastSaveDirectory = path == null ? null : path.getParent();
            documentBox.setAlignment(Pos.TOP_CENTER);
            documentBox.setPadding(new Insets(28, 0, 28, 0));
            documentBox.getStyleClass().add("document-box");
            scrollPane.setFitToWidth(true);
            scrollPane.setPannable(false);
            scrollPane.getStyleClass().add("workspace");
            scrollPane.setOnScroll(event -> {
                if (event.isControlDown()) {
                    changeZoom(event.getDeltaY() > 0 ? 0.1 : -0.1);
                    event.consume();
                }
            });
            scrollPane.vvalueProperty().addListener((observable, oldValue, newValue) -> updateCurrentPageFromScroll(this));
        }
    }

    private record PageSurface(VBox root, StackPane stack, ImageView imageView, Canvas canvas) {
    }

    private record PendingSignature(SavedCertificate certificate, char[] password) {
    }

    private record InlineTextEdit(
            DocumentSession session,
            int page,
            double startX,
            double startY,
            double endX,
            double endY,
            String originalText,
            TextField editor
    ) {
    }
}

