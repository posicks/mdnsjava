package org.xbill.mDNS.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.xbill.DNS.Options;
import org.xbill.mDNS.utils.ExecutionTimer;
import org.xbill.mDNS.utils.Executors;

public abstract class NetworkProcessor implements Runnable, Closeable
{
    public static class Packet
    {
        private final InetAddress address;
        
        private final int port;
        
        private final byte[] data;
        
        protected static int sequence;
        
        protected int id;
        
        protected ExecutionTimer timer = new ExecutionTimer();
        
        
        protected Packet(final DatagramPacket datagram)
        {
            this(datagram.getAddress(), datagram.getPort(), datagram.getData(), datagram.getOffset(), datagram.getLength());
        }
        
        
        protected Packet(final InetAddress address, final int port, final byte[] data, final int offset, final int length)
        {
            id = Packet.sequence++ ;
            this.address = address;
            this.port = port;
            this.data = data;
        }
        
        
        public InetAddress getAddress()
        {
            return address;
        }
        
        
        public byte[] getData()
        {
            return data;
        }
        
        
        public int getPort()
        {
            return port;
        }
        
        
        public SocketAddress getSocketAddress()
        {
            return new InetSocketAddress(address, port);
        }
    }
    
    
    public static interface PacketListener
    {
        void packetReceived(Packet packet);
    }
    
    
    protected static class PacketRunner implements Runnable
    {
        private static long lastPacket = -1;
        
        PacketListener dispatcher;
        
        private final Packet[] packets;
        
        
        protected PacketRunner(final PacketListener dispatcher, final Packet... packets)
        {
            this.dispatcher = dispatcher;
            this.packets = packets;
            if (lastPacket <= 0)
            {
                lastPacket = System.currentTimeMillis();
            }
        }
        
        
        public void run()
        {
            if (verboseLogging)
            {
                System.out.println("Running " + packets.length + " on a single thread");
            }
            lastPacket = System.currentTimeMillis();
            
            PacketListener dispatcher = this.dispatcher;
            for (Packet packet : packets)
            {
                try
                {
                    if (verboseLogging)
                    {
                        double took = packet.timer.took(TimeUnit.MILLISECONDS);
                        System.err.println("NetworkProcessor took " + took + " milliseconds to start packet " + packet.id + ".");
                        ExecutionTimer._start();
                        System.err.println("-----> Dispatching Packet " + packet.id + " <-----");
                    }
                    dispatcher.packetReceived(packet);
                    if (verboseLogging)
                    {
                        System.err.println("Packet " + packet.id + " took " + ExecutionTimer._took(TimeUnit.MILLISECONDS) + " milliseconds to be dispatched to Listeners.");
                    }
                } catch (Throwable e)
                {
                    System.err.println("Error dispatching data packet - " + e.getMessage());
                    e.printStackTrace(System.err);
                }
            }
        }
    }
    
    // Normally MTU size is 1500, but can be up to 9000 for jumbo frames.
    public static final int DEFAULT_MTU = 1500;
    
    public static final int AVERAGE_QUEUE_THRESHOLD = 2;
    
    public static final int MAX_QUEUE_THRESHOLD = 10;
    
    public static final int PACKET_MONITOR_NO_PACKET_RECEIVED_TIMEOUT = 100000;
    
    protected static boolean verboseLogging = false;

    private static ScheduledExecutorService defaultScheduledExecutor = Executors.getDefaultScheduledExecutor();
    
    private static ThreadPoolExecutor defaultNetworkExecutor = Executors.getDefaultNetworkExecutor();
    
    protected InetAddress ifaceAddress;
    
    protected InetAddress address;
    
    protected boolean ipv6;
    
    protected int port;
    
    protected int mtu = DEFAULT_MTU;
    
    protected transient boolean exit = false;
    
    protected PacketListener listener;
    
    protected boolean threadMonitoring = false;
    
    protected Thread monitorThread = null;
    
    protected ScheduledExecutorService scheduledExecutor = defaultScheduledExecutor;

