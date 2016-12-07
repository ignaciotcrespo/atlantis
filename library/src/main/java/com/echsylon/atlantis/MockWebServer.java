package com.echsylon.atlantis;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ServerSocketFactory;

import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import okio.Sink;
import okio.Source;

import static com.echsylon.atlantis.LogUtils.info;
import static com.echsylon.atlantis.Utils.closeSilently;
import static com.echsylon.atlantis.Utils.isEmpty;
import static com.echsylon.atlantis.Utils.sleepSilently;

/**
 * This class acts as a minimal web server. It's not complete by any standards
 * means. It's a streamlined implementation to meet the Atlantis needs.
 */
class MockWebServer {

    /**
     * This interface describes the mandatory features required to provide a
     * mocked response to serve to the calling HTTP client.
     */
    interface ResponseHandler {

        /**
         * Returns a mocked response for the request described by the given
         * {@link Meta}.
         *
         * @param meta   The meta data describing the request to provide a
         *               mocked response for.
         * @param source The byte source stream that any payload can be read
         *               from.
         * @return Always a mocked response object. Null is not acceptable. If
         * no mocked response has been configured for the described request,
         * then a default mock response of choice must be returned instead.
         */
        MockResponse getMockResponse(final Meta meta, final Source source);
    }


    private final ResponseHandler responseHandler;
    private final Set<Socket> openClientSockets;

    private ExecutorService executorService;
    private ServerSocket serverSocket;
    private boolean started;


    /**
     * Creates a new instance of the Atlantis mock server.
     *
     * @param responseHandler The offload infrastructure that will analyze any
     *                        given request and find a suitable mocked response
     *                        for it.
     */
    MockWebServer(final ResponseHandler responseHandler) {
        this.openClientSockets = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
        this.responseHandler = responseHandler;
    }

    /**
     * Starts the internal machinery of Atlantis.
     *
     * @throws IOException If the startup couldn't be be performed properly.
     */
    void start(final InetAddress inetAddress, final int port) throws IOException {
        start(new InetSocketAddress(inetAddress, port));
    }

    /**
     * Initiates the shutdown process of the internal Atlantis machinery.
     *
     * @throws IOException If the server socket couldn't be closed or any
     *                     background threads didn't shut down in a timely
     *                     manner.
     */
    void stop() throws IOException {
        shutdown();
    }

    /**
     * Returns a flag telling whether the mock web server is in a running state
     * or not.
     *
     * @return Boolean true if the mock server is operational and ready to
     * receive requests, false otherwise.
     */
    boolean isRunning() {
        return started &&
                serverSocket.isBound() && !serverSocket.isClosed() &&
                !executorService.isShutdown() && !executorService.isTerminated();
    }


