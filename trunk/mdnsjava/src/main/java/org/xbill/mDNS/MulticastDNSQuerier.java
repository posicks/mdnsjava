package org.xbill.mDNS;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.xbill.DNS.ExtendedResolver;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Header;
import org.xbill.DNS.Message;
import org.xbill.DNS.MulticastDNSUtils;
import org.xbill.DNS.Name;
import org.xbill.DNS.Opcode;
import org.xbill.DNS.Options;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.ResolverListener;
import org.xbill.DNS.Section;
import org.xbill.DNS.TSIG;


/**
 * The MulticastDNSQuerier is a responder that integrates multicast and unicast DNS in accordance to the 
 * mDNS specification [RFC 6762].  DNS queries for multicast domains are send as multicast DNS requests, 
 * while unicast domain queries are sent as unicast DNS requests.
 * 
 * @author posicks
 */
@SuppressWarnings({"rawtypes","unchecked"})
public class MulticastDNSQuerier implements Querier
{
    protected static class Response
    {
        private Object id = null;
        
        private Message message = null;
        
        private Exception exception = null;
        
        
        protected Response(Object id, Message message)
        {
            this.id = id;
            this.message = message;
        }
        
        
        protected Response(Object id, Exception exception)
        {
            this.id = id;
            this.exception = exception;
        }
        
        
        public Object getID()
        {
            return id;
        }
        
        
        public Message getMessage()
        {
            return message;
        }
        
        
        public Exception getException()
        {
            return exception;
        }
        
        
        public boolean inError()
        {
            return exception != null;
        }
    }
    
    
    protected static class Resolution implements ResolverListener
    {
        private MulticastDNSQuerier querier = null;
        
        private Message query = null;
        
        private ResolverListener listener = null;
        
        private LinkedList responses = new LinkedList();

        private int requestsSent;

        private List requestIDs = new ArrayList();
        
        private boolean mdnsVerbose = false;

        
        public Resolution(MulticastDNSQuerier querier, Message query, ResolverListener listener)
        {
            this.querier = querier;
            this.query = query;
            this.listener = listener;
            mdnsVerbose = Options.check("mdns_verbose");
        }


        public void receiveMessage(Object id, Message message)
        {
            if (requestIDs.size() == 0 || requestIDs.contains(id) || this == id || this.equals(id) || MulticastDNSUtils.answersAny(query, message))
            {
                if (mdnsVerbose)
                {
                    System.out.println("!!!! Message Received - " + id + " - " + query.getQuestion());
                }
                synchronized (responses)
                {
                    responses.add(new Response(this, message));
                    responses.notifyAll();
                }
                
                if (listener != null)
                {
                    listener.receiveMessage(this, message);
                }
            } else if (mdnsVerbose)
            {
                
                String msg = "!!!!! Message Disgarded ";
                if (requestIDs.size() != 0 && (!requestIDs.contains(id) || this != id || !this.equals(id)))
                {
                    msg += "[Request ID does not match Response ID] ";
                }
                if (!MulticastDNSUtils.answersAny(query, message))
                {
                    msg += "[Response does not answer Query]";
                }
                System.err.println(msg);
                System.err.println(message);
            }
        }
        

