package tlschannel;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.StandardConstants;
import tlschannel.impl.BufferHolder;
import tlschannel.impl.ByteBufferSet;
import tlschannel.impl.TlsChannelImpl;
import tlschannel.impl.TlsChannelImpl.EofException;
import tlschannel.impl.TlsExplorer;

/** A server-side {@link TlsChannel}. */
public class ServerTlsChannel implements TlsChannel {

    private interface SslContextStrategy {

        @FunctionalInterface
        interface SniReader {
            Optional<SNIServerName> readSni() throws IOException, EofException;
        }

        SSLContext getSslContext(SniReader sniReader) throws IOException, EofException;
    }

    private static class SniSslContextStrategy implements SslContextStrategy {

        private final SniSslContextFactory sniSslContextFactory;

        public SniSslContextStrategy(SniSslContextFactory sniSslContextFactory) {
            this.sniSslContextFactory = sniSslContextFactory;
        }

        @Override
        public SSLContext getSslContext(SniReader sniReader) throws IOException, EofException {
            // IO block
            Optional<SNIServerName> nameOpt = sniReader.readSni();
            // call client code
            Optional<SSLContext> chosenContext;
            try {
                chosenContext = sniSslContextFactory.getSslContext(nameOpt);
            } catch (Exception e) {
                throw new TlsChannelCallbackException("SNI callback failed", e);
            }
            return chosenContext.orElseThrow(
                    () -> new SSLHandshakeException("No ssl context available for received SNI: " + nameOpt));
        }
    }

    private static class FixedSslContextStrategy implements SslContextStrategy {

        private final SSLContext sslContext;

        public FixedSslContextStrategy(SSLContext sslContext) {
            this.sslContext = sslContext;
        }

        @Override
        public SSLContext getSslContext(SniReader sniReader) {
            /*
             * Avoid SNI parsing (using the supplied sniReader) when no decision
             * would be made based on it.
             */
            return sslContext;
        }
    }

    private static SSLEngine defaultSSLEngineFactory(SSLContext sslContext) {
        SSLEngine engine = sslContext.createSSLEngine();
        engine.setUseClientMode(false);
        return engine;
    }

    /** Builder of {@link ServerTlsChannel} */
    public static class Builder extends TlsChannelBuilder<Builder> {

        private final SslContextStrategy internalSslContextFactory;
        private Function<SSLContext, SSLEngine> sslEngineFactory = ServerTlsChannel::defaultSSLEngineFactory;

        private Builder(ByteChannel underlying, SSLContext sslContext) {
            super(underlying);
            this.internalSslContextFactory = new FixedSslContextStrategy(sslContext);
        }

        private Builder(ByteChannel wrapped, SniSslContextFactory sslContextFactory) {
            super(wrapped);
            this.internalSslContextFactory = new SniSslContextStrategy(sslContextFactory);
        }

        @Override
        Builder getThis() {
            return this;
        }

        public Builder withEngineFactory(Function<SSLContext, SSLEngine> sslEngineFactory) {
            this.sslEngineFactory = sslEngineFactory;
            return this;
        }

        public ServerTlsChannel build() {
            return new ServerTlsChannel(
                    underlying,
                    internalSslContextFactory,
                    sslEngineFactory,
                    sessionInitCallback,
                    runTasks,
                    plainBufferAllocator,
                    encryptedBufferAllocator,
                    releaseBuffers,
                    waitForCloseConfirmation);
        }
    }

    /**
     * Create a new {@link Builder}, configured with a underlying {@link Channel} and a fixed {@link
     * SSLContext}, which will be used to create the {@link SSLEngine}.
     *
     * @param underlying a reference to the underlying {@link ByteChannel}
     * @param sslContext a fixed {@link SSLContext} to be used
     * @return the new builder
     */
    public static Builder newBuilder(ByteChannel underlying, SSLContext sslContext) {
        return new Builder(underlying, sslContext);
    }

