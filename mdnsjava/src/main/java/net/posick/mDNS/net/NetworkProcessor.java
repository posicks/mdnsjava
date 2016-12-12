package net.posick.mDNS.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.xbill.DNS.Options;

import net.posick.mDNS.utils.ExecutionTimer;
import net.posick.mDNS.utils.Executors;
import net.posick.mDNS.utils.Misc;

public abstract class NetworkProcessor implements Runnable, Closeable
{
    protected static final Logger logger = Misc.getLogger(NetworkProcessor.class.getName(), Options.check("mdns_network_verbose") || Options.check("network_verbose") || Options.check("mdns_verbose") || Options.check("dns_verbose") || Options.check("verbose"));
    
    
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
            logger.logp(Level.FINE, getClass().getName(), "run", "Running " + packets.length + " on a single thread");
            lastPacket = System.currentTimeMillis();
            
            PacketListener dispatcher = this.dispatcher;
            for (Packet packet : packets)
            {
                try
                {
                    if (logger.isLoggable(Level.FINE))
                    {
                        double took = packet.timer.took(TimeUnit.MILLISECONDS);
                        logger.logp(Level.FINE, getClass().getName(), "run", "NetworkProcessor took " + took + " milliseconds to start packet " + packet.id + ".");
                        ExecutionTimer._start();
                        logger.logp(Level.FINE, getClass().getName(), "run", "-----> Dispatching Packet " + packet.id + " <-----");
                    }
                    dispatcher.packetReceived(packet);
                    if (logger.isLoggable(Level.FINE))
                    {
                        logger.logp(Level.FINE, getClass().getName(), "run", "Packet " + packet.id + " took " + ExecutionTimer._took(TimeUnit.MILLISECONDS) + " milliseconds to be dispatched to Listeners.");
                    }
                } catch (Throwable e)
                {
                    logger.log(Level.WARNING, "Error dispatching data packet - " + e.getMessage(), e);
                }
            }
        }
    }
    
    // Normally MTU size is 1500, but can be up to 9000 for jumbo frames.
    public static final int DEFAULT_MTU = 1500;
    
    public static final int AVERAGE_QUEUE_THRESHOLD = 2;
    
    public static final int MAX_QUEUE_THRESHOLD = 10;
    
    public static final int PACKET_MONITOR_NO_PACKET_RECEIVED_TIMEOUT = 100000;
    
    protected Executors executors = Executors.newInstance();
    
    protected InetAddress ifaceAddress;
    
    protected InetAddress address;
    
    protected boolean ipv6;
    
    protected int port;
    
    protected int mtu = DEFAULT_MTU;
    
    protected transient boolean exit = false;
    
    protected PacketListener listener;
    
    protected boolean threadMonitoring = false;
    
    protected Thread networkReadThread = null;
    
    
    public NetworkProcessor(final InetAddress ifaceAddress, final InetAddress address, final int port, final PacketListener listener)
    throws IOException
    {
        threadMonitoring = Options.check("mdns_network_thread_monitor");
        
        setInterfaceAddress(ifaceAddress);
        this.address = address;
        setPort(port);
        
        if (ifaceAddress.getAddress().length != address.getAddress().length)
        {
            throw new IOException("Interface Address and bind address bust be the same IP specifciation!");
        }
        
        ipv6 = address.getAddress().length > 4;
        
        this.listener = listener;
    }
    
    
    public void close()
    throws IOException
    {
        if (threadMonitoringFuture != null)
        {
            threadMonitoringFuture.cancel(true);
        }
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
        return !exit && executors.isNetworkExecutorOperational();
    }
    
    
    public abstract void send(byte[] data)
    throws IOException;
    
    
    public void setInterfaceAddress(final InetAddress address)
    {
        ifaceAddress = address;
    }
    
    
    public void setPort(final int port)
    {
        this.port = port;
    }
    
    private ScheduledFuture<?> threadMonitoringFuture;
    
    public void start()
    {
        exit = false;
        
        /*
         * This scheduled task monitors the NetworkProcessor, closing it if Packet
         * processing stops or if the Executors it relies upon
         * are shutdown or terminated by any means.
         */
        if (threadMonitoring)
        {
            threadMonitoringFuture = executors.schedule(new Runnable()
            {
                public void run()
                {
                    if (!exit)
                    {
                        long now = System.currentTimeMillis();
                        long lastPacket = PacketRunner.lastPacket;
                        boolean operational = isOperational();
                        if (now > (lastPacket + PACKET_MONITOR_NO_PACKET_RECEIVED_TIMEOUT))
                        {
                            String msg = "Network Processor has not received a mDNS packet in " + ((double) (now - lastPacket) / (double) 1000) + " seconds";
                            if (!executors.isNetworkExecutorOperational())
                            {
                                msg += " - NetworkProcessorExecutor has shutdown!";
                            }
                            logger.logp(Level.WARNING, getClass().getPackage().getName() + ".ThreadMonitor", "run", msg);
                        }
                        
                        if (!operational)
                        {
                            logger.logp(Level.WARNING, getClass().getPackage().getName() + ".ThreadMonitor", "run", "NetworkProcessor is NOT operational, closing it!");
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
            }, 1, TimeUnit.SECONDS);
        }

        Thread t = new Thread(this);
        t.setName("NetworkProcessor IO Read Thread");
        t.setPriority(Executors.DEFAULT_NETWORK_THREAD_PRIORITY);
        t.setDaemon(true);
        t.start();
        networkReadThread = t;
    }
}
