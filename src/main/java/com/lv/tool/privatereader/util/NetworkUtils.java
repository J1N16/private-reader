package com.lv.tool.privatereader.util;

import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 网络工具类
 */
public class NetworkUtils {
    private static final Logger LOG = Logger.getInstance(NetworkUtils.class);
    private static final String[] CHECK_HOSTS = {
        "www.baidu.com",
        "www.qq.com",
        "www.aliyun.com"
    };
    private static final int TIMEOUT = 3000;

    /**
     * 检查网络是否可用
     */
    public static boolean isNetworkAvailable() {
        LOG.info("开始检查网络连接...");
        for (String host : CHECK_HOSTS) {
            if (isHostReachable(host)) {
                LOG.info("网络连接正常，可以访问: " + host);
                return true;
            }
        }
        LOG.warn("网络连接异常，无法访问任何测试主机");
        return false;
    }

    /**
     * 异步检查网络是否可用
     *
     * @param timeoutMs 超时时间（毫秒）
     * @return 网络是否可用的CompletableFuture
     */
    @SuppressWarnings("unchecked")
    public static CompletableFuture<Boolean> isNetworkAvailableAsync(int timeoutMs) {
        LOG.info("开始异步检查网络连接...");

        // 使用CompletableFuture异步测试多个主机，任何一个成功即可
        CompletableFuture<Boolean>[] futures = new CompletableFuture[CHECK_HOSTS.length];
        for (int i = 0; i < CHECK_HOSTS.length; i++) {
            final String host = CHECK_HOSTS[i];
            futures[i] = CompletableFuture.supplyAsync(() -> {
                try {
                    return isHostReachable(host);
                } catch (Exception e) {
                    LOG.warn("测试主机连接失败: " + host, e);
                    return false;
                }
            });
        }

        // 任何一个主机可达即认为网络正常
        return CompletableFuture.supplyAsync(() -> {
            for (CompletableFuture<Boolean> future : futures) {
                try {
                    if (future.get(timeoutMs, TimeUnit.MILLISECONDS)) {
                        LOG.info("网络连接正常（异步检测）");
                        return true;
                    }
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    // 忽略单个主机的异常，继续检查其他主机
                }
            }
            LOG.warn("网络连接异常（异步检测）");
            return false;
        });
    }

    /**
     * 检查指定主机是否可达
     */
    public static boolean isHostReachable(String host) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, 80), TIMEOUT);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 检查网络连接速度
     * @return 连接速度（毫秒），-1表示连接失败
     */
    public static long checkNetworkSpeed(String url) {
        try {
            long start = System.currentTimeMillis();
            URLConnection connection = new URI(url).toURL().openConnection();
            connection.setConnectTimeout(TIMEOUT);
            connection.connect();
            return System.currentTimeMillis() - start;
        } catch (IOException | URISyntaxException e) {
            return -1;
        }
    }

    /**
     * 获取最佳可用主机
     * @return 最佳主机，如果都不可用则返回null
     */
    public static String getBestHost() {
        LOG.info("开始查找最佳可用主机...");
        String bestHost = null;
        long bestSpeed = Long.MAX_VALUE;

        for (String host : CHECK_HOSTS) {
            long speed = checkNetworkSpeed("http://" + host);
            if (speed > 0 && speed < bestSpeed) {
                bestSpeed = speed;
                bestHost = host;
            }
        }

        if (bestHost != null) {
            LOG.info("找到最佳可用主机: " + bestHost + "，响应时间: " + bestSpeed + "ms");
        } else {
            LOG.warn("未找到可用主机");
        }
        return bestHost;
    }

    /**
     * 测试URL是否可访问
     *
     * @param urlString URL字符串
     * @return URL是否可访问
     */
    public static boolean isUrlAccessible(String urlString) {
        return isUrlAccessible(urlString, TIMEOUT);
    }

    /**
     * 测试URL是否可访问
     *
     * @param urlString URL字符串
     * @param timeoutMs 超时时间（毫秒）
     * @return URL是否可访问
     */
    public static boolean isUrlAccessible(String urlString, int timeoutMs) {
        LOG.debug("测试URL可访问性: " + urlString);
        try {
            URL url = new URI(urlString).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setRequestMethod("HEAD");
            int responseCode = connection.getResponseCode();
            LOG.debug("URL访问结果: " + urlString + ", 响应码: " + responseCode);
            return (responseCode >= 200 && responseCode < 400);
        } catch (IOException | URISyntaxException e) {
            LOG.debug("URL访问失败: " + urlString + ", 错误: " + e.getMessage());
            return false;
        }
    }
}