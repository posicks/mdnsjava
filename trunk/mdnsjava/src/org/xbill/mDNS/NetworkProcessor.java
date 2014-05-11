package org.xbill.mDNS;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public abstract class NetworkProcessor implements Runnable, Closeable
{
    // Normally MTU size is 1500, but can be up to 9000 for jumbo frames.
    public static final int DEFAULT_MTU = 1500;
    
//    protected byte[][] sentPackets = new byte[10][];
    
    
    protected static class Packet
    {
        private InetAddress address;
        
        private int port;
        
        private byte[] data;
        
        
        protected Packet(DatagramPacket datagram)
        {
            this(datagram.getAddress(), datagram.getPort(), datagram.getData(), datagram.getOffset(), datagram.getLength());
        }
        
        
        protected Packet(InetAddress address, int port, byte[] data, int offset, int length)
        {
            this.address = address;
            this.port = port;
            
            this.data = new byte[length];
            System.arraycopy(data, offset, this.data, 0, length);
        }
        
        
        public byte[] getData()
        {
            return data;
        }


        public SocketAddress getSocketAddress()
        {
            return new InetSocketAddress(address, port);
        }


        public InetAddress getAddress()
        {
            return address;
        }


        public int getPort()
        {
            return port;
        }
    }
    
    protected static interface PacketListener
    {
        void packetReceived(NetworkProcessor processor, Packet packet);
    }
    
    protected Executor threadPool = Executors.newCachedThreadPool(new ThreadFactory()
    {
        public Thread newThread(Runnable r)
        {
            Thread t = new Thread(r, "Datagram Processor Thread");
            t.setDaemon(true);
            return t;
        }
    });
    
    
    protected InetAddress ifaceAddress;
    
    protected InetAddress address;
    
    protected boolean ipv6;
    
    protected int port;
    
    protected int mtu = DEFAULT_MTU;
    
    protected boolean exit = false;
    
    protected PacketListener listener;
    
//    protected ListenerProcessor<PacketListener> listenerProcessor = new ListenerProcessor<PacketListener>(PacketListener.class);
    
    
    public NetworkProcessor(InetAddress ifaceAddress, InetAddress address, int port, PacketListener listener)
    throws IOException
    {
        setInterfaceAddress(ifaceAddress);
        setAddress(address);
        setPort(port);
        
        if (ifaceAddress.getAddress().length != address.getAddress().length)
        {
            throw new IOException("Interface Address and bind address bust be the same IP specifciation!");
        }
        
        ipv6 = address.getAddress().length > 4;
        
        this.listener = listener;
    }
    
    
    public void send(byte[] data/*, boolean remember*/)
    throws IOException
    {
//        if (!remember)
//        {
/*
            synchronized (sentPackets)
            {
                for (int index = 0; index < sentPackets.length - 1; index++)
                {
                    sentPackets[index + 1] = sentPackets[index];
                }
                sentPackets[0] = data;
            }
*/
//        }
        
        _send(data);
    }
    
    
    protected abstract void _send(byte[] data)
    throws IOException;
    
    
    public int getMTU()
    {
        return mtu;
    }


    public void setInterfaceAddress(InetAddress address)
    {
        this.ifaceAddress = address;
    }


    public InetAddress getInterfaceAddress()
    {
        return ifaceAddress;
    }


    public void setAddress(InetAddress address)
    {
        this.address = address;
    }


    public InetAddress getAddress()
    {
        return address;
    }


    public void setPort(int port)
    {
        this.port = port;
    }


    public int getPort()
    {
        return port;
    }


    public boolean isIPv6()
    {
        return ipv6;
    }


    public boolean isIPv4()
    {
        return !ipv6;
    }
    
    /*
    protected boolean isSentPacket(byte[] data)
    {
        if (data != null)
        {
            for (byte[] sentPacket : sentPackets)
            {
                if (Arrays.equals(sentPacket, data))
                {
                    return true;
                }
            }
        }
        return false;
    }
    */
}
