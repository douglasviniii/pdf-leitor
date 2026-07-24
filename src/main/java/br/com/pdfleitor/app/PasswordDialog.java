package br.com.pdfleitor.app;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.GridPane;

public class PasswordDialog extends Dialog<String> {
    public PasswordDialog() {
        setTitle("Certificado A1");
        setHeaderText("Senha do certificado");

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Senha");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.add(new Label("Senha:"), 0, 0);
        grid.add(passwordField, 1, 0);

        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        setResultConverter(button -> button == ButtonType.OK ? passwordField.getText() : null);
    }
}
