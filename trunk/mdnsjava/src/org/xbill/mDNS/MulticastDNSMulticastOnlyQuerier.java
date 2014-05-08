package org.xbill.mDNS;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
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
public class MulticastDNSMulticastOnlyQuerier implements Querier
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

            int[] sections = null;
            int credibility = Credibility.NORMAL;
            switch (opcode)
            {
                case Opcode.IQUERY :
                case Opcode.QUERY :
                case Opcode.NOTIFY :
                case Opcode.STATUS :
                    if (header.getFlag(Flags.QR) || header.getFlag(Flags.AA) || header.getFlag(Flags.AD))
                    {
                        sections = new int[] {Section.ANSWER, Section.AUTHORITY, Section.ADDITIONAL};
                    } else
                    {
                        return;
                    }
                    break;
                case Opcode.UPDATE :
                    if (cache.getCacheMonitor() == null)
                    {
                        cache.setCacheMonitor(cacheMonitor);
                    }
                    sections = new int[] {Section.ZONE, Section.PREREQ, Section.UPDATE, Section.ADDITIONAL};
                    credibility = Credibility.AUTH_ANSWER;
                    break;
            }
            
            if (Options.check("mdns_verbose"))
            {
                System.out.println("RCode: " + Rcode.string(rcode));
                System.out.println("Opcode: " + Opcode.string(opcode));
            }
            
            Record[] records = MulticastDNSUtils.extractRecords(message, sections);
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
                                        System.out.println("Updating Record: " + cacheRecord);
                                    }
                                    cache.updateRRset(cacheRecord, credibility);
                                }
                            } else
                            {
                                if (Options.check("mdns_verbose"))
                                {
                                    System.out.println("Caching Record: " + cacheRecord);
                                }
                                cache.addRecord(cacheRecord, credibility, message);
                            }
                        } else
                        {
                            // Remove unregistered records from Cache
                            RRset[] rrs = cache.findAnyRecords(cacheRecord.getName(), cacheRecord.getType());
                            if (rrs != null && rrs.length > 0)
                            {
                                for (int i = 0; i < rrs.length; i++)
                                {
                                    cache.removeRRset(rrs[i]);
                                }
                            }
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
            
            if (header.getFlag(Flags.QR))
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
                    // TODO: Implement the reception of truncated packets. (wait 400 to 500 milliseconds for more packets) 
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
                        Message response = cache.queryCache(message, Credibility.AUTH_ANSWER);
                        
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
                                responseHeader.setFlag(Flags.QR + Flags.AA);
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
            return new Thread(r, "mDNSResolver Pool Thread");
        }
    });
    
    protected ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(1, new ThreadFactory()
    {
        public Thread newThread(Runnable r)
        {
            return new Thread(r, "mDNSResolver Scheduled Thread");
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
    
    protected DatagramProcessor multicastProcessor;
    
//TODO:    protected UnicastProcessor unicastProcessor;
    
    protected Thread multicastReceiverThread = null;
    
    protected Thread unicastReceiverThread = null;
    

    private CacheMonitor cacheMonitor = new CacheMonitor()
    {
        private List records = new ArrayList();
        
        private boolean foundAuthoritiveRecords;
        
        private long lastPoll = -1;
        
        
        public void begin()
        {
            if (Options.check("mdns_verbose"))
            {
                System.out.print("!!!! ");
                if (lastPoll > 0)
                {
                    System.out.print("Last Poll " + (double) ((double) (System.nanoTime() - lastPoll) / (double) 1000000000) + " seconds ago. ");
                }
                System.out.print(" Cache Monitor Check ");
            }
            lastPoll = System.nanoTime();
            
            records.clear();
            foundAuthoritiveRecords = false;
        }
        
        
        public void check(RRset rrs, int credibility, int expiresIn)
        {
            if (Options.check("mdns_verbose"))
            {
                System.out.println("CacheMonitor check RRset: expires in: " + expiresIn + " seconds : " + rrs);
            }
            long ttl = rrs.getTTL();
            
            // Update expiry of records in accordance to RFC 6762 Section 5.2
            if (credibility >= Credibility.AUTH_ANSWER)
            {
                foundAuthoritiveRecords = true;
                if (isAboutToExpire(ttl, expiresIn))
                {
                    Record[] records = MulticastDNSUtils.extractRecords(rrs);
                    for (Record record : records)
                    {
                        try
                        {
                            MulticastDNSUtils.setTLLForRecord(record, ttl);
                            this.records.add(record);
                        } catch (Exception e)
                        {
                            System.err.println(e.getMessage());
                            e.printStackTrace(System.err);
                        }
                    }
                }
            }
        }
        
        
        public void expired(RRset rrs)
        {
            if (Options.check("mdns_verbose"))
            {
                System.out.println("CacheMonitor RRset expired : " + rrs);
            }
            Record[] records = MulticastDNSUtils.extractRecords(rrs);
            if (records != null && records.length > 0)
            {
                for (int i = 0; i < records.length; i++)
                {
                    try
                    {
                        MulticastDNSUtils.setTLLForRecord(records[i], 0);
                        this.records.add(records[i]);
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
                if (records.size() > 0)
                {
                    Message m = new Message();
                    Header h = m.getHeader();
                    h.setOpcode(Opcode.UPDATE);
                    for (int index = 0; index < records.size(); index++)
                    {
                        m.addRecord((Record) records.get(index), Section.UPDATE);
                    }

                    broadcast(m, false);
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
            
            if (!foundAuthoritiveRecords)
            {
                cache.setCacheMonitor(null);
            }
            
            records.clear();
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
        this(InetAddress.getByName(ipv6 ? MulticastDNSService.DEFAULT_IPv6_ADDRESS : MulticastDNSService.DEFAULT_IPv4_ADDRESS));
    }
    
    
    public MulticastDNSMulticastOnlyQuerier(InetAddress address)
    throws IOException
    {
        super();
        this.cache = MulticastDNSCache.DEFAULT_MDNS_CACHE;
        
        // Set Address to any local address
        setAddress(address);
        
        multicastProcessor = new DatagramProcessor(multicastAddress, port, new DatagramProcessor.PacketListener()
        {
            public void packetReceived(Packet packet)
            {
                MulticastDNSMulticastOnlyQuerier.this.packetReceived(multicastProcessor, packet);
            }
        });
        
/* TODO        unicastProcessor = new UnicastProcessor(null, port, new DatagramProcessor.PacketListener()
        {
            public void packetReceived(Packet packet)
            {
                mDNSMulticastOnlyResponder.this.packetReceived(packet);
            }
        });
*/
        
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
        
        multicastReceiverThread = new Thread(multicastProcessor);
        multicastReceiverThread.start();
        
// TODO:        unicastReceiverThread = new Thread(unicastProcessor);
//        unicastReceiverThread.start();
        
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
        return multicastProcessor.isIPv4();
    }


    /**
     * {@inheritDoc}
     */
    public boolean isIPv6()
    {
        return multicastProcessor.isIPv6();
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
            cacher.receiveMessage(header.getID(), message);
            message.getHeader().setFlag(Flags.AA);
            writeMessageToWire(convertUpdateToQueryResponse(message)/*, false*/);
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
        boolean isUpdate = header.getOpcode() == Opcode.UPDATE;
        
        if (isUpdate)
        {
            cacher.receiveMessage(header.getID(), message);
            header.setFlag(Flags.AA);
            header.setRcode(0);
            writeMessageToWire(convertUpdateToQueryResponse(message)/*, false*/);
        } else
        {
            header.setFlag(Flags.QR + Flags.AA);
            header.setRcode(0);
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
            
            // Set the high-order bit on the rrclass indicating the full RRSet is being sent.
            for (Integer section : new Integer[] {Section.ANSWER, Section.ADDITIONAL})
            {
                Record[] records = message.getSectionArray(section);
                for (Record record : records)
                {
                    MulticastDNSUtils.setDClassForRecord(record, record.getDClass() | 0x1000); 
                }
            }

            
            writeMessageToWire(message/*, true*/);
        }
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

    
    public void packetReceived(NetworkProcessor processor, Packet packet)
    {
        if (Options.check("mdns_verbose"))
        {
            System.out.println("mDNS Datagram Received!");
        }
        
        byte[] data = packet.getData();
        
        // Exclude message sent by this Responder and Message from a non-mDNS port 
        if (data.length > 0 && packet.getPort() == processor.getPort() && !processor.isSentPacket(data))
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
                System.err.println("Error parsing mDNS Response - " + e.getMessage());
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

        final byte[] out = message.toWire(Message.MAXLENGTH);
        final int maxUDPSize;
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
                    
                    /*
                    while ((now = System.currentTimeMillis()) < responseWaitTime || (response == null && now < timeout))
                    {
                        try
                        {
                            long timeToWait = Math.min(this.retryInterval, (now < responseWaitTime ? responseWaitTime : timeout) - now);
                            if (timeToWait > 0)
                            {
                                if ((response == null || !MulticastDNSUtils.answersAll(query, response)) && now > (lastBroadcast + this.retryInterval))
                                {
                                    if (Options.check("mdns_verbose"))
                                    {
                                        System.out.println("Broadcasting query and waiting for " + timeToWait + " milliseconds.");
                                    }
                                    
                                    lastBroadcast = now;
                                    broadcast(query, true);
                                }
                                
                                Thread.sleep(timeToWait);
                            }
                        } catch (InterruptedException e)
                        {
                            // ignore
                        }
                        
                        response = cache.queryCache(query, Credibility.ANY);
                    }
                    
                    if (response == null)
                    {
                        response = cache.queryCache(query, Credibility.ANY);
                    }
                    */
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
        
/* TODO: Implement Unicast DNS client and server
        try
        {
            unicastProcessor.close();
        } catch (Exception e)
        {
            if (Options.check("mdns_verbose"))
            {
                System.err.println("Error closing unicastProcessor - " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }
*/
        
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
        } else
        {
            try
            {
                this.cache = new MulticastDNSCache(cache);
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
