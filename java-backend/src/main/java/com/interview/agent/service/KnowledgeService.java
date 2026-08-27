package com.interview.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.interview.agent.auth.Ownership;
import com.interview.agent.auth.UserContext;
import com.interview.agent.client.AiServiceClient;
import com.interview.agent.entity.KnowledgeDoc;
import com.interview.agent.mapper.KnowledgeDocMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 知识库管理：文档上传 -> AI层 RAG 索引（清洗/分块/向量化写入 pgvector） */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    /** 详情页原文预览的字符上限（避免大文件整篇回传） */
    private static final int PREVIEW_LIMIT = 4000;

    private final KnowledgeDocMapper docMapper;
    private final FileStorageService storageService;
    private final DocumentTextExtractor extractor;
    private final AiServiceClient aiClient;

    @SuppressWarnings("unchecked")
    public KnowledgeDoc upload(MultipartFile file, String docType, List<String> tags) {
        String filePath = storageService.save(file, "knowledge");
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setUserId(UserContext.userId());
        doc.setTitle(file.getOriginalFilename());
        doc.setFilePath(filePath);
        doc.setDocType(docType == null ? "article" : docType);
        doc.setTags(tags == null ? List.of() : tags);
        doc.setChunkStatus("PENDING");
        doc.setVersion(1);
        docMapper.insert(doc);

        try {
            // 先本地提取纯文本，再由 AI 层执行 清洗->分块->Embedding->pgvector
            String text = extractor.extract(file);
            Map<String, Object> resp = aiClient.postJson("/ai/rag/index", Map.of(
                    "document_id", doc.getId(),
                    "content", text,
                    "metadata", Map.of(
                            "doc_type", doc.getDocType(),
                            "tags", doc.getTags() == null ? List.of() : doc.getTags(),
                            "title", doc.getTitle())));
            Object chunkCount = resp.get("data");
            doc.setChunkCount(chunkCount == null ? 0 : Integer.parseInt(String.valueOf(chunkCount)));
            doc.setChunkStatus("INDEXED");
        } catch (Exception e) {
            log.error("知识库索引失败 docId={}", doc.getId(), e);
            doc.setChunkStatus("FAILED");
        }
        docMapper.updateById(doc);
        return doc;
    }

    /** 重新索引（用于失败补偿或版本更新） */
    @SuppressWarnings("unchecked")
    public KnowledgeDoc reindex(Long id) {
        KnowledgeDoc doc = docMapper.selectById(id);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在: " + id);
        }
        Ownership.check(doc.getUserId(), "知识文档");
        doc.setVersion(doc.getVersion() + 1);
        doc.setChunkStatus("PENDING");
        docMapper.updateById(doc);
        // 内容由 AI 层按 file_path 直读文件重新执行清洗-分块-向量化
        try {
            Map<String, Object> resp = aiClient.postJson("/ai/rag/reindex", Map.of(
                    "document_id", doc.getId(),
                    "file_path", doc.getFilePath()));
            Object chunkCount = resp.get("data");
            doc.setChunkCount(chunkCount == null ? 0 : Integer.parseInt(String.valueOf(chunkCount)));
            doc.setChunkStatus("INDEXED");
        } catch (Exception e) {
            log.error("重新索引失败 docId={}", doc.getId(), e);
            doc.setChunkStatus("FAILED");
        }
        docMapper.updateById(doc);
        return doc;
    }

    public List<KnowledgeDoc> list() {
        LambdaQueryWrapper<KnowledgeDoc> w = new LambdaQueryWrapper<>();
        Long uid = UserContext.userId();
        if (uid != null) {
            w.and(q -> q.isNull(KnowledgeDoc::getUserId).or().eq(KnowledgeDoc::getUserId, uid));
        }
        return docMapper.selectList(w.orderByDesc(KnowledgeDoc::getId));
    }

    public void delete(Long id) {
        KnowledgeDoc doc = docMapper.selectById(id);
        if (doc != null) {
            Ownership.check(doc.getUserId(), "知识文档");
        }
        docMapper.deleteById(id); // 向量表 ON DELETE CASCADE 自动清理
    }

    /** 下载载体：文件名（含扩展名）+ 字节 */
    public record FilePayload(String fileName, byte[] bytes, String contentType) {
    }

    /** 文档详情（R-12）：元信息 + 纯文本原文预览（PDF/Word 不预览，仅可下载） */
    public Map<String, Object> detail(Long id) {
        KnowledgeDoc doc = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("doc", doc);
        out.put("preview", preview(doc));
        out.put("downloadable", doc.getFilePath() != null && !doc.getFilePath().isBlank());
        return out;
    }

    /** 原文下载（R-12）：按标题+原扩展名回传，浏览器直接保存 */
    public FilePayload file(Long id) {
        KnowledgeDoc doc = require(id);
        if (doc.getFilePath() == null || doc.getFilePath().isBlank()) {
            throw new IllegalArgumentException("该文档没有可下载的原文文件");
        }
        byte[] bytes;
        try {
            bytes = storageService.readBytes(doc.getFilePath());
        } catch (RuntimeException e) {
            throw new IllegalStateException("原文文件已丢失，请重新上传后重建索引");
        }
        String name = doc.getTitle() == null || doc.getTitle().isBlank() ? ("doc-" + id) : doc.getTitle();
        String ext = extension(doc.getFilePath());
        if (!ext.isEmpty() && !name.toLowerCase(Locale.ROOT).endsWith("." + ext)) {
            name = name + "." + ext;
        }
        return new FilePayload(name, bytes, "application/octet-stream");
    }

    /** RAG 检索预览（R-12）：透传 AI 层混合检索，返回召回片段与分数 */
    public Object search(Map<String, Object> body) {
        Object query = body.get("query");
        if (query == null || String.valueOf(query).isBlank()) {
            throw new IllegalArgumentException("请先输入检索内容");
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("query", String.valueOf(query));
        Object skill = body.get("skill");
        if (skill != null && !String.valueOf(skill).isBlank()) {
            req.put("skill", String.valueOf(skill));
        }
        Object topK = body.get("topK") != null ? body.get("topK") : body.get("top_k");
        if (topK != null) {
            req.put("top_k", topK);
        }
        return aiClient.postJson("/ai/rag/search", req).get("data");
    }

    private KnowledgeDoc require(Long id) {
        KnowledgeDoc doc = docMapper.selectById(id);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在: " + id);
        }
        Ownership.check(doc.getUserId(), "知识文档");
        return doc;
    }

    private String preview(KnowledgeDoc doc) {
        String path = doc.getFilePath();
        if (path == null || path.isBlank()) {
            return "";
        }
        String ext = extension(path);
        if (!"md".equals(ext) && !"markdown".equals(ext) && !"txt".equals(ext)) {
            return "";
        }
        try {
            String text = new String(storageService.readBytes(path), StandardCharsets.UTF_8);
            return text.length() > PREVIEW_LIMIT ? text.substring(0, PREVIEW_LIMIT) + "\n…（预览已截断，可下载原文）" : text;
        } catch (RuntimeException e) {
            log.warn("读取原文预览失败 docId={}: {}", doc.getId(), e.getMessage());
            return "";
        }
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
