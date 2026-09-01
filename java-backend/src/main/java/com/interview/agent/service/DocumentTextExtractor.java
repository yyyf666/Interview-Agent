package com.interview.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** 简历/JD 文档纯文本提取：支持 PDF / DOCX / TXT / MD */
@Slf4j
@Service
public class DocumentTextExtractor {

    private static final Set<String> TEXT_EXT = Set.of("txt", "md", "markdown");

    public String extract(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        try (InputStream in = file.getInputStream()) {
            if (name.endsWith(".pdf")) {
                try (PDDocument doc = Loader.loadPDF(in.readAllBytes())) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    return stripper.getText(doc);
                }
            } else if (name.endsWith(".docx")) {
                try (XWPFDocument doc = new XWPFDocument(in);
                     XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
                    return extractor.getText();
                }
            } else if (TEXT_EXT.stream().anyMatch(ext -> name.endsWith("." + ext)) || name.isEmpty()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw new IllegalArgumentException("暂不支持的文件格式: " + name);
        } catch (IOException e) {
            throw new IllegalStateException("文档解析失败: " + e.getMessage(), e);
        }
    }
}
