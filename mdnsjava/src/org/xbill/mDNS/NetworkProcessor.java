package org.xbill.mDNS;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.xbill.DNS.Options;

public abstract class NetworkProcessor implements Runnable, Closeable
{
    // Normally MTU size is 1500, but can be up to 9000 for jumbo frames.
    public static final int DEFAULT_MTU = 1500;

    public static final int AVERAGE_QUEUE_THRESHOLD = 2;

    public static final int MAX_QUEUE_THRESHOLD = 10;

    private static final int CORE_PROCESSOR_THREAD = 1;

    private static final int MAX_PROCESSOR_THREAD = 5;
    
    
    protected static interface PacketListener
    {
        void packetReceived(Packet packet);
    }
    
    
    protected static class Packet
    {
        private InetAddress address;
        
        private int port;
        
        private byte[] data;
        
        protected static int sequence;
        
        protected int id;
        
        protected ExecutionTimer timer = new ExecutionTimer();
        
        
        protected Packet(DatagramPacket datagram)
        {
            this(datagram.getAddress(), datagram.getPort(), datagram.getData(), datagram.getOffset(), datagram.getLength());
        }
        
        
        protected Packet(InetAddress address, int port, byte[] data, int offset, int length)
        {
            this.id = Packet.sequence++;
            this.address = address;
            this.port = port;
            this.data = data;
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
    
    protected ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(1, new ThreadFactory()
    {
        public Thread newThread(Runnable r)
        {
            Thread t = new Thread(r, "Network Processor Scheduled Thread");
            t.setDaemon(true);
            return t;
        }
    });

    
    protected ThreadPoolExecutor processorExecutor = new ThreadPoolExecutor(CORE_PROCESSOR_THREAD, MAX_PROCESSOR_THREAD, 0L, TimeUnit.MILLISECONDS,
    new LinkedBlockingQueue<Runnable>(), new ThreadFactory()
    {
        public Thread newThread(Runnable r)
        {
            Thread t = new Thread(r, "Network Queue Processing Thread");
            t.setDaemon(true);
            return t;
        }
    });
    
    
    protected static class PacketRunner implements Runnable
    {
        private PacketListener dispatcher;
        
        private Packet[] packets;
        
        
        protected PacketRunner(PacketListener dispatcher, Packet... packets)
        {
            this.dispatcher = dispatcher;
            this.packets = packets;
        }
        
        
        public void run()
        {
            if (Options.check("mdns_verbose") || Options.check("mdns_packet_verbose"))
            {
                System.err.println("Running " + packets.length + " on a single thread");
            }
            
            PacketListener dispatcher = this.dispatcher;
            for (Packet packet : packets)
            {
                try
                {
                    boolean log = Options.check("mdns_verbose") || Options.check("mdns_packet_verbose");
                    if (log)
                    {
                        double took = packet.timer.took(TimeUnit.MILLISECONDS);
                        System.out.println("ProcessingRunner took " + took + " milliseconds to start packet " + packet.id + ".");
                        took = packet.timer.took(TimeUnit.MILLISECONDS);
                        System.out.println("Processing packet " + packet.id + " took " + took + " to be executed by the PacketRunner.");
                        ExecutionTimer._start();
                    }
                    dispatcher.packetReceived(packet);
                    if (log)
                    {
                        System.out.println("Packet " + packet.id + " took " + ExecutionTimer._took(TimeUnit.MILLISECONDS) + " to be processed by dispatched to Listeners.");
                    }
                } catch (Throwable e)
                {
                    System.err.println("Error dispatching data packet - " + e.getMessage());
                    e.printStackTrace(System.err);
                }
            }
        }
    }
    
    
    protected static class ThreadPoolAdjuster implements Runnable
    {
        private ThreadPoolExecutor processorExecutor;
        
        private int[] queueSizeHistory = new int[60];
        
        private int start = 0;
        
        private int end = 0;
        
        private int maxQueueSize = 0;
        
        private long lastThreadPoolAdjustment = 0;

        
        protected ThreadPoolAdjuster(ThreadPoolExecutor processorExecutor)
        {
            this.processorExecutor = processorExecutor;
        }
        
        
        public void check(int size)
        {
            if (size > maxQueueSize)
            {
                maxQueueSize = size;
            }
        }
        
        
        public void run()
        {
            remember(maxQueueSize);
            
            int totalQueueSize = 0;
            int averageQueueSize = 0;
            int maxQueueSize = 0;
            int[] history = getHistory();
            for (int queueSize : history)
            {
                totalQueueSize += queueSize;
                if (queueSize > maxQueueSize)
                {
                    maxQueueSize = queueSize;
                }
            }
            averageQueueSize = totalQueueSize / history.length;
            
            boolean log = Options.check("mdns_packet_verbose");
            if (log)
            {
                System.err.println("Max Queue Size:" + maxQueueSize + ", Total Queue Size: " + totalQueueSize + ", Average Queue Size: " + averageQueueSize);
            }
            
            long now;
            if ((now = System.currentTimeMillis()) > lastThreadPoolAdjustment + 60000)
            {
                lastThreadPoolAdjustment = now;
                if (averageQueueSize > AVERAGE_QUEUE_THRESHOLD || maxQueueSize > MAX_QUEUE_THRESHOLD)
                {
                    int newCorePoolSize = processorExecutor.getCorePoolSize() + 1;
                    int newMaxPoolSize = processorExecutor.getMaximumPoolSize() + 1;
                    processorExecutor.setCorePoolSize(newCorePoolSize);
                    processorExecutor.setMaximumPoolSize(newMaxPoolSize);
                    if (log)
                    {
                        System.err.println("Increasing Thread Pool Size to " + processorExecutor.getCorePoolSize() + " core threads & " + processorExecutor.getMaximumPoolSize() + " max threads.");
                    }
                } else if (averageQueueSize < AVERAGE_QUEUE_THRESHOLD && maxQueueSize < MAX_QUEUE_THRESHOLD && 
                          (processorExecutor.getCorePoolSize() > CORE_PROCESSOR_THREAD || processorExecutor.getMaximumPoolSize() > MAX_PROCESSOR_THREAD))
                {
                    int newCorePoolSize = processorExecutor.getCorePoolSize() - 1;
                    int newMaxPoolSize = processorExecutor.getMaximumPoolSize() - 1;
                    processorExecutor.setCorePoolSize(newCorePoolSize < CORE_PROCESSOR_THREAD ? CORE_PROCESSOR_THREAD : newCorePoolSize);
                    processorExecutor.setMaximumPoolSize(newMaxPoolSize < MAX_PROCESSOR_THREAD ? MAX_PROCESSOR_THREAD : newMaxPoolSize);
                    if (log)
                    {
                        System.err.println("Decreasing Thread Pool Size to " + processorExecutor.getCorePoolSize() + " core threads & " + processorExecutor.getMaximumPoolSize() + " max threads.");
                    }
                }
            }
            
            this.maxQueueSize = 0;
        }
        
        
        public int[] getHistory()
        {
            int[] history = new int[(end > start ? end - start : queueSizeHistory.length)];
            int length = 0;
            
            length = (end > start ? end : queueSizeHistory.length) - start;
            System.arraycopy(queueSizeHistory, start, history, 0, length);
            
            if (end < start)
            {
                System.arraycopy(queueSizeHistory, 0, history, length, end);
            }
            
            return history;
        }
        
        
        private final void remember(int size)
        {
            queueSizeHistory[end++] = size;
            if (end >= queueSizeHistory.length)
            {
                end = 0;
            }
            
            if (start == end)
            {
                start++;
                if (start >= queueSizeHistory.length)
                {
                    start = 0;
                }
            }
        }
    }
    
    
    protected static class QueueRunner implements Runnable
    {
        private NetworkProcessor networkProcessor;
        
        private Executor executor;
        
        private Queue<Packet> queue;
        
        private ThreadPoolAdjuster queueChecker;
        
        private long lastRun = System.currentTimeMillis();

        
        protected QueueRunner(NetworkProcessor networkProcessor, Executor executor, Queue<Packet> queue, ThreadPoolAdjuster queueChecker)
        {
            this.networkProcessor = networkProcessor;
            this.executor = executor;
            this.queue = queue;
            this.queueChecker = queueChecker;
        }
        
        
        public void run()
        {
            if (Options.check("mdns_packet_verbose"))
            {
                long now = System.currentTimeMillis();
                long time = now - lastRun;
                if (time > 20)
                {
                    System.out.println("-----> QueueRunner last run " + time + " milliseconds ago. <-----");
                }
                lastRun = now;
            }
            
            Packet packet = null;
            
            int packetCount = 0;
            while ((packet = queue.poll()) != null)
            {
                packetCount++;
                if (Options.check("mdns_packet_verbose"))
                {
                    double took = packet.timer.took(TimeUnit.MILLISECONDS);
                    System.out.println("Packet \"" + packet.id + "\" took " + took + " to be popped from queue.");
                    packet.timer.start();
                }
                executor.execute(new PacketRunner(networkProcessor.listener, packet));
            }
            
            if (Options.check("mdns_packet_verbose"))
            {
                queueChecker.check(packetCount);
            }
            
            try
            {
                Thread.sleep(10);
            } catch (InterruptedException e)
            {
                // ignore
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
// TO DO: Remove when done testing
//Options.set("mdns_packet_verbose");
        setInterfaceAddress(ifaceAddress);
        setAddress(address);
        setPort(port);
        
        if (ifaceAddress.getAddress().length != address.getAddress().length)
        {
            throw new IOException("Interface Address and bind address bust be the same IP specifciation!");
        }
        
        ipv6 = address.getAddress().length > 4;
        
        this.listener = listener;
        ThreadPoolAdjuster queueChecker = new ThreadPoolAdjuster(processorExecutor);
        scheduledExecutor.scheduleAtFixedRate(new QueueRunner(this, processorExecutor, queue, queueChecker), 10, 10, TimeUnit.MILLISECONDS);
        if (Options.check("mdns_packet_verbose"))
        {
            scheduledExecutor.scheduleAtFixedRate(queueChecker, 1, 1, TimeUnit.SECONDS);
        }
    }
    
    
    public abstract void send(byte[] data)
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
}
