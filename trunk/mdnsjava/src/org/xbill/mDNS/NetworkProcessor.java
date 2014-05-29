package org.xbill.mDNS;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
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

    private static final int CORE_PROCESSOR_THREAD = 5;

    private static final int MAX_PROCESSOR_THREAD = 10;
    
    
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
            t.setPriority(Thread.MAX_PRIORITY - 1);
            t.setContextClassLoader(NetworkProcessor.class.getClassLoader());
            return t;
        }
    });

    
    protected ThreadPoolExecutor processorExecutor = null;
    
    
    protected static class PacketRunner implements Runnable
    {
        private PacketListener dispatcher;
        
        private Packet[] packets;

        private boolean verboseLogging = false;
        
        
        protected PacketRunner(PacketListener dispatcher, Packet... packets)
        {
            this.dispatcher = dispatcher;
            this.packets = packets;
            this.verboseLogging = Options.check("mdns_verbose") || Options.check("mdns_packet_verbose");
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
                    if (verboseLogging)
                    {
                        double took = packet.timer.took(TimeUnit.MILLISECONDS);
                        System.out.println("ProcessingRunner took " + took + " milliseconds to start packet " + packet.id + ".");
                        took = packet.timer.took(TimeUnit.MILLISECONDS);
                        System.out.println("Processing packet " + packet.id + " took " + took + " to be executed by the PacketRunner.");
                        ExecutionTimer._start();
                        System.err.println("-----> Running Packet " + packet.id + " <-----");
                    }
                    dispatcher.packetReceived(packet);
                    if (verboseLogging)
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
    
    
    /*
    protected class QueueRunner implements Runnable
    {
        private long lastRun = System.currentTimeMillis();
        
        private boolean verboseLogging = false;

        
        protected QueueRunner()
        {
            verboseLogging = Options.check("mdns_network_verbose") || Options.check("network_verbose") ||
                             Options.check("mdns_verbose") || Options.check("verbose");
        }
        
        
        public void run()
        {
            while (!exit)
            {
                if (verboseLogging)
                {
                    long now = System.currentTimeMillis();
                    long time = now - lastRun;
                    if (time > 50)
                    {
                        System.out.println("-----> QueueRunner last run " + time + " milliseconds ago. <-----");
                    }
                    lastRun = now;
                }
                
                Packet packet = null;
                
                while ((packet = queue.poll()) != null)
                {
                    if (verboseLogging)
                    {
                        double took = packet.timer.took(TimeUnit.MILLISECONDS);
                        System.out.println("Packet \"" + packet.id + "\" took " + took + " to be popped from queue.");
                        packet.timer.start();
                        System.err.println("-----> Passing packet " + packet.id + " onto PacketRunner <-----");
                    }
                    
                    try
                    {
                        if (verboseLogging)
                        {
                            double took = packet.timer.took(TimeUnit.MILLISECONDS);
                            System.out.println("ProcessingRunner took " + took + " milliseconds to start packet " + packet.id + ".");
                            took = packet.timer.took(TimeUnit.MILLISECONDS);
                            System.out.println("Processing packet " + packet.id + " took " + took + " to be executed by the PacketRunner.");
                            ExecutionTimer._start();
                        }
                        if (verboseLogging)
                        {
                            System.err.println("-----> Dispatching packet " + packet.id + " <-----");
                        }
                        processorExecutor.execute(new PacketRunner(listener, packet));
                        if (verboseLogging)
                        {
                            System.out.println("Packet " + packet.id + " took " + ExecutionTimer._took(TimeUnit.MILLISECONDS) + " to be processed by dispatched to Listeners.");
                        }
                    } catch (Throwable e)
                    {
                        System.err.println("Error dispatching data packet - " + e.getMessage());
                        e.printStackTrace(System.err);
                    }
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
    }
*/
    
    protected InetAddress ifaceAddress;
    
    protected InetAddress address;
    
    protected boolean ipv6;
    
    protected int port;
    
    protected int mtu = DEFAULT_MTU;
    
    protected boolean exit = false;
    
    protected PacketListener listener;
    
    protected Queue<Packet> queue = new ConcurrentLinkedQueue<Packet>();
    
    protected boolean verboseLogging = false; 
    
    
    public NetworkProcessor(InetAddress ifaceAddress, InetAddress address, int port, PacketListener listener)
    throws IOException
    {
        verboseLogging = Options.check("mdns_network_verbose") || Options.check("network_verbose") ||
                         Options.check("mdns_verbose") || Options.check("verbose");
        
        setInterfaceAddress(ifaceAddress);
        setAddress(address);
        setPort(port);
        
        if (ifaceAddress.getAddress().length != address.getAddress().length)
        {
            throw new IOException("Interface Address and bind address bust be the same IP specifciation!");
        }
        
        ipv6 = address.getAddress().length > 4;
        
        this.listener = listener;
        scheduledExecutor.scheduleAtFixedRate(new Runnable()
        {
            public void run()
            {
                verboseLogging = Options.check("mdns_network_verbose") || Options.check("network_verbose") ||
                                 Options.check("mdns_verbose") || Options.check("verbose");
            }
        }, 1, 1, TimeUnit.MINUTES);
    }
    
    
    public void start()
    {
        exit = false;
        
        processorExecutor = new ThreadPoolExecutor(CORE_PROCESSOR_THREAD, MAX_PROCESSOR_THREAD, 0L, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<Runnable>(), new ThreadFactory()
        {
            public Thread newThread(Runnable r)
            {
                Thread t = new Thread(r, "Network Queue Processing Thread");
                t.setDaemon(false);
                t.setPriority(Thread.MAX_PRIORITY - 1);
                t.setContextClassLoader(NetworkProcessor.class.getClassLoader());
                return t;
            }
        });
        
//        processorExecutor.execute(new QueueRunner(/*, queueChecker*/));
        processorExecutor.execute(this);
    }
    
    
    public void close()
    throws IOException
    {
        exit = true;
        processorExecutor.shutdownNow();
        try
        {
            processorExecutor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e)
        {
            System.err.println("Timeout occured while awaiting the Network Processor Executor to shutdown.");
        }
    }
    
    
    public abstract void send(byte[] data)
    throws IOException;
    
    
    public int getMTU()
    {
        return mtu;
    }
    
    
    public boolean isOperational()
    {
        return !exit && !processorExecutor.isShutdown() && 
               !processorExecutor.isTerminated() && !processorExecutor.isTerminating() &&
               !scheduledExecutor.isShutdown() && !scheduledExecutor.isTerminated();
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
