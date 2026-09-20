package com.lv.tool.privatereader.repository.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.diagnostic.Logger;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.model.BookIndex;
import com.lv.tool.privatereader.parser.NovelParser;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 书籍 JSON 编解码器(纯序列化/反序列化,无 IO)。
 * <p>
 * 从 FileBookRepository 拆出:负责安全 Gson 实例构建、Book/BookIndex 与 JSON 字符串互转、
 * JsonObject 字段安全读取。原 {@code FileBookRepository} 仅保留仓储调度与缓存职责。
 */
final class BookJsonCodec {

    private static final Logger LOG = Logger.getInstance(BookJsonCodec.class);

    private final Gson gson;

    BookJsonCodec() {
        this.gson = createSecureGson();
    }

    Gson gson() {
        return gson;
    }

    /**
     * 创建安全配置的Gson实例,避免访问JDK内部类引起的模块系统限制
     * 并解决IntelliJ平台特有的序列化冲突问题
     */
    private static Gson createSecureGson() {
        // 共享的排除策略实例,用于序列化和反序列化(避免匿名类在每次构建时重复分配)
        com.google.gson.ExclusionStrategy exclusionStrategy = new com.google.gson.ExclusionStrategy() {
            @Override
            public boolean shouldSkipField(com.google.gson.FieldAttributes f) {
                String className = f.getDeclaringClass().getName();
                String fieldName = f.getName();

                // 排除所有可能导致问题的字段
                return className.startsWith("java.lang.invoke.") ||
                       className.startsWith("java.lang.reflect.") ||
                       className.contains("MethodType") ||
                       className.contains("com.intellij.") ||
                       className.contains("com.jetbrains.") ||
                       fieldName.equals("rtype") ||
                       fieldName.equals("ptypes") ||
                       fieldName.equals("supportedSignaturesOfLightServiceConstructors") ||
                       fieldName.equals("myContainer") ||
                       fieldName.equals("myDisposed") ||
                       fieldName.equals("myParentComponentManager") ||
                       fieldName.startsWith("my") || // 排除所有my开头的字段,这是IntelliJ常用的命名
                       fieldName.startsWith("_");   // 排除所有_开头的字段
            }

            @Override
            public boolean shouldSkipClass(Class<?> clazz) {
                String className = clazz.getName();
                // 排除所有可能导致问题的类
                return className.startsWith("java.lang.invoke.") ||
                       className.startsWith("java.lang.reflect.") ||
                       className.contains("MethodType") ||
                       className.contains("com.intellij.") ||
                       className.contains("com.jetbrains.");
            }
        };

        // 排除TypeAdapter工厂,处理可能导致反射错误的类型
        TypeAdapterFactory excludeProblematicTypesFactory = new TypeAdapterFactory() {
            @Override
            public <T> com.google.gson.TypeAdapter<T> create(Gson gson, com.google.gson.reflect.TypeToken<T> type) {
                Class<? super T> rawType = type.getRawType();
                // 排除 MethodType、反射类以及IntelliJ平台特定的类
                if (rawType.getName().startsWith("java.lang.invoke.") ||
                    rawType.getName().startsWith("java.lang.reflect.") ||
                    rawType.getName().contains("MethodType") ||
                    rawType.getName().contains("com.intellij.openapi.project.impl.") ||
                    rawType.getName().contains("com.intellij.serviceContainer.") ||
                    rawType.getName().contains("com.intellij.") ||
                    rawType.getName().contains("com.jetbrains.")) {

                    // 返回一个更简单的适配器,直接写入null而不尝试遍历字段
                    @SuppressWarnings("unchecked")
                    com.google.gson.TypeAdapter<T> adapter = (com.google.gson.TypeAdapter<T>) new com.google.gson.TypeAdapter<Object>() {
                        @Override
                        public void write(com.google.gson.stream.JsonWriter out, Object value) throws IOException {
                            // 简单地写null,避免遍历复杂对象的字段导致的递归问题
                            out.nullValue();
                        }

                        @Override
                        public Object read(com.google.gson.stream.JsonReader in) throws IOException {
                            // 简单跳过读取
                            in.skipValue();
                            return null;
                        }
                    };
                    return adapter;
                }
                return null; // 让Gson处理其他类型
            }
        };

        return new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping() // 禁用HTML转义
                .disableJdkUnsafe() // 禁用不安全的JDK访问
                .excludeFieldsWithModifiers(Modifier.TRANSIENT, Modifier.STATIC) // 排除transient和static字段
                .registerTypeAdapterFactory(excludeProblematicTypesFactory) // 注册我们自定义的类型适配器工厂
                .addSerializationExclusionStrategy(exclusionStrategy)
                .addDeserializationExclusionStrategy(exclusionStrategy)
                .serializeNulls() // 序列化 null 值
                .create();
    }

