package io.axiom.http.internal;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DuplexChannel;

/** An embedded channel that, like a socket, can shut down its output alone. */
class DuplexEmbeddedChannel extends EmbeddedChannel implements DuplexChannel {
    boolean outputShutdown;
    @Override public boolean isInputShutdown() { return !isActive(); }
    @Override public ChannelFuture shutdownInput() { return shutdownInput(newPromise()); }
    @Override public ChannelFuture shutdownInput(ChannelPromise promise) { return promise.setSuccess(); }
    @Override public boolean isOutputShutdown() { return outputShutdown || !isActive(); }
    @Override public ChannelFuture shutdownOutput() { return shutdownOutput(newPromise()); }
    @Override public ChannelFuture shutdownOutput(ChannelPromise promise) { outputShutdown = true; return promise.setSuccess(); }
    @Override public boolean isShutdown() { return isInputShutdown() && isOutputShutdown(); }
    @Override public ChannelFuture shutdown() { return shutdown(newPromise()); }
    @Override public ChannelFuture shutdown(ChannelPromise promise) { outputShutdown = true; return promise.setSuccess(); }
}
