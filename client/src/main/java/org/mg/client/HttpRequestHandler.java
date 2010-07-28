package org.mg.client;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;

import org.apache.commons.codec.binary.Base64;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.handler.codec.http.HttpRequestEncoder;
import org.jboss.netty.util.CharsetUtil;
import org.jivesoftware.smack.Chat;
import org.jivesoftware.smack.ChatManager;
import org.jivesoftware.smack.MessageListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.packet.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class for handling all HTTP requests from the browser to the proxy.
 */
public class HttpRequestHandler extends SimpleChannelUpstreamHandler {

    private final static Logger log = 
        LoggerFactory.getLogger(HttpRequestHandler.class);
    private static final String HTTP_KEY = "HTTP";
    
    private static int totalBrowserToProxyConnections = 0;
    private int browserToProxyConnections = 0;
    
    private volatile int messagesReceived = 0;
    
    private final ChannelGroup channelGroup;

    private Chat chat;
    
    private long sequenceNumber = 0L;
    private Channel browserToProxyChannel;
    private final XMPPConnection conn;
    
    /**
     * Creates a new class for handling HTTP requests with the specified
     * authentication manager.
     * 
     * @param channelGroup The group of channels for keeping track of all
     * channels we've opened.
     * @param clientChannelFactory The common channel factory for clients.
     * @param conn The XMPP connection. 
     */
    public HttpRequestHandler(final ChannelGroup channelGroup, 
        final XMPPConnection conn) {
        this.channelGroup = channelGroup;
        this.conn = conn;
        log.info("Using TLS: "+conn.isSecureConnection());
        final ChatManager chatmanager = conn.getChatManager();
        
        this.chat = 
            chatmanager.createChat("mglittleshoot@gmail.com", 
            new MessageListener() {
                public void processMessage(final Chat chat, final Message msg) {
                    log.info("Received message: {}", msg);
                    // We need to grab the HTTP data from the message and send
                    // it to the browser.
                    final String data = (String) msg.getProperty(HTTP_KEY);
                    if (data == null) {
                        log.warn("No HTTP data");
                        return;
                    }
                    final ChannelBuffer cb = xmppToHttpChannelBuffer(msg);
                    browserToProxyChannel.write(cb);
                }
            });
    }
    

    private ChannelBuffer xmppToHttpChannelBuffer(final Message msg) {
        final String data = (String) msg.getProperty(HTTP_KEY);
        final byte[] raw = 
            Base64.decodeBase64(data.getBytes(CharsetUtil.UTF_8));
        return ChannelBuffers.wrappedBuffer(raw);
    }
    
    private static final class LocalHttpRequestEncoder 
        extends HttpRequestEncoder {
        
        @Override
        protected Object encode(final ChannelHandlerContext chc, 
            final Channel channel, final Object msg) throws Exception {
            return super.encode(chc, channel, msg);
        }
    }
    
    @Override
    public void messageReceived(final ChannelHandlerContext ctx, 
        final MessageEvent me) {
        messagesReceived++;
        log.info("Received "+messagesReceived+" total messages");

        final Channel ch = ctx.getChannel();
        if (this.browserToProxyChannel != null && this.browserToProxyChannel != ch) {
            log.error("Got message on a different channel??!");
        }
        this.browserToProxyChannel = ch;
        //final LocalHttpRequestEncoder encoder = new LocalHttpRequestEncoder();
        try {
            //final ChannelBuffer encoded = 
            //    (ChannelBuffer) encoder.encode(ctx, localChannel, me.getMessage());
            final ChannelBuffer cb = (ChannelBuffer) me.getMessage();
            final ByteBuffer buf = cb.toByteBuffer();
            final byte[] raw = toRawBytes(buf);
            final String base64 = Base64.encodeBase64String(raw);
            final Message msg = new Message();
            msg.setProperty(HTTP_KEY, base64);
            
            // The other side will also need to know where the request came
            // from to differentiate incoming HTTP connections.
            msg.setProperty("LOCAL-IP", ch.getLocalAddress().toString());
            msg.setProperty("REMOTE-IP", ch.getRemoteAddress().toString());
            
            // We set the sequence number in case the server delivers the 
            // packets out of order for any reason.
            msg.setProperty("NUM", sequenceNumber);
            
            this.chat.sendMessage(msg);
            sequenceNumber++;
            log.info("Sent message!!");
        } catch (final Exception e) {
            log.error("Could not relay message", e);
        }
    }
    
    public static byte[] toRawBytes(final ByteBuffer buf) {
        final int mark = buf.position();
        final byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        buf.position(mark);
        return bytes;
    }
    
    @Override
    public void channelOpen(final ChannelHandlerContext ctx, 
        final ChannelStateEvent cse) throws Exception {
        final Channel inboundChannel = cse.getChannel();
        log.info("New channel opened: {}", inboundChannel);
        totalBrowserToProxyConnections++;
        browserToProxyConnections++;
        log.info("Now "+totalBrowserToProxyConnections+" browser to proxy channels...");
        log.info("Now this class has "+browserToProxyConnections+" browser to proxy channels...");
        
        // We need to keep track of the channel so we can close it at the end.
        this.channelGroup.add(inboundChannel);
    }
    
    @Override
    public void channelClosed(final ChannelHandlerContext ctx, 
        final ChannelStateEvent cse) {
        log.info("Channel closed: {}", cse.getChannel());
        totalBrowserToProxyConnections--;
        browserToProxyConnections--;
        log.info("Now "+totalBrowserToProxyConnections+" total browser to proxy channels...");
        log.info("Now this class has "+browserToProxyConnections+" browser to proxy channels...");
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, 
        final ExceptionEvent e) throws Exception {
        final Channel channel = e.getChannel();
        final Throwable cause = e.getCause();
        if (cause instanceof ClosedChannelException) {
            log.info("Caught an exception on browser to proxy channel: "+
                channel, cause);
        }
        else {
            log.warn("Caught an exception on browser to proxy channel: "+
                channel, cause);
        }
        if (channel.isOpen()) {
            closeOnFlush(channel);
        }
        this.conn.disconnect();
    }
    
    /**
     * Closes the specified channel after all queued write requests are flushed.
     */
    private static void closeOnFlush(final Channel ch) {
        log.info("Closing on flush: {}", ch);
        if (ch.isConnected()) {
            ch.write(ChannelBuffers.EMPTY_BUFFER).addListener(
                ChannelFutureListener.CLOSE);
        }
    }
}