    /**
     * 将书籍详情序列化为 JSON 字符串(与 FileBookRepository 原 saveBookDetails 构建的字段一致)。
     */
    String toBookDetailsJson(Book book) {
        // 创建一个简化的书籍对象,只包含需要保存的字段
        Map<String, Object> bookData = new HashMap<>();
        bookData.put("id", book.getId());
        bookData.put("title", book.getTitle());
        bookData.put("author", book.getAuthor());
        bookData.put("url", book.getUrl());
        bookData.put("sourceId", book.getSourceId());
        bookData.put("createTimeMillis", book.getCreateTimeMillis());
        bookData.put("lastChapter", book.getLastChapter());
        bookData.put("totalChapters", book.getTotalChapters());

        // Add progress data to details.json to ensure persistence across sessions
        bookData.put("lastReadChapter", book.getLastReadChapter());
        bookData.put("lastReadChapterId", book.getLastReadChapterId());
        bookData.put("lastReadPosition", book.getLastReadPosition());
        bookData.put("lastReadTimeMillis", book.getLastReadTimeMillis());
        bookData.put("currentChapterIndex", book.getCurrentChapterIndex());
        bookData.put("finished", book.isFinished());
        bookData.put("lastReadPage", book.getLastReadPage());

        bookData.put("cachedChapters", book.getCachedChapters() != null ? book.getCachedChapters() : new ArrayList<>());

        return gson.toJson(bookData);
    }

    /**
     * 将书籍索引导出为 JSON 字符串。
     */
    String toIndicesJson(List<BookIndex> indices) {
        return gson.toJson(indices);
    }

    /**
     * 解析 JSON 字符串为 Book 对象。
     *
     * @param jsonContent JSON 内容
     * @param bookId 书籍 ID
     * @return 解析后的 Book 对象,如果解析失败则返回 null
     */
    Book parseBook(String jsonContent, String bookId) {
        if (jsonContent == null || jsonContent.isEmpty()) {
            LOG.warn("JSON content is null or empty for book: " + bookId);
            return null;
        }

        try {
            JsonObject json = JsonParser.parseString(jsonContent).getAsJsonObject();
            Book book = new Book(
                getString(json, "id", bookId), // Ensure ID is correct
                getString(json, "title", "未知标题"),
                getString(json, "author", "未知作者"),
                getString(json, "url", null)
            );

            // --- Metadata Only --- Keep these fields
            book.setSourceId(getString(json, "sourceId", null));
            book.setCreateTimeMillis(getLong(json, "createTimeMillis", System.currentTimeMillis()));
            book.setLastChapter(getString(json, "lastChapter", null));
            book.setTotalChapters(getInt(json, "totalChapters", 0));

            // --- Progress Data --- Restore these fields from JSON parsing
            book.setLastReadChapter(getString(json, "lastReadChapter", null));
            book.setLastReadChapterId(getString(json, "lastReadChapterId", null));
            book.setLastReadPosition(getInt(json, "lastReadPosition", 0));
            book.setLastReadTimeMillis(getLong(json, "lastReadTimeMillis", 0));
            book.setCurrentChapterIndex(getInt(json, "currentChapterIndex", 0));
            book.setFinished(getBoolean(json, "finished", false));
            book.setLastReadPage(getInt(json, "lastReadPage", 1));

            // --- Cached Chapters (Optional) ---
            if (json.has("cachedChapters") && json.get("cachedChapters").isJsonArray()) {
                try {
                    Type chapterListType = new TypeToken<List<NovelParser.Chapter>>(){}.getType();
                    List<NovelParser.Chapter> chapters = gson.fromJson(json.get("cachedChapters"), chapterListType);
                    book.setCachedChapters(chapters);
                } catch (Exception e) {
                    LOG.warn("Failed to parse cachedChapters for book: " + bookId, e);
                    book.setCachedChapters(new ArrayList<>()); // Set empty list on error
                }
            } else {
                book.setCachedChapters(new ArrayList<>());
            }

            LOG.debug("Successfully parsed book from JSON: " + book.getId());
            return book;
        } catch (com.google.gson.JsonSyntaxException | IllegalStateException | NullPointerException e) {
            LOG.error("Failed to parse JSON content for book: " + bookId, e);
            return null;
        }
    }

