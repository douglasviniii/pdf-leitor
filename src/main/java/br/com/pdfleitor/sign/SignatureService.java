package br.com.pdfleitor.sign;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.CMSTypedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.util.Store;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;

public class SignatureService {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final int SIGNATURE_RESERVE_BYTES = 65536;

    public CertificateInfo inspectA1(Path certificatePath, char[] password) throws Exception {
        KeyStoreData keyStoreData = loadPkcs12(certificatePath, password);
        X509Certificate certificate = keyStoreData.certificate();
        LocalDate validUntil = certificate.getNotAfter().toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDate();
        return new CertificateInfo(certificate.getSubjectX500Principal().getName(), validUntil);
    }

    public void signWithA1(Path inputPdf, Path outputPdf, Path certificatePath, char[] password) throws Exception {
        KeyStoreData keyStoreData = loadPkcs12(certificatePath, password);
        Files.deleteIfExists(outputPdf);

        try (PDDocument document = Loader.loadPDF(inputPdf.toFile());
             FileOutputStream output = new FileOutputStream(outputPdf.toFile());
             SignatureOptions options = new SignatureOptions()) {
            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED);
            signature.setName(keyStoreData.certificate().getSubjectX500Principal().getName());
            signature.setSignDate(Calendar.getInstance());
            options.setPreferredSignatureSize(SIGNATURE_RESERVE_BYTES);

            document.addSignature(signature, content -> {
                try {
                    return createCmsSignature(IOUtils.toByteArray(content), keyStoreData);
                } catch (Exception ex) {
                    throw new IOException("Nao foi possivel gerar a assinatura CMS.", ex);
                }
            }, options);
            document.saveIncremental(output);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public void signWithA3(Path inputPdf, Path outputPdf, Path pkcs11Library, char[] pin) {
        throw new UnsupportedOperationException("Assinatura A3 sera implementada com PKCS#11 conforme o token/cartao usado.");
    }

    public ValidationReport validatePdf(Path pdfPath) throws Exception {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        List<ValidationSignature> results = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            List<PDSignature> signatures = document.getSignatureDictionaries();
            if (signatures.isEmpty()) {
                return new ValidationReport(false, "Nenhuma assinatura digital encontrada.", results);
            }
            long fileSize = Files.size(pdfPath);
            byte[] pdfBytes = Files.readAllBytes(pdfPath);
            for (int i = 0; i < signatures.size(); i++) {
                PDSignature signature = signatures.get(i);
                results.add(validateSignature(pdfBytes, signature, i + 1, fileSize));
            }
        }
        boolean allValid = results.stream().allMatch(ValidationSignature::valid);
        boolean hasInconclusive = results.stream().anyMatch(ValidationSignature::inconclusive);
        String summary = allValid
                ? "Assinatura criptografica valida. Validacao online ICP-Brasil/Receita ainda nao consultada."
                : hasInconclusive
                ? "Assinatura digital encontrada, mas a validacao criptografica local ficou inconclusiva."
                : "Uma ou mais assinaturas apresentaram problema.";
        return new ValidationReport(allValid, summary, results);
    }

