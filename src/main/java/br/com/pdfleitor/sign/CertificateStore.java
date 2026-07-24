package br.com.pdfleitor.sign;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

public class CertificateStore {
    private static final String KEY_CERTIFICATES = "certificates";
    private static final String KEY_CERTIFICATES_V2 = "certificates.v2";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private final Preferences preferences = Preferences.userNodeForPackage(CertificateStore.class);

    public List<SavedCertificate> loadCertificates() {
        Map<String, SavedCertificate> certificates = new LinkedHashMap<>();
        String raw = preferences.get(KEY_CERTIFICATES_V2, "");
        for (String line : raw.split("\\R")) {
            SavedCertificate certificate = decode(line);
            if (certificate != null) {
                certificates.put(certificate.path().toString(), certificate);
            }
        }

        String legacy = preferences.get(KEY_CERTIFICATES, "");
        for (String item : legacy.split("\\|")) {
            if (!item.isBlank() && !certificates.containsKey(item)) {
                Path path = Path.of(item);
                certificates.put(item, new SavedCertificate(path, "", path.getFileName().toString(), 0));
            }
        }
        return new ArrayList<>(certificates.values());
    }

    public void addCertificate(Path path, char[] password, SignatureService.CertificateInfo info) {
        Map<String, SavedCertificate> certificates = new LinkedHashMap<>();
        for (SavedCertificate certificate : loadCertificates()) {
            certificates.put(certificate.path().toString(), certificate);
        }
        certificates.put(path.toString(), new SavedCertificate(
                path,
                new String(password),
                info.subject(),
                info.validUntil().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        ));
        save(certificates.values().stream().toList());
    }

    public void removeCertificate(SavedCertificate certificate) {
        List<SavedCertificate> certificates = loadCertificates().stream()
                .filter(item -> !item.path().equals(certificate.path()))
                .toList();
        save(certificates);
    }

    private void save(List<SavedCertificate> certificates) {
        StringBuilder raw = new StringBuilder();
        for (SavedCertificate certificate : certificates) {
            if (!raw.isEmpty()) {
                raw.append(System.lineSeparator());
            }
            raw.append(encode(certificate.path().toString()))
                    .append('\t')
                    .append(encode(certificate.password()))
                    .append('\t')
                    .append(encode(certificate.subject()))
                    .append('\t')
                    .append(certificate.validUntilEpochMillis());
        }
        preferences.put(KEY_CERTIFICATES_V2, raw.toString());
    }

    private SavedCertificate decode(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] parts = line.split("\\t", -1);
        if (parts.length != 4) {
            return null;
        }
        try {
            return new SavedCertificate(
                    Path.of(decodeText(parts[0])),
                    decodeText(parts[1]),
                    decodeText(parts[2]),
                    Long.parseLong(parts[3])
            );
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeText(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    public record SavedCertificate(Path path, String password, String subject, long validUntilEpochMillis) {
        public char[] passwordChars() {
            return password.toCharArray();
        }

        public LocalDate validUntil() {
            if (validUntilEpochMillis <= 0) {
                return null;
            }
            return java.time.Instant.ofEpochMilli(validUntilEpochMillis)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();
        }

        public String validityText() {
            LocalDate date = validUntil();
            return date == null ? "Validade nao lida" : "Valido ate " + DATE_FORMAT.format(date);
        }

        public CertificateStatus status() {
            LocalDate date = validUntil();
            if (date == null) {
                return CertificateStatus.NEEDS_PASSWORD;
            }
            LocalDate today = LocalDate.now();
            if (date.isBefore(today)) {
                return CertificateStatus.EXPIRED;
            }
            if (!date.isAfter(today.plusDays(30))) {
                return CertificateStatus.EXPIRING;
            }
            return CertificateStatus.ACTIVE;
        }

        public String displayName() {
            if (subject == null || subject.isBlank()) {
                return path.getFileName().toString();
            }
            return subject;
        }
    }

    public enum CertificateStatus {
        ACTIVE("Ativo"),
        EXPIRING("Vencendo"),
        EXPIRED("Vencido"),
        NEEDS_PASSWORD("Recadastrar");

        private final String label;

        CertificateStatus(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