    /**
     * 从索引 JSON 对象构造 BookIndex(安全字段读取)。
     */
    BookIndex parseIndexFromObject(JsonObject indexObject) {
        BookIndex index = new BookIndex();
        index.setId(getString(indexObject, "id", ""));
        index.setTitle(getString(indexObject, "title", ""));
        index.setAuthor(getString(indexObject, "author", ""));
        index.setUrl(getString(indexObject, "url", ""));
        index.setCreateTimeMillis(getLong(indexObject, "createTimeMillis", 0L));
        index.setLastChapter(getString(indexObject, "lastChapter", null));
        index.setLastReadTimeMillis(getLong(indexObject, "lastReadTimeMillis", 0L));
        index.setTotalChapters(getInt(indexObject, "totalChapters", 0));
        index.setFinished(getBoolean(indexObject, "finished", false));
        return index;
    }

    /**
     * 手动解析索引 JSON 字符串为 BookIndex 列表。
     *
     * @return 解析结果;若内容不是合法 JSON 数组返回 null(由调用方决定 fallback)
     */
    List<BookIndex> parseIndicesManually(String jsonContent) {
        try {
            com.google.gson.JsonArray jsonArray = JsonParser.parseString(jsonContent).getAsJsonArray();
            List<BookIndex> indices = new ArrayList<>();
            for (int i = 0; i < jsonArray.size(); i++) {
                indices.add(parseIndexFromObject(jsonArray.get(i).getAsJsonObject()));
            }
            return indices;
        } catch (Exception e) {
            LOG.warn("手动解析JSON索引文件失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 用 Gson 从 reader 中读取 BookIndex 列表。(返回 null 表示读取失败)
     */
    List<BookIndex> parseIndicesWithGson(java.io.Reader reader) {
        try {
            return gson.fromJson(reader, new TypeToken<List<BookIndex>>(){}.getType());
        } catch (Exception e) {
            LOG.error("使用GSON解析索引文件失败: " + e.getMessage(), e);
            return null;
        }
    }

    // --- JsonObject 安全字段读取辅助方法 ---

    String getString(JsonObject json, String key, String defaultValue) {
        try {
            if (json.has(key) && !json.get(key).isJsonNull()) {
                return json.get(key).getAsString();
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        return defaultValue;
    }

    int getInt(JsonObject json, String key, int defaultValue) {
        try {
            if (json.has(key) && !json.get(key).isJsonNull()) {
                return json.get(key).getAsInt();
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        return defaultValue;
    }

    long getLong(JsonObject json, String key, long defaultValue) {
        try {
            if (json.has(key) && !json.get(key).isJsonNull()) {
                return json.get(key).getAsLong();
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        return defaultValue;
    }

    boolean getBoolean(JsonObject json, String key, boolean defaultValue) {
        try {
            if (json.has(key) && !json.get(key).isJsonNull()) {
                return json.get(key).getAsBoolean();
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        return defaultValue;
    }
}