    protected ThreadPoolExecutor networkExecutor = defaultNetworkExecutor;
    
    
    public NetworkProcessor(final InetAddress ifaceAddress, final InetAddress address, final int port, final PacketListener listener)
    throws IOException
    {
        /*
         * Remove When done developing and testing
        Options.set("mdns_cache_verbose");
        Options.set("cache_verbose");
        Options.set("mdns_network_verbose");
        Options.set("network_verbose");
        Options.set("mdns_network_thread_monitor");
         */
        verboseLogging = Options.check("mdns_network_verbose") || Options.check("network_verbose") || Options.check("mdns_verbose") || Options.check("verbose");
        threadMonitoring = Options.check("mdns_network_thread_monitor");
        
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
                verboseLogging = Options.check("mdns_network_verbose") || Options.check("network_verbose") || Options.check("mdns_verbose") || Options.check("verbose");
            }
        }, 1, 1, TimeUnit.MINUTES);
    }
    
    
    public void close()
    throws IOException
    {
        exit = true;
    }
    
    
    public InetAddress getAddress()
    {
        return address;
    }
    
    
    public InetAddress getInterfaceAddress()
    {
        return ifaceAddress;
    }
    
    
    public int getMTU()
    {
        return mtu;
    }
    
    
    public int getPort()
    {
        return port;
    }
    
    
    public boolean isIPv4()
    {
        return !ipv6;
    }
    
    
    public boolean isIPv6()
    {
        return ipv6;
    }
    
    
    public boolean isOperational()
    {
        return !exit && !networkExecutor.isShutdown() && !networkExecutor.isTerminated() && !networkExecutor.isTerminating();
    }
    
    
    public abstract void send(byte[] data)
    throws IOException;
    
    
    public void setAddress(final InetAddress address)
    {
        this.address = address;
    }
    
    
    public void setInterfaceAddress(final InetAddress address)
    {
        ifaceAddress = address;
    }
    
    
    public void setPort(final int port)
    {
        this.port = port;
    }
    
    
    public void start()
    {
        exit = false;
        
        networkExecutor.execute(this);
        if (threadMonitoring)
        {
            /*
             * This thread monitors the NetworkProcessor, closing it if Packet
             * processing stops or if the Executors it relies upon
             * are shutdown or terminated by any means. An Executor is NOT used
             * so that full control of the thread can be retained.
             */
            Thread t = new Thread(new Runnable()
            {
                public void run()
                {
                    while ( !exit)
                    {
                        try
                        {
                            Thread.sleep(1000);
                        } catch (InterruptedException e)
                        {
                            // ignore
                        }
                        
                        if ( !exit)
                        {
                            long now = System.currentTimeMillis();
                            long lastPacket = PacketRunner.lastPacket;
                            boolean operational = isOperational();
                            if (now > (lastPacket + PACKET_MONITOR_NO_PACKET_RECEIVED_TIMEOUT))
                            {
                                String msg = "Network Processor has not received a mDNS packet in " + ((double) (now - lastPacket) / (double) 1000) + " seconds";
                                if (networkExecutor.isShutdown())
                                {
                                    msg += " - ProcessorExecutor has shutdown!";
                                } else if (networkExecutor.isTerminated())
                                {
                                    msg += " - ProcessorExecutor has terminated!";
                                } else if (networkExecutor.isTerminating())
                                {
                                    msg += " - ProcessorExecutor is terminating!";
                                }
                                System.err.println(msg);
                            }
                            
                            if ( !operational)
                            {
                                System.err.println("NetworkProcessor is NOT operational, closing it!");
                                try
                                {
                                    close();
                                } catch (IOException e)
                                {
                                    // ignore
                                }
                            }
                        }
                    }
                }
            });
            t.setName("NetworkProcessor Operation Monitor Thread");
            t.setPriority(Executors.DEFAULT_NETWORK_THREAD_PRIORITY);
            t.setDaemon(true);
            t.start();
            monitorThread = t;
        }
    }
    
    
    public static void setDefaultScheduledExecutor(ScheduledExecutorService scheduledExecutor)
    {
        if (scheduledExecutor != null)
        {
            defaultScheduledExecutor = scheduledExecutor;
        }
    }
    
    
    public static void setDefaultNetworkExecutor(ThreadPoolExecutor networkExecutor)
    {
        if (networkExecutor != null)
        {
            defaultNetworkExecutor = networkExecutor;
        }
    }
    
    
    public void setScheduledExecutor(ScheduledExecutorService scheduledExecutor)
    {
        if (scheduledExecutor != null)
        {
            this.scheduledExecutor  = scheduledExecutor;
        } else
        {
            this.scheduledExecutor = defaultScheduledExecutor;
        }
    }
    
    
    public void setNetworkExecutor(ThreadPoolExecutor networkExecutor)
    {
        if (networkExecutor != null)
        {
            this.networkExecutor   = networkExecutor;
        } else
        {
            this.networkExecutor = defaultNetworkExecutor;
        }
    }
}
