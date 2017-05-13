package tlschannel;

import java.io.IOException;
import java.nio.ByteBuffer;

import javax.net.ssl.SSLEngine;
import java.nio.channels.ByteChannel;
import javax.net.ssl.SSLSession;

import tlschannel.impl.ByteBufferSet;
import tlschannel.impl.TlsChannelImpl;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * A client-side {@link TlsChannel}.
 */
public class ClientTlsChannel implements TlsChannel {

	/**
	 * Builder of {@link ClientTlsChannel}
	 */
	public static class Builder extends TlsChannelBuilder<Builder> {

		private final SSLEngine sslEngine;

		private Builder(ByteChannel underlying, SSLEngine sslEngine) {
			super(underlying);
			this.sslEngine = sslEngine;
		}

		@Override
		Builder getThis() {
			return this;
		}

		public ClientTlsChannel build() {
			return new ClientTlsChannel(underlying, sslEngine, sessionInitCallback, runTasks, plainBufferAllocator,
					encryptedBufferAllocator, waitForCloseConfirmation);
		}

	}

	/**
	 * Create a new {@link Builder}, configured with a underlying
	 * {@link Channel} and a fixed {@link SSLEngine}.
	 * 
	 * @param underlying
	 *            a reference to the underlying {@link ByteChannel}
	 * @param sslEngine
	 *            the engine to use with this channel
	 */
	public static Builder newBuilder(ByteChannel underlying, SSLEngine sslEngine) {
		return new Builder(underlying, sslEngine);
	}

	private final ByteChannel underlying;
	private final TlsChannelImpl impl;

	private ClientTlsChannel(ByteChannel underlying, SSLEngine engine, Consumer<SSLSession> sessionInitCallback,
			boolean runTasks, BufferAllocator plainBufferAllocator, BufferAllocator encryptedBufferAllocator, boolean waitForCloseNotifyOnClose) {
		if (!engine.getUseClientMode())
			throw new IllegalArgumentException("SSLEngine must be in client mode");
		this.underlying = underlying;
		impl = new TlsChannelImpl(underlying, underlying, engine, Optional.empty(), sessionInitCallback, runTasks,
				plainBufferAllocator, encryptedBufferAllocator, waitForCloseNotifyOnClose);
	}

	@Override
	public ByteChannel getUnderlying() {
		return underlying;
	}

	@Override
	public SSLEngine getSslEngine() {
		return impl.engine();
	}

	@Override
	public Consumer<SSLSession> getSessionInitCallback() {
		return impl.getSessionInitCallback();
	}

	@Override
	public BufferAllocator getPlainBufferAllocator() {
		return impl.getPlainBufferAllocator();
	}

	@Override
	public BufferAllocator getEncryptedBufferAllocator() {
		return impl.getEncryptedBufferAllocator();
	}

	@Override
	public boolean getRunTasks() {
		return impl.getRunTasks();
	}

	@Override
	public long read(ByteBuffer[] dstBuffers, int offset, int length) throws IOException {
		ByteBufferSet dest = new ByteBufferSet(dstBuffers, offset, length);
		TlsChannelImpl.checkReadBuffer(dest);
		return impl.read(dest);
	}

	@Override
	public long read(ByteBuffer[] dstBuffers) throws IOException {
		return read(dstBuffers, 0, dstBuffers.length);
	}

	@Override
	public int read(ByteBuffer dstBuffer) throws IOException {
		return (int) read(new ByteBuffer[] { dstBuffer });
	}

	@Override
	public long write(ByteBuffer[] srcBuffers, int offset, int length) throws IOException {
		ByteBufferSet source = new ByteBufferSet(srcBuffers, offset, length);
		return impl.write(source);
	}

	@Override
	public long write(ByteBuffer[] outs) throws IOException {
		return write(outs, 0, outs.length);
	}

	@Override
	public int write(ByteBuffer srcBuffer) throws IOException {
		return (int) write(new ByteBuffer[] { srcBuffer });
	}

	@Override
	public void renegotiate() throws IOException {
		impl.renegotiate();
	}

	@Override
	public void handshake() throws IOException {
		impl.handshake();
	}

	@Override
	public void close() throws IOException {
		impl.close();
	}

	@Override
	public boolean isOpen() {
		return impl.isOpen();
	}

	@Override
	public boolean shutdown() throws IOException {
		return impl.shutdown();
	}

	@Override
	public boolean shutdownReceived() {
		return impl.shutdownReceived();
	}

	@Override
	public boolean shutdownSent() {
		return impl.shutdownSent();
	}
	
}