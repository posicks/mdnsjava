package org.xbill.mDNS.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.xbill.DNS.Options;
import org.xbill.mDNS.Querier;

@SuppressWarnings({"unchecked", "rawtypes"})
public class UnicastProcessor extends NetworkProcessor
{
    protected static interface SocketListener
    {
        public void dataReceived();
    }
    
    
    protected abstract static class UnicastRunner implements Runnable
    {
        SocketChannel channel;
        
        
        UnicastRunner(final SocketChannel channel)
        {
            this.channel = channel;
        }
    }
    
    protected ServerSocketChannel server;
    
    protected Selector selector;
    
    protected Map clients = new HashMap();
    
    protected Map readBuffers = new HashMap();
    
    
    public UnicastProcessor(final InetAddress ifaceAddress, final InetAddress address, final int port, final PacketListener listener)
    throws IOException
    {
        super(ifaceAddress, address, port, listener);
        
        server = ServerSocketChannel.open();
        server.socket().setReuseAddress(true);
        server.socket().bind(new InetSocketAddress(address, port));
        server.configureBlocking(false);
        
        selector = Selector.open();
        server.register(selector, SelectionKey.OP_ACCEPT);
    }
    
    
    @Override
    public void close()
    throws IOException
    {
        selector.close();
        server.socket().close();
        server.close();
    }
    
    
    public void run()
    {
        try
        {
            while (!exit)
            {
                /*
                 * TODO: For mDNS responses only accept requests from devices that are on the
                 * same subnet as the interface.
                 * 
                 * (I & M) == (P & M)
                 * 
                 * where:
                 * I is the address of the interface receiving the packet.
                 * M is the subnet mask of the interface receiving the packet.
                 * P is the source address of the packet.
                 * 
                 * TODO: Set IP TTL to 255
                 */
                if (selector.select(Querier.DEFAULT_RESPONSE_WAIT_TIME) > 0)
                {
                    for (Iterator i = selector.selectedKeys().iterator(); i.hasNext();)
                    {
                        SelectionKey key = (SelectionKey) i.next();
                        i.remove();
                        
                        if (key.isValid())
                        {
                            try
                            {
                                if (key.isConnectable())
                                {
                                    ((SocketChannel) key.channel()).finishConnect();
                                }
                                
                                if (key.isAcceptable())
                                {
                                    // Accept connection
                                    SocketChannel client = server.accept();
                                    client.configureBlocking(false);
                                    client.socket().setTcpNoDelay(true);
                                    client.socket().setKeepAlive(true);
                                    client.register(selector, SelectionKey.OP_READ);
                                    clients.put(key, client);
                                }
                                
                                if (key.isReadable())
                                {
                                    SocketChannel channel = (SocketChannel) key.channel();
                                    
                                    ByteBuffer readBuffer = (ByteBuffer) readBuffers.get(key);
                                    if (readBuffer == null)
                                    {
                                        readBuffer = ByteBuffer.allocateDirect(mtu);
                                        readBuffers.put(key, readBuffer);
                                    }
                                    
                                    if (channel.read(readBuffer) == -1)
                                    {
                                        throw new IOException("Read on closed key");
                                    } else
                                    {
                                        // readBuffer.flip();
                                        
                                        System.out.println("Received message from " + channel.socket().getRemoteSocketAddress());
                                        // threadPool.execute(new PacketDispatchRunner(new Packet(datagram), listeners));
                                    }
                                }
                                
                                if (key.isWritable())
                                {
                                    // Write data.
                                }
                            } catch (CancelledKeyException e)
                            {
                                // Connection closed, remove Client.
                                readBuffers.remove(key);
                            }
                        } else
                        {
                            // Connection closed, remove Client.
                            readBuffers.remove(key);
                        }
                    }
                }
                
                /*
                 * final SocketChannel channel = server.accept(); if (channel !=
                 * null) { threadPool.execute(new UnicastRunner(channel) {
                 * 
                 * @Override public void run() { try { ByteBuffer buffer =
                 * ByteBuffer.allocateDirect(mtu); int bytesRead =
                 * channel.read(buffer); if (bytesRead > 0) { } } catch
                 * (IOException e) { if (!(!server.isOpen() && exit)) { if
                 * (Options.check("mdns_verbose")) {
                 * System.err.println("Error receiving data from \"" + address +
                 * "\" - " + e.getMessage()); e.printStackTrace(System.err); } }
                 * } } });
                 */
            }
        } catch (SecurityException e)
        {
            if (Options.check("mdns_verbose"))
            {
                System.err.println("Security issue receiving data from \"" + address + "\" - " + e.getMessage());
                e.printStackTrace(System.err);
            }
        } catch (Exception e)
        {
            System.err.println("!!!!! Error receiving data from \"" + address + "\" - " + e.getMessage() + " !!!!!");
            e.printStackTrace(System.err);
            if (server.isOpen() && !exit)
            {
                if (Options.check("mdns_verbose"))
                {
                    System.err.println("Error receiving data from \"" + address + "\" - " + e.getMessage());
                    e.printStackTrace(System.err);
                }
            }
        }
    }
    
    
    @Override
    public void send(final byte[] data)
    throws IOException
    {
        // TODO Auto-generated method stub
        
    }
}