        public void handleException(Object id, Exception exception)
        {
            if (requestIDs.size() != 0 && (!requestIDs.contains(id) || this != id || !this.equals(id)))
            {
                if (mdnsVerbose)
                {
                    System.out.println("!!!!! Exception Received for ID - " + id + ".");
                }
                synchronized (responses)
                {
                    responses.add(new Response(id, exception));
                    responses.notifyAll();
                }
                
                if (listener != null)
                {
                    listener.handleException(this, exception);
                }
            } else if (mdnsVerbose)
            {
                String msg = "!!!!! Exception Disgarded ";
                if (!(requestIDs.size() != 0 && (!requestIDs.contains(id) || this != id || !this.equals(id))))
                {
                    msg += "[Request ID does not match Response ID - " + id + " ] ";
                }
                System.err.println(msg);
                exception.printStackTrace(System.err);
            }
        }
        
        
        public boolean inError()
        {
            for (Object o : responses)
            {
                Response response = (Response) o;
                if (!response.inError())
                {
                    return false;
                }
            }
            
            return true;
        }
        
        
        public boolean hasResults()
        {
            return responses.size() >= requestsSent;
        }
        
        
        public Message[] getResults(boolean waitForResults, int timeoutValue)
        throws Exception
        {
            if (waitForResults)
            {
                long now = System.currentTimeMillis();
                long timeout = now + timeoutValue;
                while (!hasResults() && (now = System.currentTimeMillis()) < timeout)
                {
                    synchronized (responses)
                    {
                        if (!hasResults())
                        {
                            try
                            {
                                responses.wait(timeout - now);
                            } catch (InterruptedException e)
                            {
                                // ignore
                            }
                        }
                    }
                }
            }
            
            if (responses.size() > 0)
            {
                LinkedList messages = new LinkedList();
                LinkedList exceptions = new LinkedList();
                
                for (Object o : responses)
                {
                    Response response = (Response) o;
                    if (response.inError())
                    {
                        exceptions.add(response.getException());
                    } else
                    {
                        messages.add(response.getMessage());
                    }
                }
                
                if (messages.size() > 0)
                {
                    return (Message[]) messages.toArray(new Message[messages.size()]);
                } else if (exceptions.size() > 0)
                {
                    throw (Exception) exceptions.get(0);
                }
            }
            
            return null;
        }
        
        
        public Message getResponse(int responseWait, int timeout)
        throws IOException
        {
            Message response = (Message) query.clone();
            Header header = response.getHeader();
            try
            {
                Message[] messages = getResults(true, timeout);
                
                boolean found = false;
                if (messages != null && messages.length > 0)
                {
                    header.setRcode(Rcode.NOERROR);
                    header.setOpcode(Opcode.QUERY);
                    header.setFlag(Flags.QR);
                    
                    for (Message message : messages)
                    {
                        Header h = message.getHeader();
                        if (h.getRcode() == Rcode.NOERROR)
                        {
                            if (h.getFlag(Flags.AA))
                            {
                                header.setFlag(Flags.AA);
                            }
                            
                            if (h.getFlag(Flags.AD))
                            {
                                header.setFlag(Flags.AD);
                            }
                            
                            for (int section : new int[]{Section.ANSWER, Section.ADDITIONAL, Section.AUTHORITY})
                            {
                                Record[] records = message.getSectionArray(section);
                                if (records != null && records.length > 0)
                                {
                                    for (Record record : records)
                                    {
                                        if (!response.findRecord(record))
                                        {
                                            response.addRecord(record, section);
                                            found = true;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                if (!found)
                {
                    header.setRcode(Rcode.NXDOMAIN);
                }
                
                return response;
            } catch (Exception e)
            {
                if (e instanceof IOException)
                {
                    throw (IOException) e;
                } else
                {
                    IOException ioe = new IOException(e.getMessage());
                    ioe.setStackTrace(e.getStackTrace());
                    throw ioe;
                }
            }
        }


        public Object start()
        {
            requestsSent = 0;
            requestIDs.clear();
            boolean unicast = false;
            boolean multicast = false;
            if (MulticastDNSService.hasUnicastDomains(query) && querier.unicastResolvers != null && querier.unicastResolvers.length > 0)
            {
                for (Resolver resolver : querier.unicastResolvers)
                {
                    unicast = true;
                    requestIDs.add(resolver.sendAsync(query, this));
                    requestsSent++;
                }
            }
            
            if (MulticastDNSService.hasMulticastDomains(query) && querier.multicastResponders != null && querier.multicastResponders.length > 0)
            {
                for (Querier responder : querier.multicastResponders)
                {
                    multicast = true;
                    requestIDs.add(responder.sendAsync(query, this));
                    requestsSent++;
                }
            }
            
            if (!unicast && !multicast)
            {
                System.err.println("Could not execute query, no Unicast Resolvers or Multicast Queriers were available\n" + query);
            }
            return this;
        }
    }
    
    
    protected ListenerProcessor<ResolverListener> resolverListenerProcessor = new ListenerProcessor<ResolverListener>(ResolverListener.class);
    
    protected ResolverListener resolverListenerDispatcher = resolverListenerProcessor.getDispatcher();
    
    protected boolean ipv4 = false;
    
    protected boolean ipv6 = false;
    
    protected Querier[] multicastResponders;
    
    protected Resolver[] unicastResolvers;

    protected ResolverListener resolverDispatch = new ResolverListener()
    {
        public void receiveMessage(Object id, Message m)
        {
            resolverListenerDispatcher.receiveMessage(id, m);
        }


        public void handleException(Object id, Exception e)
        {
            resolverListenerDispatcher.handleException(id, e);
        }
    };

    private boolean mdnsVerbose;
    
    
    /**
     * Constructs a new IPv4 mDNS Querier using the default Unicast DNS servers for the system.
     * 
     * @throws UnknownHostException
     */
    public MulticastDNSQuerier()
    throws IOException
    {
        this(true, false, new Resolver[] {new ExtendedResolver()});
    }
    
    
    /**
     * Constructs a new mDNS Querier using the default Unicast DNS servers for the system.
     * 
     * @param ipv6 if IPv6 should be enabled.
     * @throws UnknownHostException
     */
    public MulticastDNSQuerier(boolean ipv4, boolean ipv6)
    throws IOException
    {
        this(ipv4, ipv6, (Resolver[]) null);
    }
    
    
    /**
     * Constructs a new mDNS Querier using the provided Unicast DNS Resolver.
     * 
     * @param ipv6 if IPv6 should be enabled.
     * @param unicastResolver The Unicast DNS Resolver
     * @throws UnknownHostException
     */
    public MulticastDNSQuerier(boolean ipv4, boolean ipv6, Resolver unicastResolver)
    throws IOException
    {
        this(ipv4, ipv6, new Resolver[] {unicastResolver});
    }
    
    
    /**
     * Constructs a new mDNS Querier using the provided Unicast DNS Resolvers.
     * 
     * @param ipv6 if IPv6 should be enabled.
     * @param unicastResolvers The Unicast DNS Resolvers
     * @throws UnknownHostException
     */
    public MulticastDNSQuerier(boolean ipv4, boolean ipv6, Resolver[] unicastResolvers)
    throws IOException
    {
        mdnsVerbose = Options.check("mdns_verbose");
        
        if (unicastResolvers == null || unicastResolvers.length == 0)
        {
            this.unicastResolvers = new Resolver[] {new ExtendedResolver()};
        } else
        {
            this.unicastResolvers = unicastResolvers;
        }
        
        Querier ipv4Responder = null;
        Querier ipv6Responder = null;
        
        IOException ipv4_exception = null;
        IOException ipv6_exception = null;
        
        if (ipv4)
        {
            try
            {
                ipv4Responder = new MulticastDNSMulticastOnlyQuerier(false);
                this.ipv4 = true;
            } catch (IOException e)
            {
                ipv4Responder = null;
                ipv4_exception = e;
                if (mdnsVerbose)
                {
                    System.err.println("Error constructing IPv4 mDNS Responder - " + e.getMessage());
                    e.printStackTrace(System.err);
                }
            }
        }
        
        if (ipv6)
        {
            try
            {
                ipv6Responder = new MulticastDNSMulticastOnlyQuerier(true);
                this.ipv6 = true;
            } catch (IOException e)
            {
                ipv6Responder = null;
                ipv6_exception = e;
                if (mdnsVerbose)
                {
                    System.err.println("Error constructing IPv6 mDNS Responder - " + e.getMessage());
                    e.printStackTrace(System.err);
                }
            }
        }
        
        if (ipv4Responder != null && ipv6Responder != null)
        {
            this.multicastResponders = new Querier[] {ipv4Responder, ipv6Responder};
            ipv4Responder.registerListener(resolverDispatch );
            ipv6Responder.registerListener(resolverDispatch);
        } else if (ipv4Responder != null)
        {
            this.multicastResponders = new Querier[] {ipv4Responder};
            ipv4Responder.registerListener(resolverDispatch);
        } else if (ipv6Responder != null)
        {
            this.multicastResponders = new Querier[] {ipv6Responder};
            ipv6Responder.registerListener(resolverDispatch);
        } else
        {
            if (ipv4_exception != null)
            {
                throw ipv4_exception;
            } else if (ipv6_exception != null)
            {
                throw ipv6_exception;
            }
        }
    }
    
    
    /**
     * {@inheritDoc}
     */
    public boolean isIPv4()
    {
        return ipv4;
    }
    
    
    /**
     * {@inheritDoc}
     */
    public boolean isIPv6()
    {
        return ipv6;
    }
    
    
    /**
     * {@inheritDoc}
     */
    public boolean isOperational()
    {
        for (Querier querier : this.multicastResponders)
        {
            if (!querier.isOperational())
            {
                return false;
            }
        }
        
        return true;
    }
    
    
    /**
     * {@inheritDoc}
     */
    public Name[] getMulticastDomains()
    {
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
    public void setPort(int port)
    {
        for (Querier querier : multicastResponders)
        {
            querier.setPort(port);
        }
    }
    
    
    public Resolver[] getUnicastResolvers()
    {
        return unicastResolvers;
    }
    
    
    /**
     * {@inheritDoc}
     */
    public void setTCP(boolean flag)
    {
        for (Resolver resolver : unicastResolvers)
        {
            resolver.setTCP(flag);
        }
    }
    
    
    /**
     * {@inheritDoc}
     */
    public void setIgnoreTruncation(boolean flag)
    {
        for (Querier querier : multicastResponders)
        {
            querier.setIgnoreTruncation(flag);
        }
        
        for (Resolver resolver : unicastResolvers)
        {
            resolver.setIgnoreTruncation(flag);
        }
    }
    
    
    /**
     * {@inheritDoc}
     */
    public void setEDNS(int level)
    {
        for (Querier querier : multicastResponders)
        {
            querier.setEDNS(level);
        }
        
        for (Resolver resolver : unicastResolvers)
        {
            resolver.setEDNS(level);
        }
    }
    
    
    /**
     * {@inheritDoc}
     */
    public void setEDNS(int level, int payloadSize, int flags, List options)
    {
        for (Querier querier : multicastResponders)
        {
            querier.setEDNS(level, payloadSize, flags, options);
        }
        
        for (Resolver resolver : unicastResolvers)
        {
            resolver.setEDNS(level, payloadSize, flags, options);
        }
    }
    
    
    /**
     * {@inheritDoc}
     */
    public void setTSIGKey(TSIG key)
    {
        for (Querier querier : multicastResponders)
        {
            querier.setTSIGKey(key);
        }
        
        for (Resolver resolver : unicastResolvers)
        {
            resolver.setTSIGKey(key);
        }
    }
    
    
    /**
     * {@inheritDoc}
     */
    public void setTimeout(int secs, int msecs)
    {
        for (Querier querier : multicastResponders)
        {
            querier.setTimeout(secs, msecs);
        }
        
        for (Resolver resolver : unicastResolvers)
        {
            resolver.setTimeout(secs, msecs);
        }
    }
    
    
    /**
     * {@inheritDoc}
     */
    public void setTimeout(int secs)
    {
        for (Querier querier : multicastResponders)
        {
            querier.setTimeout(secs);
        }
        
        for (Resolver resolver : unicastResolvers)
        {
            resolver.setTimeout(secs);
        }
    }
    
    
    /**
     * {@inheritDoc}
     */
    public Message send(final Message query)
    throws IOException
    {
        Resolution res = new Resolution(this, query, null);
        res.start();
        return res.getResponse(DEFAULT_RESPONSE_WAIT_TIME, DEFAULT_TIMEOUT);
    }
    
    
    /**
     * {@inheritDoc}
     */
    public Object sendAsync(final Message query, final ResolverListener listener)
    {
        Resolution res = new Resolution(this, query, listener);
        res.start();
        return res;
    }
    
    
    /**
     * {@inheritDoc}
     */
    public void broadcast(Message message, boolean addKnown)
    throws IOException
    {
        boolean success = false;
        IOException ex = null;
        for (Querier responder : multicastResponders)
        {
            try
            {
                responder.broadcast(message, addKnown);
                success = true;
            } catch (IOException e)
            {
                ex = e;
            }
        }
        
        for (Resolver resolver : unicastResolvers)
        {
            resolver.sendAsync(message, new ResolverListener()
            {
                public void receiveMessage(Object id, Message m)
                {
                    resolverListenerDispatcher.receiveMessage(id, m);
                }
                
                
                public void handleException(Object id, Exception e)
                {
                    resolverListenerDispatcher.handleException(id, e);
                }
            });
        }
        
        if (!success && ex != null)
        {
            throw ex;
        }
    }


    /**
     * {@inheritDoc}
     */
    public void setRetryWaitTime(int secs)
    {
        for (Querier querier : multicastResponders)
        {
            querier.setTimeout(secs);
        }
    }


    /**
     * {@inheritDoc}
     */
    public void setRetryWaitTime(int secs, int msecs)
    {
        for (Querier querier : multicastResponders)
        {
            querier.setTimeout(secs, msecs);
        }
    }
    

    public void close()
    throws IOException
    {
        for (Querier querier : multicastResponders)
        {
            try
            {
                querier.close();
            } catch (Exception e)
            {
                if (mdnsVerbose)
                {
                    System.err.println("Error closing Responder: " + e.getMessage());
                    e.printStackTrace(System.err);
                }
            }
        }
    }


    public ResolverListener registerListener(ResolverListener listener)
    {
        for (Querier querier : multicastResponders)
        {
            querier.registerListener(listener);
        }
        
        return listener;
    }


    public ResolverListener unregisterListener(ResolverListener listener)
    {
        for (Querier querier : multicastResponders)
        {
            querier.unregisterListener(listener);
        }
        
        return listener;
    }
}
