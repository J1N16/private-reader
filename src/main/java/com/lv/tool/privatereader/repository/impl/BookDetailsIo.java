package com.lv.tool.privatereader.repository.impl;

import com.intellij.openapi.diagnostic.Logger;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.repository.StorageRepository;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * 书籍详情文件 IO(每本书 details.json 的读写)。
 * <p>
 * 从 FileBookRepository 拆出:负责详情文件的原子写入(temp + rename/复制回退)、
 * 内容读取与目录清理。序列化逻辑在 {@link BookJsonCodec}。
 */
final class BookDetailsIo {

    private static final Logger LOG = Logger.getInstance(BookDetailsIo.class);

    private final StorageRepository storageRepository;
    private final BookJsonCodec codec;

    BookDetailsIo(StorageRepository storageRepository, BookJsonCodec codec) {
        this.storageRepository = storageRepository;
        this.codec = codec;
    }

    /**
     * 读取书籍详情 JSON 内容;文件不存在返回 null。
     */
    String readDetails(String bookId) {
        File detailsFile = detailsFile(bookId);
        if (!detailsFile.exists()) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(detailsFile.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("读取书籍详情文件失败: " + detailsFile.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * 保存书籍详情到 details.json(原子写入 + rename/复制回退)。
     */
    void saveDetails(Book book) {
        String bookId = book.getId();
        if (bookId == null) {
            LOG.error("无法保存书籍详情:book.getId() 为空");
            return;
        }

        // 获取书籍目录路径
        String bookDirPath = storageRepository.getBookDirectory(bookId);
        if (bookDirPath == null) {
            LOG.error("无法获取书籍目录路径,无法保存: " + bookId);
            return;
        }

        File detailsFile = detailsFile(bookId);
        File tempFile = new File(bookDirPath, "details.json.tmp");

        // 确保父目录存在
        File parentDir = detailsFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                LOG.error("无法创建书籍详情目录: " + parentDir.getAbsolutePath());
                return;
            }
        }

        try {
            String json = codec.toBookDetailsJson(book);

            // 先写入临时文件
            try (FileWriter writer = new FileWriter(tempFile)) {
                writer.write(json);
                writer.flush();
            }

            // 如果临时文件写入成功,则重命名为目标文件
            if (tempFile.exists() && tempFile.length() > 0) {
                if (!replace(tempFile, detailsFile)) {
                    LOG.error("保存书籍详情失败(rename失败): " + detailsFile.getAbsolutePath());
                }
            } else {
                LOG.error("临时文件写入失败或为空: " + tempFile.getAbsolutePath());
            }
        } catch (Exception e) {
            LOG.error("保存书籍详情失败: " + bookId, e);
            // 清理临时文件
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    /**
     * 备份损坏的 details.json 为 details.json.corrupted.{timestamp}。
     */
    void backupCorrupted(String bookId) {
        File detailsFile = detailsFile(bookId);
        if (!detailsFile.exists()) {
            return;
        }
        File backupFile = new File(detailsFile.getParentFile(),
                "details.json.corrupted." + System.currentTimeMillis());
        try {
            Files.copy(detailsFile.toPath(), backupFile.toPath());
            LOG.info("已将损坏的文件备份到: " + backupFile.getAbsolutePath());
        } catch (Exception e) {
            LOG.error("备份损坏文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除书籍目录及其所有内容(详情文件、备份等)。
     */
    void deleteBookDirectory(String bookId) {
        String bookDir = storageRepository.getBookDirectory(bookId);
        deleteDirectory(new File(bookDir));
    }

    private boolean replace(File tempFile, File targetFile) {
        // 如果目标文件已存在,先删除
        if (targetFile.exists()) {
            if (!targetFile.delete()) {
                LOG.warn("无法删除已存在的书籍详情文件: " + targetFile.getAbsolutePath());
            }
        }

        // 重命名临时文件为目标文件
        if (tempFile.renameTo(targetFile)) {
            LOG.debug("已保存书籍详情: " + targetFile.getAbsolutePath());
            return true;
        }

        // 尝试复制文件内容
        try {
            Files.copy(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            tempFile.delete();
            LOG.debug("通过复制保存书籍详情: " + targetFile.getAbsolutePath());
            return true;
        } catch (IOException copyEx) {
            LOG.error("复制临时文件失败: " + copyEx.getMessage(), copyEx);
        }
        return false;
    }

    private File detailsFile(String bookId) {
        return new File(storageRepository.getBookDirectory(bookId), "details.json");
    }

    private void deleteDirectory(File directory) {
        if (directory == null || !directory.exists()) {
            return;
        }

        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }

        directory.delete();
    }
}