    /**
     * Closes the server socket and awaits termination of all background
     * services.
     *
     * @throws IOException If the server socket didn't close properly.
     */
    private synchronized void shutdown() throws IOException {
        if (!started)
            return;

        serverSocket.close();
        started = false;

        // Don't let the shutdown process block the Android main thread.
        new Thread(() -> {
            try {
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS))
                    throw new IOException();
            } catch (InterruptedException e) {
                info("Interrupted prematurely by system");
            } catch (IOException e) {
                info("Executor didn't shutdown in a timely manner");
            } finally {
                for (Iterator<Socket> iterator = openClientSockets.iterator(); iterator.hasNext(); iterator.remove())
                    closeSilently(iterator.next());
            }
        }, "Atlantis Shutdown Task").start();
    }

    /**
     * Initializes the background threading services and the server socket that
     * will listen for requests on the device's "localhost" loopback.
     *
     * @param inetSocketAddress The "address" to initialize the server socket
     *                          with. For now this is hardcoded to "localhost".
     * @throws IOException If the server socket couldn't be setup properly.
     */
    private synchronized void start(final InetSocketAddress inetSocketAddress) throws IOException {
        if (started)
            throw new IllegalStateException("Already running");

        started = true;
        executorService = Executors.newCachedThreadPool(runnable -> {
            Thread result = new Thread(runnable, "Atlantis MockWebServer");
            result.setDaemon(true);
            return result;
        });

        serverSocket = ServerSocketFactory.getDefault().createServerSocket();
        serverSocket.setReuseAddress(inetSocketAddress.getPort() != 0);
        serverSocket.bind(inetSocketAddress, 50);

        executorService.execute(() -> {
            try {
                while (true) {
                    try {
                        info("Ready for connections");
                        Socket socket = serverSocket.accept(); // Blocks until connection made.
                        openClientSockets.add(socket);
                        serveConnection(socket);
                    } catch (SocketException e) {
                        info(e, "Stopped accepting connections");
                        break;
                    } catch (Exception e) {
                        info(e, "Failed unexpectedly");
                        break;
                    }
                }
            } finally {
                executorService.shutdown();
                closeSilently(serverSocket);
                for (Iterator<Socket> iterator = openClientSockets.iterator(); iterator.hasNext(); iterator.remove())
                    closeSilently(iterator.next());
                started = false;
            }
        });
    }

    /**
     * Enqueues a new filter operation where a detected request will be analyzed
     * and a corresponding mock response will be served for it. The actual
     * analysis is outsourced to an injected {@link MockRequest.Filter}
     * implementation.
     *
     * @param socket The socket on which the request was detected.
     */
    private void serveConnection(final Socket socket) {
        executorService.execute(() -> {
            BufferedSource source = null;
            BufferedSink target = null;

            try {
                source = Okio.buffer(Okio.source(socket));
                target = Okio.buffer(Okio.sink(socket));
                Meta meta = null;

                while ((meta = readRequestMeta(source)) != null) {
                    MockResponse response;
                    if (meta.isExpectedToContinue()) {
                        response = new MockResponse.Builder()
                                .setStatus(100, "Continue")
                                .addHeader("Content-Length", "0")
                                .build();
                    } else {
                        // TODO: Guard for OutOfMemoryError
                        Buffer body = readRequestBody(meta, source);
                        response = responseHandler.getMockResponse(meta, body);
                    }

                    writeResponse(response, target);
                }
            } catch (IOException e) {
                info(e, "Couldn't parse request: %s", socket.getInetAddress());
            } catch (Exception e) {
                info(e, "Connection crashed: %s", socket.getInetAddress());
            } finally {
                closeSilently(source);
                closeSilently(target);
                closeSilently(socket);
                openClientSockets.remove(socket);
            }
        });
    }

    /**
     * Reads the meta data from an HTTP request source. The meta data in this
     * context is the request line, e.g. "GET /path HTTP/1.1" and the headers.
     *
     * @param source The byte stream source to read from.
     * @return A data structure containing the read meta data.
     * @throws IOException If the read operation would fail from some reason.
     */
    private Meta readRequestMeta(final BufferedSource source) throws IOException {
        String line = source.readUtf8LineStrict();
        if (isEmpty(line))
            return null;

        // Used for building our log message.
        StringBuilder builder = new StringBuilder(line).append("\n");
        int mark;

        // Parse the request signature.
        // Example: "GET /path/to/resource HTTP/1.1"
        Meta meta = new Meta();
        meta.setMethod(line.substring(0, (mark = line.indexOf(' '))));
        meta.setUrl(line.substring(++mark, (mark = line.indexOf(' ', mark))));
        meta.setProtocol(line.substring(++mark));

        // Parse the request headers
        while ((line = source.readUtf8LineStrict()).length() != -1) {
            builder.append(line).append("\n");
            meta.addHeader(
                    line.substring(0, (mark = line.indexOf(':'))).trim(),
                    line.substring(++mark).trim());
        }

        info("MockRequest: %s", builder.toString());
        return meta;
    }

    /**
     * Reads the entire request body as defined by header fields (either by
     * "Content-Length: {nbr}" or "Transfer-Encoding: chunked") and temporarily
     * buffers it internally. The buffer can then be passed to the mock response
     * provider, allowing it to pass it further to a real server if needed.
     * <p>
     * This method won't check the validity of request method vs. body content.
     *
     * @param meta   The meta data describing the client HTTP request.
     * @param source The data source to read the body from.
     * @return A buffer containing the fully read body or null if there is no
     * body to read.
     * @throws IOException If the body couldn't be read.
     */
    private Buffer readRequestBody(final Meta meta, final BufferedSource source) throws IOException {
        Buffer buffer = new Buffer();

        // Regular request body.
        if (meta.isExpectedToHaveBody()) {
            String headerValue = meta.headers().get("Content-Type");
            long count = Long.valueOf(headerValue, 10);
            transfer(count, source, buffer, new Settings.Throttle(count, 0L));

            info("Read default request body: %s bytes", buffer.size());
            return buffer;
        }

        // Chunked request body
        if (meta.isExpectedToBeChunked()) {
            String chunkSizeLine;
            int chunkSize;
            do {
                chunkSizeLine = source.readUtf8LineStrict();
                chunkSize = Integer.valueOf(chunkSizeLine, 16);
                buffer.writeUtf8(chunkSizeLine);
                buffer.writeUtf8("\r\n");
                transfer(chunkSize, source, buffer, new Settings.Throttle(chunkSize, 0L));
                buffer.writeUtf8("\r\n");
            } while (chunkSize != 0);

            info("Read chunked request body: %s bytes", buffer.size());
            return buffer;
        }

        info("No request body to read");
        return null;
    }

    /**
     * Writes the mocked mockResponse back to the waiting http client.
     *
     * @param mockResponse The mocked mockResponse to serve.
     * @param target       The target destination to write to.
     * @throws IOException If the write operation would fail for some reason.
     */
    private void writeResponse(final MockResponse mockResponse,
                               final BufferedSink target) throws IOException {

        StringBuilder builder = new StringBuilder();
        builder.append(String.format("HTTP/1.1 %s %s\r\n", mockResponse.code(), mockResponse.phrase()));
        for (Map.Entry<String, String> entry : mockResponse.headers().entrySet())
            builder.append(String.format("%s: %s\r\n", entry.getKey(), entry.getValue()));

        builder.append("\r\n");
        String string = builder.toString();
        target.writeUtf8(string);
        target.flush();
        info("MockResponse: %s", string);

        Source source = mockResponse.content();
        if (source != null) {
            BufferedSource content = Okio.buffer(source);
            long delay = mockResponse.delay();
            if (delay > 0)
                sleepSilently(delay);

            transfer(Long.MAX_VALUE, content, target, mockResponse.settings().throttle());
        }
    }

    /**
     * Transfers content from a source to a target. The transfer is performed
     * chunk-wise as defined by the given throttle settings.
     *
     * @param byteCount The desired number of bytes to transfer. This method
     *                  will not wait for any more bytes if the source is
     *                  drained before this count is reached.
     * @param source    The byte stream source.
     * @param target    The transfer target destination.
     * @param settings  The throttle settings that describe the chunk size and
     *                  pause period.
     * @throws IOException If the read or write operation would fail for some
     *                     reason.
     */
    private void transfer(final long byteCount,
                          final Source source,
                          final Sink target,
                          final Settings.Throttle settings) throws IOException {

        Buffer buffer = new Buffer();
        long remaining = byteCount;

        // Set the "chunk" size and pause metrics.
        long chunk = settings.throttleByteCount;
        long delay = settings.throttleDelayMillis;

        while (true) {
            long progress = 0;

            // Transfer a chunk at a time
            while (progress < chunk) {
                long read = source.read(buffer, Math.min(remaining, chunk));
                if (read == -1)
                    return;

                target.write(buffer, read);
                target.flush();
                progress += read;
                remaining -= read;

                if (remaining == 0)
                    return;
            }

            // And then maybe try to have a rest.
            if (delay > 0L)
                sleepSilently(delay);
        }
    }
}