    /**
     * Create a new {@link Builder}, configured with a underlying {@link Channel} and a custom {@link
     * SSLContext} factory, which will be used to create the context (in turn used to create the
     * {@link SSLEngine}, as a function of the SNI received at the TLS connection start.
     *
     * <p><b>Implementation note:</b><br>
     * Due to limitations of {@link SSLEngine}, configuring a {@link ServerTlsChannel} to select the
     * {@link SSLContext} based on the SNI value implies parsing the first TLS frame (ClientHello)
     * independently of the SSLEngine.
     *
     * @param underlying a reference to the underlying {@link ByteChannel}
     * @param sslContextFactory a function from an optional SNI to the {@link SSLContext} to be used
     * @return the new builder
     * @see <a href="https://tools.ietf.org/html/rfc6066#section-3">Server Name Indication</a>
     */
    public static Builder newBuilder(ByteChannel underlying, SniSslContextFactory sslContextFactory) {
        return new Builder(underlying, sslContextFactory);
    }

    private final ByteChannel underlying;
    private final SslContextStrategy sslContextStrategy;
    private final Function<SSLContext, SSLEngine> engineFactory;
    private final Consumer<SSLSession> sessionInitCallback;
    private final boolean runTasks;
    private final TrackingAllocator plainBufAllocator;
    private final TrackingAllocator encryptedBufAllocator;
    private final boolean releaseBuffers;
    private final boolean waitForCloseConfirmation;

    private final Lock initLock = new ReentrantLock();

    private BufferHolder inEncrypted;

    private volatile boolean sniRead = false;
    private SSLContext sslContext = null;
    private TlsChannelImpl impl = null;

    // @formatter:off
    private ServerTlsChannel(
            ByteChannel underlying,
            SslContextStrategy internalSslContextFactory,
            Function<SSLContext, SSLEngine> engineFactory,
            Consumer<SSLSession> sessionInitCallback,
            boolean runTasks,
            BufferAllocator plainBufAllocator,
            BufferAllocator encryptedBufAllocator,
            boolean releaseBuffers,
            boolean waitForCloseConfirmation) {
        this.underlying = underlying;
        this.sslContextStrategy = internalSslContextFactory;
        this.engineFactory = engineFactory;
        this.sessionInitCallback = sessionInitCallback;
        this.runTasks = runTasks;
        this.plainBufAllocator = new TrackingAllocator(plainBufAllocator);
        this.encryptedBufAllocator = new TrackingAllocator(encryptedBufAllocator);
        this.releaseBuffers = releaseBuffers;
        this.waitForCloseConfirmation = waitForCloseConfirmation;
        inEncrypted = new BufferHolder(
                "inEncrypted",
                Optional.empty(),
                encryptedBufAllocator,
                TlsChannelImpl.buffersInitialSize,
                TlsChannelImpl.maxTlsPacketSize,
                false /* plainData */,
                releaseBuffers);
    }

    // @formatter:on

    @Override
    public ByteChannel getUnderlying() {
        return underlying;
    }

    /**
     * Return the used {@link SSLContext}.
     *
     * @return if context if present, of null if the TLS connection as not been initializer, or the
     *     SNI not received yet.
     */
    public SSLContext getSslContext() {
        return sslContext;
    }

    @Override
    public SSLEngine getSslEngine() {
        return impl == null ? null : impl.engine();
    }

    @Override
    public Consumer<SSLSession> getSessionInitCallback() {
        return sessionInitCallback;
    }

    @Override
    public boolean getRunTasks() {
        return impl.getRunTasks();
    }

    @Override
    public TrackingAllocator getPlainBufferAllocator() {
        return plainBufAllocator;
    }

    @Override
    public TrackingAllocator getEncryptedBufferAllocator() {
        return encryptedBufAllocator;
    }

