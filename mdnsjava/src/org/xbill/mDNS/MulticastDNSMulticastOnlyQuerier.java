package org.xbill.mDNS;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.xbill.DNS.Cache;
import org.xbill.DNS.Credibility;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Header;
import org.xbill.DNS.Message;
import org.xbill.DNS.MulticastDNSUtils;
import org.xbill.DNS.Name;
import org.xbill.DNS.OPTRecord;
import org.xbill.DNS.Opcode;
import org.xbill.DNS.Options;
import org.xbill.DNS.RRset;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.ResolverListener;
import org.xbill.DNS.Section;
import org.xbill.DNS.SetResponse;
import org.xbill.DNS.TSIG;
import org.xbill.DNS.WireParseException;
import org.xbill.mDNS.MulticastDNSCache.CacheMonitor;
import org.xbill.mDNS.NetworkProcessor.Packet;

/**
 * Implements the Multicast DNS portions of the MulticastDNSQuerier in accordance to RFC 6762.
 * 
 * The MulticastDNSMulticastOnlyQuerier is used by the MulticastDNSQuerier to issue multicast DNS 
 * requests.  Clients should use the MulticastDNSQuerier when issuing DNS/mDNS queries, as 
 * Unicast DNS queries will be sent via unicast UDP, and Multicast DNS queries will be sent via 
 * multicast UDP.
 * 
 * This class may be used if a client wishes to only send requests via multicast UDP.
 * 
 * @author Steve Posick
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class MulticastDNSMulticastOnlyQuerier implements Querier, NetworkProcessor.PacketListener
{
    /** The default EDNS payload size */
    public static final int DEFAULT_EDNS_PAYLOADSIZE = 1280;
    
    
    /**
     * Resolver Listener used cache responses received from the network.
     * 
     * @author Steve Posick
     */
    protected class Cacher implements ResolverListener
    {
        public void receiveMessage(Object id, Message message)
        {
            Header header = message.getHeader();
            int rcode = message.getRcode();
            int opcode = header.getOpcode();
            
            if (ignoreTruncation && header.getFlag(Flags.TC))
            {
                System.err.println("Truncated Message Ignored : " + "RCode: " + Rcode.string(rcode) + "; Opcode: " + Opcode.string(opcode));
                return;
            }

            switch (opcode)
            {
                case Opcode.IQUERY :
                case Opcode.QUERY :
                case Opcode.NOTIFY :
                case Opcode.STATUS :
                    if (header.getFlag(Flags.QR) || header.getFlag(Flags.AA))
                    {
                        updateCache(MulticastDNSUtils.extractRecords(message, Section.ANSWER, Section.AUTHORITY, Section.ADDITIONAL), Credibility.NONAUTH_AUTHORITY);
                    } else
                    {
                        return;
                    }
                    break;
                case Opcode.UPDATE :
                    // We do not allow updates from the network!
                    System.err.println("-----> We do not allow updates from the network! <-----");
                    return;
            }
            
            if (Options.check("mdns_verbose"))
            {
                System.out.println("RCode: " + Rcode.string(rcode));
                System.out.println("Opcode: " + Opcode.string(opcode));
            }
        }
        

        public void handleException(Object id, Exception e)
        {
        }
    }
    
    
    /**
     * Resolver Listener that replies to queries from the network.
     * 
     * @author Steve Posick
     */
    public class MulticastDNSResponder implements ResolverListener
    {
        public MulticastDNSResponder()
        throws IOException
        {
        }
        
        
        public void receiveMessage(Object id, Message message)
        {
            int rcode = message.getRcode();
            Header header = message.getHeader();
            int opcode =header.getOpcode();
            
            if (header.getFlag(Flags.QR) || header.getFlag(Flags.AA))
            {
                return;
            }
            
            if (header.getFlag(Flags.TC))
            {
                if (ignoreTruncation)
                {
                    System.err.println("Truncated Message : " + "RCode: " + Rcode.string(rcode) + "; Opcode: " + Opcode.string(opcode) + " - Ignoring subsequent known answer records.");
                    return;
                } else
                {
                    // TODO: Implement the reception of truncated packets. (wait 400 to 500 milliseconds for more known answers) 
                }
            }
            
            if (Options.check("mdns_verbose"))
            {
                System.out.println("RCode: " + Rcode.string(rcode));
                System.out.println("Opcode: " + Opcode.string(opcode));
            }
            
            try
            {
                switch (opcode)
                {
                    case Opcode.IQUERY :
                    case Opcode.QUERY :
                        Message response = cache.queryCache(message, Credibility.AUTH_AUTHORITY);
                        
                        if (response != null)
                        {
                            Header responseHeader = response.getHeader();
                            if (responseHeader.getCount(Section.ANSWER) > 0 ||
                                responseHeader.getCount(Section.AUTHORITY) > 0 ||
                                responseHeader.getCount(Section.ADDITIONAL) > 0)
                            {
                                if (Options.check("mdns_verbose"))
                                {
                                    System.out.println("Query Reply ID: " + id + "\n" + response);
                                }
                                responseHeader.setFlag(Flags.AA);
                                responseHeader.setFlag(Flags.QR);
//System.out.println("-----> Writing Response <-----\nQuery:\n" + message + "\nResponse:\n" + response);
                                writeResponse(response);
                            } else
                            {
                                if (Options.check("mdns_verbose"))
                                {
                                    System.out.println("No response, client knows answer.");
                                }
                            }
                        }
                        break;
                    case Opcode.NOTIFY :
                    case Opcode.STATUS :
                    case Opcode.UPDATE :
                        System.out.println("Received Invalid Request - Opcode: " + Opcode.string(opcode));
                        break;
                }
            } catch (Exception e)
            {
                System.err.println("Error replying to query - " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }

        
        public void handleException(Object id, Exception e)
        {
        }
    }
    
    
    public class ListenerWrapper implements ResolverListener
    {
        private Object id;
        
        private Message query;
        
        private ResolverListener listener;
        
        
        public ListenerWrapper(Object id, Message query, ResolverListener listener)
        {
            this.id = id;
            this.query = query;
            this.listener = listener;
        }
        
        
        
        public void receiveMessage(Object id, Message m)
        {
            Header h = m.getHeader();
            if (h.getFlag(Flags.QR) || h.getFlag(Flags.AA) || h.getFlag(Flags.AD))
            {
                if (MulticastDNSUtils.answersAny(query, m))
                {
                    listener.receiveMessage(this.id, m);
                    unregisterListener(this);
                }
            } else
            {
                return;
            }
        }

        
        public void handleException(Object id, Exception e)
        {
            if (this.id == null || this.id.equals(id))
            {
                listener.handleException(this.id, e);
                unregisterListener(this);
            }
        }
        
        
        
        public int hashCode()
        {
            return listener.hashCode();
        }
        
        
        
        public boolean equals(Object o)
        {
            if (this == o || this.listener == o)
            {
                return true;
            } else if (o instanceof ListenerWrapper)
            {
                return this.listener == ((ListenerWrapper) o).listener;
            }
            
            return false;
        }
    }
    
    
    protected ExecutorService threadPool = Executors.newCachedThreadPool(new ThreadFactory()
    {
        public Thread newThread(Runnable r)
        {
            Thread t = new Thread(r, "mDNSResolver Pool Thread");
            t.setDaemon(true);
            return t;
        }
    });
    
    protected ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(1, new ThreadFactory()
    {
        public Thread newThread(Runnable r)
        {
            Thread t = new Thread(r, "mDNSResolver Scheduled Thread");
            t.setDaemon(true);
            return t;
        }
    });
    
    protected ListenerProcessor<ResolverListener> resolverListenerProcessor = new ListenerProcessor<ResolverListener>(ResolverListener.class);
    
    protected ResolverListener resolverListenerDispatcher = resolverListenerProcessor.getDispatcher();
    
    protected MulticastDNSCache cache;
    
    protected Cacher cacher;
    
    protected MulticastDNSResponder responder;
    
    protected InetAddress multicastAddress;
    
    protected int port = MulticastDNSService.DEFAULT_PORT;
    
    protected OPTRecord queryOPT;
    
    protected TSIG tsig;
    
    protected boolean ignoreTruncation = false;
    
    protected long timeoutValue = DEFAULT_TIMEOUT;
    
    protected long responseWaitTime = DEFAULT_RESPONSE_WAIT_TIME;
    
    protected long retryInterval = DEFAULT_RETRY_INTERVAL;
    
    protected List<DatagramProcessor> multicastProcessors = new ArrayList<DatagramProcessor>();
    
//TODO:    protected UnicastProcessor unicastProcessor;
    
    protected List<Thread> multicastReceiverThreads = new ArrayList<Thread>();
    
    protected List<Thread> unicastReceiverThreads = new ArrayList<Thread>();
    

    private CacheMonitor cacheMonitor = new CacheMonitor()
    {
        private List authRecords = new ArrayList();
        
        private List nonauthRecords = new ArrayList();
        
        private long lastPoll = -1;
        
        
        public void begin()
        {
            if (Options.check("mdns_verbose") || Options.check("mdns_cache_verbose"))
            {
                System.out.print("!!!! ");
                if (lastPoll > 0)
                {
                    System.out.print("Last Poll " + (double) ((double) (System.nanoTime() - lastPoll) / (double) 1000000000) + " seconds ago. ");
                }
                System.out.print(" Cache Monitor Check ");
            }
            lastPoll = System.nanoTime();
            
            authRecords.clear();
            nonauthRecords.clear();
        }
        
        
        public void check(RRset rrs, int credibility, int expiresIn)
        {
            if (Options.check("mdns_verbose") || Options.check("mdns_cache_verbose"))
            {
                System.out.println("CacheMonitor check RRset: expires in: " + expiresIn + " seconds : " + rrs);
            }
            long ttl = rrs.getTTL();
            
            // Update expiry of records in accordance to RFC 6762 Section 5.2
            if (credibility >= Credibility.AUTH_AUTHORITY)
            {
                if (isAboutToExpire(ttl, expiresIn))
                {
                    Record[] records = MulticastDNSUtils.extractRecords(rrs);
                    for (Record record : records)
                    {
                        try
                        {
                            MulticastDNSUtils.setTLLForRecord(record, ttl);
                            this.authRecords.add(record);
                        } catch (Exception e)
                        {
                            System.err.println(e.getMessage());
                            e.printStackTrace(System.err);
                        }
                    }
                }
            }
        }
        
        
        public void expired(RRset rrs, int credibility)
        {
            if (Options.check("mdns_verbose") || Options.check("mdns_cache_verbose"))
            {
                System.out.println("CacheMonitor RRset expired : " + rrs);
            }
            
            List<Record> list;
            if (credibility >= Credibility.AUTH_AUTHORITY)
            {
                list = this.authRecords;
            } else
            {
                list = this.nonauthRecords;
                // TODO: Notify local clients, not Network!
            }
            
            Record[] records = MulticastDNSUtils.extractRecords(rrs);
            if (records != null && records.length > 0)
            {
                for (int i = 0; i < records.length; i++)
                {
                    try
                    {
                        MulticastDNSUtils.setTLLForRecord(records[i], 0);
                        list.add(records[i]);
                    } catch (Exception e)
                    {
                        System.err.println(e.getMessage());
                        e.printStackTrace(System.err);
                    }
                }
            }
        }
        
        
        public void end()
        {
            try
            {
                if (authRecords.size() > 0)
                {
                    Message m = new Message();
                    Header h = m.getHeader();
                    h.setOpcode(Opcode.UPDATE);
                    for (int index = 0; index < authRecords.size(); index++)
                    {
                        m.addRecord((Record) authRecords.get(index), Section.UPDATE);
                    }

                    if (Options.check("mdns_verbose") || Options.check("mdns_cache_verbose"))
                    {
                        System.out.println("CacheMonitor Broadcasting update for Authoritative Records:\n" + m);
                    }
                    broadcast(m, false);
                }
                
                // Notify Local client of expired records
                if (nonauthRecords.size() > 0)
                {
                    Message m = new Message();
                    Header h = m.getHeader();
                    h.setOpcode(Opcode.QUERY);
                    h.setFlag(Flags.QR);
                    for (int index = 0; index < nonauthRecords.size(); index++)
                    {
                        m.addRecord((Record) nonauthRecords.get(index), Section.UPDATE);
                    }

                    if (Options.check("mdns_verbose") || Options.check("mdns_cache_verbose"))
                    {
                        System.out.println("CacheMonitor Locally Broadcasting Non-Authoritative Records:\n" + m);
                    }
                    resolverListenerProcessor.getDispatcher().receiveMessage(h.getID(), m);
                }
            } catch (IOException e)
            {
                IOException ioe = new IOException("Exception \"" + e.getMessage() + "\" occured while refreshing cached entries.");
                ioe.setStackTrace(e.getStackTrace());
                resolverListenerDispatcher.handleException("", ioe);
                
                if (Options.check("mdns_verbose"))
                {
                    System.err.println(e.getMessage());
                    e.printStackTrace(System.err);
                }
            } catch (Exception e)
            {
                System.err.println(e.getMessage());
                e.printStackTrace(System.err);
            }
            
            authRecords.clear();
            nonauthRecords.clear();
        }


        protected boolean isAboutToExpire(long ttl, int expiresIn)
        {
            double percentage = (double) expiresIn / (double) ttl;
            return percentage >= .05f && percentage <= .07f ||
                   percentage >= .10f && percentage <= .12f ||
                   percentage >= .15f && percentage <= .17f ||
                   percentage >= .20f && percentage <= .22f;
        }
    };

    
    public MulticastDNSMulticastOnlyQuerier()
    throws IOException
    {
        this(false);
    }


    public MulticastDNSMulticastOnlyQuerier(boolean ipv6)
    throws IOException
    {
        this(null, InetAddress.getByName(ipv6 ? MulticastDNSService.DEFAULT_IPv6_ADDRESS : MulticastDNSService.DEFAULT_IPv4_ADDRESS));
    }
    
    
    public MulticastDNSMulticastOnlyQuerier(InetAddress ifaceAddress, InetAddress address)
    throws IOException
    {
        super();
        cache = MulticastDNSCache.DEFAULT_MDNS_CACHE;
        if (cache.getCacheMonitor() == null)
        {
            cache.setCacheMonitor(cacheMonitor);
        }
        
        // Set Address to any local address
        setAddress(address);
        
        // TODO: Re-evaluate this and make sure that The Socket Works properly!
        if (ifaceAddress != null)
        {
            this.multicastProcessors.add(new DatagramProcessor(ifaceAddress, address, port, this));
        } else
        {
            Set<InetAddress> addresses = new HashSet<InetAddress>();
            Set<String> MACs = new HashSet<String>();
            Enumeration<NetworkInterface> netIfaces = NetworkInterface.getNetworkInterfaces();
            while (netIfaces.hasMoreElements())
            {
                NetworkInterface netIface = netIfaces.nextElement();
                
                if (netIface.isUp() && !netIface.isVirtual() && !netIface.isLoopback())
                {
                    // Generate MAC
                    byte[] hwAddr = netIface.getHardwareAddress();
                    if (hwAddr != null)
                    {
                        StringBuilder builder = new StringBuilder();
                        for (byte octet : hwAddr)
                        {
                            builder.append(Integer.toHexString((octet & 0x0FF))).append(":");
                        }
                        if (builder.length() > 1)
                        {
                            builder.setLength(builder.length() - 1);
                        }
                        String mac = builder.toString();
                        
                        if (!MACs.contains(mac))
                        {
                            MACs.add(mac);
                            Enumeration<InetAddress> ifaceAddrs = netIface.getInetAddresses();
                            while (ifaceAddrs.hasMoreElements())
                            {
                                InetAddress addr = ifaceAddrs.nextElement();
                                if (address.getAddress().length == addr.getAddress().length)
                                {
//System.out.println("-----> Binding to Address " + addr + " <-----");
                                    addresses.add(addr);
                                }
                            }
                        }
                    }
                }
            }
            
            for (InetAddress ifaceAddr : addresses)
            {
                if (ifaceAddr.getAddress().length == address.getAddress().length)
                {
                    try
                    {
                        DatagramProcessor multicastProcessor = new DatagramProcessor(ifaceAddr, address, port, this);
                        this.multicastProcessors.add(multicastProcessor);
                    } catch (Exception e)
                    {
                        System.err.println("Could not bind to address \"" + ifaceAddr + "\" - " + e.getMessage());
                    }
                }
            }
        }
        
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable()
        {
            public void run()
            {
                try
                {
                    close();
                } catch (IOException e)
                {
                    // We're shutting down, who cares. Ignore
                }
            }
        }, getClass().getSimpleName() + " Shutdown Hook"));
        
        cacher = new Cacher();
        registerListener(cacher);
        
        for (DatagramProcessor multicastProcessor : multicastProcessors)
        {
            Thread multicastReceiverThread = new Thread(multicastProcessor);
            multicastReceiverThread.start();
            multicastReceiverThreads.add(multicastReceiverThread);
        }
    
        responder = new MulticastDNSResponder();
        registerListener(responder);
    }
    

    /**
     * {@inheritDoc}
     */
    public void setAddress(InetAddress address)
    {
        multicastAddress = address;
    }


    /**
     * {@inheritDoc}
     */
    
    public void setPort(int port)
    {
        this.port = port;
    }
    
    
    /**
     * {@inheritDoc}
     */
    
    public void setTCP(boolean flag)
    {
        // Not implemented.  mDNS is UDP only.
    }
    
    
    /**
     * {@inheritDoc}
     */
    
    public void setEDNS(int level)
    {
        setEDNS(level, 0, 0, null);
    }
    
    
    /**
     * {@inheritDoc}
     */
    public void setEDNS(int level, int payloadSize, int flags, List options)
    {
        if (level != 0 && level != -1)
        {
            throw new IllegalArgumentException("invalid EDNS level - " + "must be 0 or -1");
        }
        
        if (payloadSize == 0)
        {
            payloadSize = DEFAULT_EDNS_PAYLOADSIZE;
        }
        
        queryOPT = new OPTRecord(payloadSize, 0, level, flags, options);
    }
    
    
    /**
     * {@inheritDoc}
     */
    public void setTSIGKey(TSIG key)
    {
        tsig = key;
    }
    
    
    /**
     * {@inheritDoc}
     */
    public void setTimeout(int secs, int msecs)
    {
        timeoutValue = (long) secs * 1000L + (long) msecs;
    }
    
    
    /**
     * {@inheritDoc}
     */
    public void setTimeout(int secs)
    {
        setTimeout(secs, 0);
    }
    
    
    /**
     * {@inheritDoc}
     */
    public void setRetryWaitTime(int secs, int msecs)
    {
        responseWaitTime = (long) secs * 1000L + (long) msecs;
    }
    
    
    /**
     * {@inheritDoc}
     */
    public void setRetryWaitTime(int secs)
    {
        setRetryWaitTime(secs, 0);
    }


    /**
     * {@inheritDoc}
     */
    public boolean isIPv4()
    {
        for (DatagramProcessor multicastProcessor : multicastProcessors)
        {
            if (multicastProcessor.isIPv4())
            {
                return true;
            }
        }
        return false;
    }


    /**
     * {@inheritDoc}
     */
    public boolean isIPv6()
    {
        for (DatagramProcessor multicastProcessor : multicastProcessors)
        {
            if (multicastProcessor.isIPv6())
            {
                return true;
            }
        }
        return false;
    }
    
    
    /**
     * {@inheritDoc}
     */
    public Name[] getMulticastDomains()
    {
        boolean ipv4 = isIPv4();
        boolean ipv6 = isIPv6();
        
        if (ipv4 && ipv6)
        {
            return MulticastDNSService.ALL_MULTICAST_DNS_DOMAINS;
        } else if (ipv4)
        {
            return MulticastDNSService.IPv4_MULTICAST_DOMAINS;
        } else if (ipv6)
        {
            return MulticastDNSService.IPv6_MULTICAST_DOMAINS;
        } else
        {
            return new Name[0];
        }
    }
    
    
    /**
     * {@inheritDoc}
     */
    public void broadcast(final Message message, boolean addKnownAnswers)
    throws IOException
    {
        if (Options.check("mdns_verbose"))
        {
            System.out.println("Broadcasting Query to " + multicastAddress.getHostAddress() + ":" + port);
        }
        
        Header header = message.getHeader();
        boolean isUpdate = header.getOpcode() == Opcode.UPDATE;
        
        if (isUpdate)
        {
            updateCache(MulticastDNSUtils.extractRecords(message, new int[] {Section.ZONE, Section.PREREQ, Section.UPDATE, Section.ADDITIONAL}), Credibility.AUTH_AUTHORITY);
            writeMessageToWire(convertUpdateToQueryResponse(message));
        } else if (addKnownAnswers)
        {
            Message knownAnswer = cache.queryCache(message, Credibility.ANY);
            for (Integer section : new Integer[] {Section.ANSWER, Section.ADDITIONAL, Section.AUTHORITY})
            {
                Record[] records = knownAnswer.getSectionArray(section);
                if (records != null && records.length > 0)
                {
                    for (Record record : records)
                    {
                        if (!message.findRecord(record))
                        {
                            message.addRecord(record, section);
                        }
                    }
                }
            }
            
            writeMessageToWire(message/*, true*/);
        } else
        {
            writeMessageToWire(message/*, true*/);
        }
    }
    
    
    private void updateCache(Record[] records, int credibility)
    {
        if (records != null && records.length > 0)
        {
            for (int index = 0; index < records.length; index++)
            {
                Record record = records[index];
                try
                {
                    // Workaround. mDNS Uses high order DClass bit for Unicast Response OK
                    Record cacheRecord = MulticastDNSUtils.clone(record);
                    MulticastDNSUtils.setDClassForRecord(cacheRecord, cacheRecord.getDClass() & 0x7FFF);
                    if (cacheRecord.getTTL() > 0)
                    {
                        SetResponse response = cache.lookupRecords(cacheRecord.getName(), cacheRecord.getType(), Credibility.ANY);
                        RRset[] rrs = response.answers();
                        if (rrs != null && rrs.length > 0)
                        {
                            Record[] cachedRecords = MulticastDNSUtils.extractRecords(rrs);
                            if (cachedRecords != null && cachedRecords.length > 0)
                            {
                                if (Options.check("mdns_verbose"))
                                {
                                    System.out.println("Updating Cached Record: " + cacheRecord);
                                }
                                cache.updateRRset(cacheRecord, credibility);
                            }
                        } else
                        {
                            if (Options.check("mdns_verbose"))
                            {
                                System.out.println("Caching Record: " + cacheRecord);
                            }
                            cache.addRecord(cacheRecord, credibility, null);
                        }
                    } else
                    {
                        // Remove unregistered records from Cache
                        cache.removeElementCopy(cacheRecord.getName(), cacheRecord.getType());
                    }
                } catch (Exception e)
                {
                    if (Options.check("mdns_verbose"))
                    {
                        System.err.println("Error caching record: " + record);
                        e.printStackTrace(System.err);
                    }
                }
            }
        }
    }


    /**
     * {@inheritDoc}
     */
    protected void writeResponse(final Message message)
    throws IOException
    {
        if (Options.check("mdns_verbose"))
        {
            System.out.println("Writing Response to " + multicastAddress.getHostAddress() + ":" + port);
        }
        
        Header header = message.getHeader();
        
        header.setFlag(Flags.AA);
        header.setFlag(Flags.QR);
        header.setRcode(0);
        
        writeMessageToWire(message/*, true*/);
    }
    
    
    protected Message convertUpdateToQueryResponse(Message update)
    {
        Message m = new Message();
        Header h = m.getHeader();
        
        h.setOpcode(Opcode.QUERY);
        h.setFlag(Flags.AA);
        h.setFlag(Flags.QR);
        
        Record[] records = update.getSectionArray(Section.UPDATE);
        for (int index = 0; index < records.length; index++)
        {
            m.addRecord(records[index], Section.ANSWER);
        }

        records = update.getSectionArray(Section.ADDITIONAL);
        for (int index = 0; index < records.length; index++)
        {
            m.addRecord(records[index], Section.ADDITIONAL);
        }
        
        return m;
    }
    
    
    /**
     * Parses a DNS message from a raw DNS packet stored in a byte array.
     * 
     * @param b The byte array containing the raw DNS packet
     * @return The DNS message
     * @throws WireParseException If an error occurred while parsing the DNS message
     */
    protected Message parseMessage(byte [] b)
    throws WireParseException
    {
        try
        {
            return new Message(b);
        } catch (IOException e) 
        {
            if (Options.check("mdns_verbose"))
            {
                e.printStackTrace(System.err);
            }
            
            WireParseException we;
            if (!(e instanceof WireParseException))
            {
                we = new WireParseException("Error parsing message - " + e.getMessage());
                we.setStackTrace(e.getStackTrace());
            } else
            {
                we = (WireParseException) e;
            }
            
            throw we;
        }
    }

    
    public void packetReceived(Packet packet)
    {
        if (Options.check("mdns_verbose"))
        {
            System.out.println("mDNS Datagram Received!");
        }
        
        byte[] data = packet.getData();
        
        // Exclude message sent by this Responder and Message from a non-mDNS port 
        if (data.length > 0/* && packet.getPort() == processor.getPort() && !processor.isSentPacket(data)*/)
        {
            // Check that the response is long enough.
            if (data.length < Header.LENGTH)
            {
                if (Options.check("mdns_verbose"))
                {
                    System.err.println("Error parsing mDNS Response - Invalid DNS header - too short");
                }
                return;
            }
            
            try
            {
                Message message = parseMessage(data);
                resolverListenerDispatcher.receiveMessage(message.getHeader().getID(), message);
            } catch (IOException e)
            {
                System.err.println("Error parsing mDNS Packet - " + e.getMessage());
                System.err.println("Packet Data [" + Arrays.toString(data) + "]");
                e.printStackTrace(System.err);
            }
        }
    }


    protected void writeMessageToWire(Message message/*, boolean remember*/)
    throws IOException
    {
        Header header = message.getHeader();
        header.setID(0);
        applyEDNS(message);
        if (tsig != null)
        {
            tsig.apply(message, null);
        }
        
        byte[] out = message.toWire(Message.MAXLENGTH);
        for (DatagramProcessor multicastProcessor : multicastProcessors)
        {
            int maxUDPSize;
            OPTRecord opt = message.getOPT();
            if (opt != null)
            {
                maxUDPSize = opt.getPayloadSize();
            } else
            {
                maxUDPSize = multicastProcessor.getMaxPayloadSize();
            }
    
            // TODO: Break into multiple messages with Truncated flag set
            if (out.length > maxUDPSize)
            {
                if (header.getFlag(Flags.QR))
                {
                    throw new IOException("DNS Message too large! - " + out.length + " bytes in size.");
                } else
                {
                    Message[] messages = MulticastDNSUtils.splitMessage(message);
                    for (int index = 0; index < messages.length; index++)
                    {
                        writeMessageToWire(messages[index]/*, remember*/);
                    }
                    return;
                }
            }
            
            try
            {
                multicastProcessor.send(out/*, remember*/);
            } catch (Exception e)
            {
                resolverListenerDispatcher.handleException(message.getHeader().getID(), e);
            }
        }
    }
    
    
    /**
     * {@inheritDoc}
     */
    
    public Message send(final Message request)
    throws IOException
    {
        if (request == null)
        {
            throw new IOException("Query is null");
        }
        
        final Message query = (Message) request.clone();
        final int opcode = query.getHeader().getOpcode();
        
        // If all answers for the query are cached, return immediately. Otherwise,
        // Broadcast the query, waiting minimum response wait time, re-broadcasting the query 
        // periodically to ensure that all mDNS Responders on the network have a chance to respond 
        // (dropped frames/packets, etc...), and then return the answers received from cache.
        switch (opcode)
        {
            case Opcode.QUERY :
            case Opcode.IQUERY :
                Message message = cache.queryCache(query, Credibility.ANY);
                if (MulticastDNSUtils.answersAll(query, message))
                {
                    return message;
                } else
                {
                    final List results = new ArrayList();
                    final List exceptions = new ArrayList();

                    sendAsync(query, new ResolverListener()
                    {
                        public void receiveMessage(Object id, Message m)
                        {
                            synchronized (results)
                            {
                                results.add(m);
                                results.notifyAll();
                            }
                        }
                        
                        
                        public void handleException(Object id, Exception e)
                        {
                            synchronized (results)
                            {
                                exceptions.add(e);
                                results.notifyAll();
                            }
                        }
                    });

                    // Wait for response or exception
                    synchronized (results)
                    {
                        int wait = Options.intValue("mdns_resolve_wait");
                        long waitTill = System.currentTimeMillis() + (wait > 0 ? wait : DEFAULT_RESPONSE_WAIT_TIME);
                        while (results.size() == 0 && System.currentTimeMillis() < waitTill)
                        {
                            try
                            {
                                results.wait(waitTill - System.currentTimeMillis());
                            } catch (InterruptedException e)
                            {
                                // ignore
                            }
                        }
                    }
                    
                    
                    if (exceptions.size() > 0)
                    {
                        Exception e = (Exception) exceptions.get(0);
                        IOException ioe = new IOException(e.getMessage());
                        ioe.setStackTrace(e.getStackTrace());
                        throw ioe;
                    }
                }
                break;
            case Opcode.UPDATE :
                broadcast(query, false);
                break;
            default:
                throw new IOException("Don't know what to do with Opcode: " + Opcode.string(opcode) + " queries.");
        }
        
        return cache.queryCache(query, Credibility.ANY);
    }


    /**
     * {@inheritDoc}
     */
    
    public Object sendAsync(final Message m, final ResolverListener listener)
    {
        final Message query = (Message) m.clone();
        final Object id = query.getHeader().getID();
        final int opcode = query.getHeader().getOpcode();
        final ListenerWrapper wrapper = new ListenerWrapper(id, query, listener);
        registerListener(wrapper);
        
        switch (opcode)
        {
            case Opcode.QUERY :
            case Opcode.IQUERY :
                try
                {
                    Message message = cache.queryCache(query, Credibility.ANY);
                    if (message != null && message.getRcode() == Rcode.NOERROR && MulticastDNSUtils.answersAll(query, message))
                    {
                        listener.receiveMessage(id, message);
                    } else
                    {
                        int wait = Options.intValue("mdns_resolve_wait");
                        long timeOut = wait >= 0 ? wait : 1000;
                        scheduledExecutor.schedule(new Runnable()
                        {
                            public void run()
                            {
                                unregisterListener(wrapper);
                            }
                        }, timeOut, TimeUnit.MILLISECONDS);
                        
                        try
                        {
                            broadcast(query, false);
                        } catch (IOException e)
                        {
                            unregisterListener(wrapper);
                            listener.handleException(id, e);
                        }
                    }
                } catch (Exception e)
                {
                    listener.handleException(id, e);
                }
                break;
            case Opcode.UPDATE :
                try
                {
                    broadcast(query, false);
                } catch (Exception e)
                {
                    listener.handleException(id, e);
                    unregisterListener(wrapper);
                    break;
                }
                break;
            default:
                listener.handleException(id, new IOException("Don't know what to do with Opcode: " + Opcode.string(opcode) + " queries."));
                unregisterListener(wrapper);
                break;
        }
        
        return id;
    }
    
    
    protected void finalize()
    throws Throwable
    {
        close();
        super.finalize();
    }
    

    public void close()
    throws IOException
    {
        try
        {
            cache.close();
        } catch (Exception e)
        {
            if (Options.check("mdns_verbose"))
            {
                System.err.println("Error closing Cache - " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }
        
        for (DatagramProcessor multicastProcessor : multicastProcessors)
        {
            try
            {
                multicastProcessor.close();
            } catch (Exception e)
            {
                if (Options.check("mdns_verbose"))
                {
                    System.err.println("Error closing multicastProcessor - " + e.getMessage());
                    e.printStackTrace(System.err);
                }
            }
        }
        
        try
        {
            threadPool.shutdownNow();
        } catch (Exception e)
        {
            if (Options.check("mdns_verbose"))
            {
                System.err.println("Error shutting down cache monitor - " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }
        
        try
        {
            scheduledExecutor.shutdownNow();
        } catch (Exception e)
        {
            if (Options.check("mdns_verbose"))
            {
                System.err.println("Error shutting down scheduled executor - " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }
        
        resolverListenerProcessor.close();
    }
    
    
    protected void applyEDNS(Message query)
    {
        if (queryOPT == null || query.getOPT() != null)
        {
            return;
        }
        query.addRecord(queryOPT, Section.ADDITIONAL);
    }
    
    
    protected int verifyTSIG(Message query, Message response, byte [] b, TSIG tsig)
    {
        if (tsig == null)
        {
            return 0;
        }
        
        int error = tsig.verify(response, b, query.getTSIG());
        
        if (Options.check("mdns_verbose"))
        {
            System.err.println("TSIG verify: " + Rcode.TSIGstring(error));
        }
        
        return error;
    }
    
    
    /**
     * {@inheritDoc}
     */
    public void setCache(Cache cache)
    {
        if (cache instanceof MulticastDNSCache)
        {
            this.cache = (MulticastDNSCache) cache;
            if (this.cache.getCacheMonitor() == null)
            {
                this.cache.setCacheMonitor(cacheMonitor);
            }
        } else
        {
            try
            {
                this.cache = new MulticastDNSCache(cache);
                if (this.cache.getCacheMonitor() == null)
                {
                    this.cache.setCacheMonitor(cacheMonitor);
                }
            } catch (Exception e)
            {
                if (Options.check("verbose"))
                {
                    System.err.println(e.getMessage());
                    e.printStackTrace(System.err);
                }
                
                throw new IllegalArgumentException("Could not set Cache - " + e.getMessage());
            }
        }
    }
    
    
    /**
     * {@inheritDoc}
     */
    
    public Cache getCache()
    {
        return cache;
    }


    public ResolverListener registerListener(ResolverListener listener)
    {
        return resolverListenerProcessor.registerListener(listener);
    }


    public ResolverListener unregisterListener(ResolverListener listener)
    {
        return resolverListenerProcessor.unregisterListener(listener);
    }


    public void setIgnoreTruncation(boolean ignoreTruncation)
    {
        this.ignoreTruncation = ignoreTruncation;
    }
}
