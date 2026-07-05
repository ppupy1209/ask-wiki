package com.yeonwoo.askwiki.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 업로드된 파일에서 본문 텍스트를 추출한다.
 * md·txt는 UTF-8 텍스트로, pdf는 PDFBox로 텍스트를 뽑는다.
 */
@Component
public class UploadedFileParser {

    private static final Set<String> TEXT_EXTENSIONS = Set.of("md", "txt");

    public String parse(MultipartFile file) {
        String extension = extensionOf(file);
        try {
            if (TEXT_EXTENSIONS.contains(extension)) {
                return new String(file.getBytes(), StandardCharsets.UTF_8);
            }
            if ("pdf".equals(extension)) {
                try (PDDocument document = Loader.loadPDF(file.getBytes())) {
                    return new PDFTextStripper().getText(document);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("파일을 읽을 수 없습니다: " + file.getOriginalFilename(), e);
        }
        throw new IllegalArgumentException("지원하지 않는 형식입니다 (md·txt·pdf만 가능): " + file.getOriginalFilename());
    }

    /** 확장자를 뗀 파일명을 문서 제목 기본값으로 쓴다. */
    public String titleOf(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return "제목 없음";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String extensionOf(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
