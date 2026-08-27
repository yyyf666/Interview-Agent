package com.interview.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/** 本地文件存储服务 */
@Slf4j
@Service
public class FileStorageService {

    private final Path root;

    public FileStorageService(@Value("${app.storage-path}") String storagePath) {
        this.root = Paths.get(storagePath).toAbsolutePath().normalize();
    }

    /**
     * 保存上传文件到 storage/{category}/yyyy-MM/{uuid}.{ext}
     *
     * @return 文件绝对路径
     */
    public String save(MultipartFile file, String category) {
        try {
            String original = StringUtils.hasText(file.getOriginalFilename())
                    ? file.getOriginalFilename() : "unnamed";
            String ext = StringUtils.getFilenameExtension(original);
            if (ext == null) {
                ext = "bin";
            }
            ext = ext.toLowerCase(Locale.ROOT);
            String storedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            Path dir = root.resolve(category).resolve(LocalDate.now().toString().substring(0, 7));
            Files.createDirectories(dir);
            Path target = dir.resolve(storedName);
            file.transferTo(target);
            log.info("文件已保存: {}", target);
            return target.toString();
        } catch (IOException e) {
            throw new IllegalStateException("文件保存失败: " + e.getMessage(), e);
        }
    }

    public byte[] readBytes(String filePath) {
        try {
            return Files.readAllBytes(Paths.get(filePath));
        } catch (IOException e) {
            throw new IllegalStateException("文件读取失败: " + filePath, e);
        }
    }

    /** 删除受管目录下的文件；失败仅记日志，不阻塞记录删除主流程 */
    public void delete(String filePath) {
        if (!StringUtils.hasText(filePath)) {
            return;
        }
        try {
            Path target = Paths.get(filePath).toAbsolutePath().normalize();
            if (target.startsWith(root)) { // 只允许删存储根目录内的文件，防路径穿越
                Files.deleteIfExists(target);
            }
        } catch (IOException e) {
            log.warn("文件删除失败: {}", filePath, e);
        }
    }
}