    @Override
    public long read(ByteBuffer[] dstBuffers, int offset, int length) throws IOException {
        ByteBufferSet dest = new ByteBufferSet(dstBuffers, offset, length);
        TlsChannelImpl.checkReadBuffer(dest);
        if (!sniRead) {
            try {
                initEngine();
            } catch (EofException e) {
                return -1;
            }
        }
        return impl.read(dest);
    }

    @Override
    public long read(ByteBuffer[] dstBuffers) throws IOException {
        return read(dstBuffers, 0, dstBuffers.length);
    }

    @Override
    public int read(ByteBuffer dstBuffer) throws IOException {
        return (int) read(new ByteBuffer[] {dstBuffer});
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        ByteBufferSet source = new ByteBufferSet(srcs, offset, length);
        if (!sniRead) {
            try {
                initEngine();
            } catch (EofException e) {
                throw new ClosedChannelException();
            }
        }
        return impl.write(source);
    }

    @Override
    public long write(ByteBuffer[] srcs) throws IOException {
        return write(srcs, 0, srcs.length);
    }

    @Override
    public int write(ByteBuffer srcBuffer) throws IOException {
        return (int) write(new ByteBuffer[] {srcBuffer});
    }

    @Override
    public void renegotiate() throws IOException {
        if (!sniRead) {
            try {
                initEngine();
            } catch (EofException e) {
                throw new ClosedChannelException();
            }
        }
        impl.renegotiate();
    }

    @Override
    public void handshake() throws IOException {
        if (!sniRead) {
            try {
                initEngine();
            } catch (EofException e) {
                throw new ClosedChannelException();
            }
        }
        impl.handshake();
    }

    @Override
    public void close() throws IOException {
        if (impl != null) {
            impl.close();
        }
        if (inEncrypted != null) {
            inEncrypted.dispose();
        }
        underlying.close();
    }

    @Override
    public boolean isOpen() {
        return underlying.isOpen();
    }

    private void initEngine() throws IOException, EofException {
        initLock.lock();
        try {
            if (!sniRead) {
                sslContext = sslContextStrategy.getSslContext(this::getServerNameIndication);
                // call client code
                SSLEngine engine;
                try {
                    engine = engineFactory.apply(sslContext);
                } catch (Exception e) {
                    throw new TlsChannelCallbackException("SSLEngine creation callback failed", e);
                }
                impl = new TlsChannelImpl(
                        underlying,
                        underlying,
                        engine,
                        Optional.of(inEncrypted),
                        sessionInitCallback,
                        runTasks,
                        plainBufAllocator,
                        encryptedBufAllocator,
                        releaseBuffers,
                        waitForCloseConfirmation);
                inEncrypted = null;
                sniRead = true;
            }
        } finally {
            initLock.unlock();
        }
    }

    private Optional<SNIServerName> getServerNameIndication() throws IOException, EofException {
        inEncrypted.prepare();
        try {
            // loop finishes using return statements
            while (true) {
                try {
                    inEncrypted.buffer.flip();
                    try {
                        Map<Integer, SNIServerName> serverNames = TlsExplorer.exploreTlsRecord(inEncrypted.buffer);
                        SNIServerName hostName = serverNames.get(StandardConstants.SNI_HOST_NAME);
                        if (hostName instanceof SNIHostName) {
                            return Optional.of(hostName);
                        } else {
                            return Optional.empty();
                        }
                    } finally {
                        inEncrypted.buffer.compact();
                    }
                } catch (BufferUnderflowException e) {
                    if (!inEncrypted.buffer.hasRemaining()) {
                        inEncrypted.enlarge();
                    }
                    TlsChannelImpl.callChannelRead(underlying, inEncrypted.buffer); // IO block
                }
            }
        } finally {
            inEncrypted.release();
        }
    }

    @Override
    public boolean shutdown() throws IOException {
        return impl != null && impl.shutdown();
    }

    @Override
    public boolean shutdownReceived() {
        return impl != null && impl.shutdownReceived();
    }

    @Override
    public boolean shutdownSent() {
        return impl != null && impl.shutdownSent();
    }
}
