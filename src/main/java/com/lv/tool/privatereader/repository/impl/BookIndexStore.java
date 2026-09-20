package com.lv.tool.privatereader.repository.impl;

import com.intellij.openapi.diagnostic.Logger;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.model.BookIndex;
import com.lv.tool.privatereader.repository.StorageRepository;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 书籍索引文件 IO(主 index.json 的读写)。
 * <p>
 * 从 FileBookRepository 拆出:负责书籍索引的原子写入(temp + rename)、
 * 手动解析优先 + Gson 兜底的读取,以及索引条目新增/更新/移除。
 */
final class BookIndexStore {

    private static final Logger LOG = Logger.getInstance(BookIndexStore.class);

    private final StorageRepository storageRepository;
    private final BookJsonCodec codec;

    BookIndexStore(StorageRepository storageRepository, BookJsonCodec codec) {
        this.storageRepository = storageRepository;
        this.codec = codec;
    }

    /**
     * 读取书籍索引列表。
     */
    List<BookIndex> read() {
        File indexFile = indexFile();
        if (!indexFile.exists()) {
            return new ArrayList<>();
        }

        try {
            // 先尝试直接读取文件内容进行手动解析
            String jsonContent = new String(Files.readAllBytes(indexFile.toPath()), StandardCharsets.UTF_8);
            List<BookIndex> indices = codec.parseIndicesManually(jsonContent);
            if (indices != null) {
                return indices;
            }

            // 作为备选,使用GSON解析
            try (FileReader reader = new FileReader(indexFile)) {
                List<BookIndex> gsonIndices = codec.parseIndicesWithGson(reader);
                return gsonIndices != null ? gsonIndices : new ArrayList<>();
            }
        } catch (Exception e) {
            LOG.error("读取书籍索引文件失败: " + e.getMessage(), e);
        }

        return new ArrayList<>();
    }

    /**
     * 保存书籍索引列表(原子写入:临时文件 + rename)。
     */
    void save(List<BookIndex> indices) {
        try {
            File indexFile = indexFile();
            File tempFile = new File(indexFile.getPath() + ".tmp");

            // 确保父目录存在
            if (indexFile.getParentFile() != null) {
                indexFile.getParentFile().mkdirs();
            }

            String json = codec.toIndicesJson(indices);

            // 先写入临时文件
            try (FileWriter writer = new FileWriter(tempFile)) {
                writer.write(json);
                writer.flush();
            }

            // 如果临时文件写入成功,则重命名为目标文件
            if (tempFile.exists() && tempFile.length() > 0) {
                if (!replace(tempFile, indexFile)) {
                    LOG.error("重命名临时文件失败: " + tempFile.getPath() + " -> " + indexFile.getPath());
                }
            } else {
                LOG.error("临时文件写入失败或为空: " + tempFile.getPath());
            }
        } catch (Exception e) {
            LOG.error("保存书籍索引列表失败: " + e.getMessage(), e);
            File tempFile = new File(indexFile().getPath() + ".tmp");
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    /**
     * 更新或新增一个索引条目并保存。
     */
    void updateAndSave(Book book) {
        List<BookIndex> indices = read();

        boolean found = false;
        for (int i = 0; i < indices.size(); i++) {
            if (indices.get(i).getId().equals(book.getId())) {
                indices.set(i, BookIndex.fromBook(book));
                found = true;
                break;
            }
        }
        if (!found) {
            indices.add(BookIndex.fromBook(book));
        }

        save(indices);
    }

    /**
     * 从索引中移除指定 ID 的书籍并保存。
     */
    void removeAndSave(String bookId) {
        List<BookIndex> indices = read();
        indices.removeIf(index -> index.getId().equals(bookId));
        save(indices);
    }

    /**
     * 清空索引为 []。
     */
    void clear() {
        save(new ArrayList<>());
    }

    /**
     * 简化版 atomic replace:临时文件 → 目标文件。
     * 优先 rename;失败时回退 Files.copy(REPLACE_EXISTING)。
     *
     * @return 是否成功
     */
    private boolean replace(File tempFile, File targetFile) {
        // 如果目标文件已存在,先删除
        if (targetFile.exists()) {
            if (!targetFile.delete()) {
                LOG.warn("无法删除已存在的索引文件: " + targetFile.getAbsolutePath());
            }
        }

        if (tempFile.renameTo(targetFile)) {
            LOG.info("索引文件保存成功: " + targetFile.getAbsolutePath());
            return true;
        }

        // 尝试复制文件内容
        try {
            Files.copy(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            tempFile.delete(); // 删除临时文件
            LOG.info("索引文件保存成功(通过复制): " + targetFile.getAbsolutePath());
            return true;
        } catch (IOException copyEx) {
            LOG.error("复制临时文件失败: " + copyEx.getMessage(), copyEx);
        }
        return false;
    }

    private File indexFile() {
        return new File(storageRepository.getBooksFilePath());
    }
}