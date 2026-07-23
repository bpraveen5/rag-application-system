package com.ragapp.parser;

import com.ragapp.exception.InvalidFileException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Parses uploaded files into plain text.
 * Supports: PDF, DOCX, TXT, Markdown, HTML.
 * Falls back to Apache Tika for unknown types.
 */
@Slf4j
@Service
public class DocumentParserService {

    private final Tika tika = new Tika();

    public record ParseResult(String text, Metadata tikaMetadata) {}

    public ParseResult parse(MultipartFile file) throws IOException {
        String contentType = file.getContentType();
        String ext         = FilenameUtils.getExtension(file.getOriginalFilename()).toLowerCase();

        log.info("Parsing file: {} (contentType={}, ext={})", file.getOriginalFilename(), contentType, ext);

        return switch (ext) {
            case "pdf"                  -> parsePdf(file);
            case "docx"                 -> parseDocx(file);
            case "doc"                  -> parseTika(file);
            case "md", "markdown"       -> parseMarkdown(file);
            case "html", "htm"          -> parseHtml(file);
            case "txt", "text", "csv"   -> parsePlainText(file);
            default                     -> parseTika(file);
        };
    }

    // ─── PDF ──────────────────────────────────────────────────────────────────

    private ParseResult parsePdf(MultipartFile file) throws IOException {
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            Metadata metadata = new Metadata();
            metadata.set("page_count", String.valueOf(doc.getNumberOfPages()));
            metadata.set("content_type", "application/pdf");
            log.debug("PDF parsed: {} pages, {} chars", doc.getNumberOfPages(), text.length());
            return new ParseResult(text, metadata);
        } catch (Exception e) {
            log.warn("PDFBox failed, falling back to Tika: {}", e.getMessage());
            return parseTika(file);
        }
    }

    // ─── DOCX ─────────────────────────────────────────────────────────────────

    private ParseResult parseDocx(MultipartFile file) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(file.getInputStream());
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            String text = extractor.getText();
            Metadata metadata = new Metadata();
            metadata.set("content_type",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            log.debug("DOCX parsed: {} chars", text.length());
            return new ParseResult(text, metadata);
        } catch (Exception e) {
            log.warn("Apache POI failed for DOCX, falling back to Tika: {}", e.getMessage());
            return parseTika(file);
        }
    }

    // ─── Markdown ─────────────────────────────────────────────────────────────

    private ParseResult parseMarkdown(MultipartFile file) throws IOException {
        String markdown = new String(file.getBytes(), StandardCharsets.UTF_8);
        // Convert Markdown → HTML → plain text
        MutableDataSet options = new MutableDataSet();
        Parser parser = Parser.builder(options).build();
        HtmlRenderer renderer = HtmlRenderer.builder(options).build();
        String html = renderer.render(parser.parse(markdown));
        String text = html.replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
        Metadata metadata = new Metadata();
        metadata.set("content_type", "text/markdown");
        log.debug("Markdown parsed: {} chars", text.length());
        return new ParseResult(text, metadata);
    }

    // ─── HTML ─────────────────────────────────────────────────────────────────

    private ParseResult parseHtml(MultipartFile file) throws IOException {
        String html = new String(file.getBytes(), StandardCharsets.UTF_8);
        // Strip tags and normalise whitespace
        String text = html.replaceAll("<style[^>]*>.*?</style>", " ")
                .replaceAll("<script[^>]*>.*?</script>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&[a-zA-Z0-9#]+;", " ")
                .replaceAll("\\s+", " ")
                .trim();
        Metadata metadata = new Metadata();
        metadata.set("content_type", "text/html");
        log.debug("HTML parsed: {} chars", text.length());
        return new ParseResult(text, metadata);
    }

    // ─── Plain Text ───────────────────────────────────────────────────────────

    private ParseResult parsePlainText(MultipartFile file) throws IOException {
        String text = new String(file.getBytes(), StandardCharsets.UTF_8);
        Metadata metadata = new Metadata();
        metadata.set("content_type", "text/plain");
        log.debug("Plain text parsed: {} chars", text.length());
        return new ParseResult(text, metadata);
    }

    // ─── Tika Fallback ────────────────────────────────────────────────────────

    private ParseResult parseTika(MultipartFile file) throws IOException {
        Metadata metadata = new Metadata();
        metadata.set(Metadata.CONTENT_TYPE, file.getContentType());
        BodyContentHandler handler  = new BodyContentHandler(10 * 1024 * 1024); // 10MB buffer
        AutoDetectParser   parser   = new AutoDetectParser();
        ParseContext       context  = new ParseContext();

        try (InputStream is = file.getInputStream()) {
            parser.parse(is, handler, metadata, context);
            String text = handler.toString();
            log.debug("Tika parsed: {} chars, detectedType={}", text.length(),
                    metadata.get(Metadata.CONTENT_TYPE));
            return new ParseResult(text, metadata);
        } catch (TikaException e) {
            throw new InvalidFileException("Could not parse file: " + e.getMessage());
        } catch (org.xml.sax.SAXException e) {
            throw new InvalidFileException("Malformed file content: " + e.getMessage());
        }
    }

    /** Detect content type via Tika (without parsing). */
    public String detectContentType(InputStream is) throws IOException {
        return tika.detect(is);
    }
}
