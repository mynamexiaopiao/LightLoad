package com.lightload.core;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/** Avoids JDK selector/loopback initialization until a mod actually sends a request. */
public final class DeferredHttpClient extends HttpClient {
    private final Supplier<HttpClient> factory;
    private volatile HttpClient delegate;

    private DeferredHttpClient(Supplier<HttpClient> factory) {
        this.factory = factory;
    }

    public static HttpClient create() {
        return new DeferredHttpClient(HttpClient::newHttpClient);
    }

    public static HttpClient create(HttpClient.Builder builder) {
        return new DeferredHttpClient(builder::build);
    }

    boolean delegateInitialized() {
        return delegate != null;
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return client().cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return client().connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
        return client().followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return client().proxy();
    }

    @Override
    public SSLContext sslContext() {
        return client().sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
        return client().sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return client().authenticator();
    }

    @Override
    public Version version() {
        return client().version();
    }

    @Override
    public Optional<Executor> executor() {
        return client().executor();
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
            throws IOException, InterruptedException {
        return client().send(request, responseBodyHandler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
        return client().sendAsync(request, responseBodyHandler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        return client().sendAsync(request, responseBodyHandler, pushPromiseHandler);
    }

    private HttpClient client() {
        HttpClient current = delegate;
        if (current != null) return current;
        synchronized (this) {
            if (delegate == null) delegate = factory.get();
            return delegate;
        }
    }
}
