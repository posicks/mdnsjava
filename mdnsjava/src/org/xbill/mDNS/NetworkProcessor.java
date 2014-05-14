package org.xbill.mDNS;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.xbill.DNS.Options;

public abstract class NetworkProcessor implements Runnable, Closeable
{
    // Normally MTU size is 1500, but can be up to 9000 for jumbo frames.
    public static final int DEFAULT_MTU = 1500;
    
//    protected byte[][] sentPackets = new byte[10][];
    
    
    protected static interface PacketListener
    {
        void packetReceived(NetworkProcessor processor, Packet packet);
    }
    
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
    
    protected Executor threadPool = Executors.newCachedThreadPool(new ThreadFactory()
    {
        public Thread newThread(Runnable r)
        {
            Thread t = new Thread(r, "Datagram Processor Thread");
            t.setDaemon(true);
            return t;
        }
    });
    
    protected static class PacketRunner implements Runnable
    {
        private NetworkProcessor networkProcessor;
        
        private Packet[] packets;
        
        
        protected PacketRunner(NetworkProcessor networkProcessor, Packet... packets)
        {
            this.networkProcessor = networkProcessor;
            this.packets = packets;
        }
        
        
        public void run()
        {
System.err.println("Running " + packets.length + " on a single thread");
            for (Packet packet : packets)
            {
                try
                {
                    networkProcessor.listener.packetReceived(networkProcessor, packet);
                } catch (Throwable e)
                {
                    System.err.println("Error dispatching data packet - " + e.getMessage());
                    e.printStackTrace(System.err);
                }
            }
        }
    }
    
    
    protected static class PacketProcessor
    {
        private static final int MAX_PACKETS_PER_PROCESSOR = 5;
        
        private NetworkProcessor networkProcessor;
        
        private Executor executor;
        
        private List<Packet> packets = new ArrayList<Packet>(MAX_PACKETS_PER_PROCESSOR);
        
        
        protected PacketProcessor(NetworkProcessor networkProcessor, Executor executor)
        {
            this.networkProcessor = networkProcessor;
            this.executor = executor;
        }
        
        
        public int getPacketCount()
        {
            return packets.size();
        }
        
        
        public boolean submitPacket(Packet packet)
        {
            packets.add(packet);
            if (packets.size() >= MAX_PACKETS_PER_PROCESSOR)
            {
                return execute();
            }
            return false;
        }


        public boolean execute()
        {
            int size = packets.size();
            if (size > 0)
            {
                executor.execute(new PacketRunner(networkProcessor, packets.toArray(new Packet[size])));
                packets.clear();
                return true;
            }
            return false;
        }
    }
    
    protected static class QueueRunner implements Runnable
    {
        private static final int MAX_CONCURRENT_PACKETS_PROCESSORS = 5;
        
        private NetworkProcessor networkProcessor;
        
        private Executor executor;

        private Queue<Packet> queue;
        
        
        protected QueueRunner(NetworkProcessor networkProcessor, Executor executor, Queue<Packet> queue)
        {
            this.networkProcessor = networkProcessor;
            this.queue = queue;
            this.executor = executor;
        }
        
        
        public void run()
        {
            PacketProcessor[] packetProcessors = new PacketProcessor[MAX_CONCURRENT_PACKETS_PROCESSORS];
            Packet packet = null;
            
            for (int index = 0; index < packetProcessors.length; index++)
            {
                packetProcessors[index] = new PacketProcessor(networkProcessor, executor);
            }
            
            while (!networkProcessor.exit)
            {
                try
                {
                    int count = 0;
                    // Reduce number of processing threads to a minimum
                    while ((packet = queue.poll()) != null)
                    {
                        int index = (count + 1) % packetProcessors.length;
                        if (packetProcessors[index].submitPacket(packet))
                        {
                            if (Options.check("mdns_verbose"))
                            {
                                System.out.println("Packets Processed");
                            }
                        }
                        
                        // Wait a short period for more packets.
                        if (queue.peek() == null)
                        {
                            synchronized (queue)
                            {
                                try
                                {
                                    queue.wait(10);
                                } catch (InterruptedException e)
                                {
                                    // ignore
                                }
                            }
                        }
                    }
                    
                    for (PacketProcessor packetProcessor : packetProcessors)
                    {
                        if (packetProcessor != null)
                        {
                            if (packetProcessor.execute())
                            {
                                if (Options.check("mdns_verbose"))
                                {
                                    System.out.println("Packets Processed");
                                }
                            }
                        }
                    }
                    
                    if (queue.peek() == null && !networkProcessor.exit)
                    {
                        synchronized (queue)
                        {
                            try
                            {
                                queue.wait(10);
                            } catch (InterruptedException e)
                            {
                                // ignore
                            }
                        }
                    }
                } catch (Exception e)
                {
                    System.err.println("Error polling packet Queue - " + e.getMessage());
                    e.printStackTrace(System.err);
                }
            }
        }
    }
    
    protected InetAddress ifaceAddress;
    
    protected InetAddress address;
    
    protected boolean ipv6;
    
    protected int port;
    
    protected int mtu = DEFAULT_MTU;
    
    protected boolean exit = false;
    
    protected PacketListener listener;
    
    protected Queue<Packet> queue = new ConcurrentLinkedQueue<Packet>();
    
    
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
        threadPool.execute(new QueueRunner(this, threadPool, queue));
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
