package br.com.pdfleitor.pdf;

import javafx.scene.image.Image;

public record RenderedPage(Image image, int pageIndex, double zoom) {
}