    private ValidationSignature validateSignature(byte[] pdfBytes, PDSignature signature, int index, long fileSize) {
        try {
            int[] byteRange = signature.getByteRange();
            byte[] contents = extractSignatureContents(pdfBytes, byteRange);
            byte[] signedContent = extractSignedContent(pdfBytes, byteRange);
            CMSSignedData cms = new CMSSignedData(new CMSProcessableByteArray(signedContent), contents);
            SignerInformationStore signerInfos = cms.getSignerInfos();
            Store<X509CertificateHolder> certificates = cms.getCertificates();

            for (SignerInformation signer : signerInfos.getSigners()) {
                Collection<X509CertificateHolder> matches = certificates.getMatches(signer.getSID());
                if (matches.isEmpty()) {
                    return ValidationSignature.invalid(index, "Certificado do assinante nao encontrado no PDF.");
                }
                X509Certificate certificate = new JcaX509CertificateConverter()
                        .setProvider("BC")
                        .getCertificate(matches.iterator().next());
                boolean cryptographicValid = signer.verify(new JcaSimpleSignerInfoVerifierBuilder()
                        .setProvider("BC")
                        .build(certificate));
                boolean certificateExpired = certificate.getNotAfter().before(new java.util.Date());
                boolean coversWholeDocument = coversWholeDocument(signature, fileSize);
                String signedAt = signature.getSignDate() == null
                        ? "Data nao informada"
                        : DATE_FORMAT.format(signature.getSignDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
                String status;
                if (!cryptographicValid) {
                    status = "Assinatura criptografica invalida ou documento alterado.";
                } else if (certificateExpired) {
                    status = "Assinatura confere, mas o certificado esta vencido hoje.";
                } else if (!coversWholeDocument) {
                    status = "Assinatura criptografica valida. O PDF possui atualizacoes incrementais apos o byte range.";
                } else {
                    status = "Assinatura criptografica valida.";
                }
                return new ValidationSignature(
                        index,
                        cryptographicValid && !certificateExpired,
                        cryptographicValid,
                        coversWholeDocument,
                        certificateExpired,
                        certificate.getSubjectX500Principal().getName(),
                        certificate.getIssuerX500Principal().getName(),
                        DATE_FORMAT.format(certificate.getNotBefore().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()),
                        DATE_FORMAT.format(certificate.getNotAfter().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()),
                        signedAt,
                        status,
                        false
                );
            }
            return ValidationSignature.invalid(index, "Assinante nao encontrado na assinatura.");
        } catch (Exception ex) {
            return ValidationSignature.inconclusive(index, "Assinatura digital encontrada, mas o validador local nao conseguiu ler o bloco criptografico deste PDF: " + safeMessage(ex));
        }
    }

    private byte[] trimSignatureContents(byte[] contents) {
        int end = contents.length;
        while (end > 0 && contents[end - 1] == 0) {
            end--;
        }
        return Arrays.copyOf(contents, end);
    }

    private byte[] extractSignedContent(byte[] pdfBytes, int[] byteRange) throws IOException {
        validateByteRange(pdfBytes, byteRange);
        byte[] signedContent = new byte[byteRange[1] + byteRange[3]];
        System.arraycopy(pdfBytes, byteRange[0], signedContent, 0, byteRange[1]);
        System.arraycopy(pdfBytes, byteRange[2], signedContent, byteRange[1], byteRange[3]);
        return signedContent;
    }

    private byte[] extractSignatureContents(byte[] pdfBytes, int[] byteRange) throws IOException {
        validateByteRange(pdfBytes, byteRange);
        int contentsStart = byteRange[0] + byteRange[1];
        int contentsEnd = byteRange[2];
        if (contentsEnd <= contentsStart || contentsEnd > pdfBytes.length) {
            throw new IOException("ByteRange da assinatura aponta para um Contents invalido.");
        }
        String raw = new String(pdfBytes, contentsStart, contentsEnd - contentsStart, StandardCharsets.ISO_8859_1);
        int start = raw.indexOf('<');
        int end = raw.lastIndexOf('>');
        if (start >= 0 && end > start) {
            raw = raw.substring(start + 1, end);
        }
        raw = raw.replaceAll("\\s+", "");
        return trimSignatureContents(decodeHex(raw));
    }

    private void validateByteRange(byte[] pdfBytes, int[] byteRange) throws IOException {
        if (byteRange == null || byteRange.length < 4) {
            throw new IOException("Assinatura sem ByteRange valido.");
        }
        long firstEnd = (long) byteRange[0] + byteRange[1];
        long secondEnd = (long) byteRange[2] + byteRange[3];
        if (byteRange[0] < 0 || byteRange[1] < 0 || byteRange[2] < 0 || byteRange[3] < 0
                || firstEnd > pdfBytes.length || secondEnd > pdfBytes.length || byteRange[2] < firstEnd) {
            throw new IOException("ByteRange da assinatura fora do tamanho do PDF.");
        }
    }

    private byte[] decodeHex(String hex) throws IOException {
        if (hex.length() % 2 != 0) {
            hex = hex.substring(0, hex.length() - 1);
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IOException("Contents da assinatura nao esta em hexadecimal valido.");
            }
            bytes[i] = (byte) ((high << 4) + low);
        }
        return bytes;
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.getClass().getSimpleName();
        }
        if (message.toLowerCase().contains("invalid hex string")) {
            return "O bloco interno da assinatura esta corrompido ou foi gravado incompleto. Gere um novo PDF assinado nesta versao.";
        }
        message = message.replaceAll("[^\\p{Print}\\r\\n\\t]", " ");
        if (message.length() > 260) {
            message = message.substring(0, 260) + "...";
        }
        return message;
    }

    private boolean coversWholeDocument(PDSignature signature, long fileSize) {
        int[] byteRange = signature.getByteRange();
        if (byteRange == null || byteRange.length < 4) {
            return false;
        }
        long signedEnd = (long) byteRange[2] + byteRange[3];
        return signedEnd == fileSize;
    }

    private KeyStoreData loadPkcs12(Path certificatePath, char[] password) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = new FileInputStream(certificatePath.toFile())) {
            keyStore.load(input, password);
        }
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (keyStore.isKeyEntry(alias)) {
                PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, password);
                Certificate[] chain = keyStore.getCertificateChain(alias);
                X509Certificate certificate = (X509Certificate) chain[0];
                return new KeyStoreData(privateKey, certificate, Arrays.stream(chain)
                        .map(X509Certificate.class::cast)
                        .toList());
            }
        }
        throw new IllegalArgumentException("Certificado A1 sem chave privada.");
    }

    private byte[] createCmsSignature(byte[] content, KeyStoreData keyStoreData) throws Exception {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        List<X509Certificate> chain = keyStoreData.chain();
        CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC")
                .build(keyStoreData.privateKey());
        generator.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                new JcaDigestCalculatorProviderBuilder().setProvider("BC").build())
                .build(signer, keyStoreData.certificate()));
        generator.addCertificates(new JcaCertStore(chain));

        CMSTypedData data = new CMSProcessableByteArray(content);
        CMSSignedData signedData = generator.generate(data, false);
        return signedData.getEncoded();
    }

    private record KeyStoreData(PrivateKey privateKey, X509Certificate certificate, List<X509Certificate> chain) {
    }

    public record CertificateInfo(String subject, LocalDate validUntil) {
    }

    public record ValidationReport(boolean valid, String summary, List<ValidationSignature> signatures) {
    }

    public record ValidationSignature(
            int index,
            boolean valid,
            boolean cryptographicValid,
            boolean coversWholeDocument,
            boolean certificateExpired,
            String subject,
            String issuer,
            String validFrom,
            String validUntil,
            String signedAt,
            String status,
            boolean inconclusive
    ) {
        static ValidationSignature invalid(int index, String status) {
            return new ValidationSignature(index, false, false, false, false, "", "", "", "", "", status, false);
        }

        static ValidationSignature inconclusive(int index, String status) {
            return new ValidationSignature(index, false, false, false, false, "", "", "", "", "", status, true);
        }
    }
}
