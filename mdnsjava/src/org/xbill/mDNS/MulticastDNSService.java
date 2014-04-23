package org.xbill.mDNS;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.MulticastDNSUtils;
import org.xbill.DNS.NSECRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Options;
import org.xbill.DNS.PTRRecord;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.ResolverListener;
import org.xbill.DNS.SRVRecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.Type;
import org.xbill.DNS.Update;

@SuppressWarnings({"unchecked","rawtypes"})
public class MulticastDNSService extends MulticastDNSLookupBase
{
    public static final long DEFAULT_RR_WITHOUT_HOST_TTL = 4500; // 75 Minutes
    
    public static final long DEFAULT_RR_WITH_HOST_TTL = 120; // 2 Minutes

    public static final long DEFAULT_OTHER_TTL = DEFAULT_RR_WITHOUT_HOST_TTL;

    public static final long DEFAULT_SRV_TTL = DEFAULT_RR_WITH_HOST_TTL;

    public static final long DEFAULT_TXT_TTL = DEFAULT_RR_WITHOUT_HOST_TTL;

    public static final long DEFAULT_A_TTL = DEFAULT_RR_WITH_HOST_TTL;

    public static final long DEFAULT_PTR_TTL = DEFAULT_RR_WITHOUT_HOST_TTL;
    
    public static final String LINK_LOCAL_DOMAIN = "local.";
    
    public static final Name[] ALL_MULTICAST_DNS_DOMAINS = new Name[] 
    {
        Name.fromConstantString(LINK_LOCAL_DOMAIN),
        Name.fromConstantString("254.169.in-addr.arpa."),
        Name.fromConstantString("8.e.f.ip6.arpa."),
        Name.fromConstantString("9.e.f.ip6.arpa."),
        Name.fromConstantString("a.e.f.ip6.arpa."),
        Name.fromConstantString("b.e.f.ip6.arpa.")
    };

    /** The multicast domains.  These domains must be sent to the IPv4 or IPv6 mDNS address */
    public static final Name[] IPv4_MULTICAST_DOMAINS = new Name[] 
    {
        Name.fromConstantString(LINK_LOCAL_DOMAIN),
        Name.fromConstantString("254.169.in-addr.arpa."),
    };
    
    public static final Name[] IPv6_MULTICAST_DOMAINS = new Name[] 
    {
        Name.fromConstantString(LINK_LOCAL_DOMAIN),
        Name.fromConstantString("8.e.f.ip6.arpa."),
        Name.fromConstantString("9.e.f.ip6.arpa."),
        Name.fromConstantString("a.e.f.ip6.arpa."),
        Name.fromConstantString("b.e.f.ip6.arpa.")
    };
    
    /** The default port to send queries to */
    public static final int DEFAULT_PORT = 5353;

    /** The default address to send IPv4 queries to */
    public static final String DEFAULT_IPv4_ADDRESS = "224.0.0.251";
    
    /** The default address to send IPv6 queries to */
    public static final String DEFAULT_IPv6_ADDRESS = "FF02::FB";
    
    /** The domain name used by DNS-Based Service Discovery (DNS-SD) [RFC 6763] to list default browse domains */
    public static final String DEFAULT_BROWSE_DOMAIN_NAME = "db._dns-sd._udp";

    /** The domain name used by DNS-Based Service Discovery (DNS-SD) [RFC 6763] to list browse domains */
    public static final String BROWSE_DOMAIN_NAME = "b._dns-sd._udp";

    /** The domain name used by DNS-Based Service Discovery (DNS-SD) [RFC 6763] to list legacy browse domains */
    public static final String LEGACY_BROWSE_DOMAIN_NAME = "lb._dns-sd._udp";
    
    /** The domain name used by DNS-Based Service Discovery (DNS-SD) [RFC 6763] to list default registration domains */
    public static final String DEFAULT_REGISTRATION_DOMAIN_NAME = "dr._dns-sd._udp";

    /** The domain name used by DNS-Based Service Discovery (DNS-SD) [RFC 6763] to list registration domains */
    public static final String REGISTRATION_DOMAIN_NAME = "r._dns-sd._udp";

    /** The domain names used by DNS-Based Service Discovery (DNS-SD) [RFC 6763] to iterate registered service types */
    public static final String SERVICES_NAME = "_services._dns-sd.udp";

    /** The Cache Flush flag used in Multicast DNS (mDNS) [RFC 6762] query responses */
    public static final int CACHE_FLUSH = 0x8000;
    
    
    public static boolean hasMulticastDomains(Message query)
    {
        Record[] records = MulticastDNSUtils.extractRecords(query, 0, 1, 2, 3);
        if (records != null)
        {
            for (Record record : records)
            {
                if (isMulticastDomain(record.getName()))
                {
                    return true;
                }
            }
        }
        return false;
    }


    public static boolean hasUnicastDomains(Message query)
    {
        Record[] records = MulticastDNSUtils.extractRecords(query, 0, 1, 2, 3);
        if (records != null)
        {
            for (Record record : records)
            {
                if (!isMulticastDomain(record.getName()))
                {
                    return true;
                }
            }
        }
        return false;
    }
    
    
    public static boolean isMulticastDomain(Name name)
    {
        for (Name multicastDomain : IPv4_MULTICAST_DOMAINS)
        {
            if (name.equals(multicastDomain) || name.subdomain(multicastDomain))
            {
                return true;
            }
        }
        
        for (Name multicastDomain : IPv6_MULTICAST_DOMAINS)
        {
            if (name.equals(multicastDomain) || name.subdomain(multicastDomain))
            {
                return true;
            }
        }
        
        return false;
    }
    
    
    public MulticastDNSService()
    throws IOException
    {
        super();
    }
    
    
    protected class Register
    {
        private ServiceInstance service;
        
        protected Register(ServiceInstance service)
        throws UnknownHostException
        {
            super();
            this.service = service;
        }
        
        
        /**
         * Registers the Service.
         * 
         * @return The Service Instances actually Registered
         * @throws IOException
         */
        protected ServiceInstance register()
        throws IOException
        {
            // TODO: Implement Probing and Name Conflict Resolution as per RFC 6762 Section 8.
            /*
             * Steps to Registering a Service.
             * 
             * 1. Query the service name of type ANY.  Ex. Test._mdc._tcp.local IN ANY Flags: QM
             *   a. Add the Service Record to the Authoritative section.
             *   b. Repeat 3 queries with a 250 millisecond delay between each query.
             * 2. Send a standard Query Response containing the service records, Opcode: QUERY, Flags: QR, AA, NO ERROR
             *   a. Add TXT record to ANSWER section. TTL: 3600
             *   b. Add SRV record to ANSWER section. TTL: 120
             *   c. Add DNS-SD Services PTR record to ANSWER section. TTL: 3600  Ex. _services._dns-sd.udp.local. IN PTR _mdc._tcp.local.   
             *   d. Add PTR record to ANSWER section. Ex. _mdc._tcp.local. IN PTR Test._mdc._tcp.local. TTL: 3600
             *   e. Add A record to ADDITIONAL section. TTL: 120  Ex. hostname.local. IN A 192.168.1.83
             *   f. Add AAAA record to ADDITIONAL section. TTL: 120  Ex. hostname.local. IN AAAA fe80::255:ff:fe4a:6369 
             *   g. Add NSEC record to ADDITIONAL section. TTL: 120  Ex. hostname.local. IN NSEC next domain: hostname.local. RRs: A AAAA 
             *   h. Add NSEC record to ADDITIONAL section. TTL: 3600  Ex. Test._mdc._tcp.local. IN NSEC next domain: Test._mdc._tcp.local. RRs: TXT SRV
             *   b. Repeat 3 queries with a 2 second delay between each query response.
             */
            final List replies = new ArrayList();
            Message query = Message.newQuery(Record.newRecord(service.getName(), Type.ANY, DClass.IN));
            query.addRecord(new SRVRecord(service.getName(), DClass.IN, 3600, 0, 0, service.getPort(), service.getHost()), Section.AUTHORITY);
            // TODO: Add support for Unicast answers for first query mDNS.createQuery(DClass.IN + 0x8000, Type.ANY, service.getName());
            
            int tries = 0;
            while (tries++ < 3)
            {
                querier.sendAsync(query, new ResolverListener()
                {
                    public void receiveMessage(Object id, Message m)
                    {
                        synchronized (replies)
                        {
                            replies.add(m);
                            replies.notifyAll();
                        }
                    }
                    
                    
                    public void handleException(Object id, Exception e)
                    {
                        synchronized (replies)
                        {
                            replies.add(e);
                            replies.notifyAll();
                        }
                    }
                });
                
                synchronized (replies)
                {
                    try
                    {
                        replies.wait(Querier.DEFAULT_RESPONSE_WAIT_TIME);
                    } catch (InterruptedException e)
                    {
                        // ignore
                    }
                    
                    if (replies.size() > 0)
                    {
                        for (Iterator i = replies.iterator(); i.hasNext();)
                        {
                            Object o = i.next();
                            if (o instanceof Exception)
                            {
                                if (o instanceof IOException)
                                {
                                    throw (IOException) o;
                                } else
                                {
                                    Exception e = (Exception) o;
                                    IOException ioe = new IOException(e.getMessage());
                                    ioe.setStackTrace(e.getStackTrace());
                                    throw ioe;
                                }
                            } else
                            {
                                Message message = (Message) o;
                                if (message.getRcode() == Rcode.NOERROR)
                                {
                                    Record[] records = MulticastDNSUtils.extractRecords(message, Section.ANSWER, Section.AUTHORITY, Section.ADDITIONAL);
                                    for (int r = 0; r < records.length; r++)
                                    {
                                        if (records[r].getType() == Type.SRV && records[r].getTTL() > 0)
                                        {
                                            // Service with this same name found, so registration must fail.
                                            return null;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            replies.clear();
            
            try
            {
                // Name Not Found, Register New Service
                Name domain = new Name(service.getName().getDomain());
                final Update update = new Update(domain);
                
                Name typeName = new Name(service.getName().getFullType() + "." + domain);
                
                PTRRecord serviceTypeReg = new PTRRecord(new Name(SERVICES_NAME + "." + domain), DClass.IN, DEFAULT_SRV_TTL, service.getName());
                PTRRecord ptr = new PTRRecord(typeName, DClass.IN, DEFAULT_SRV_TTL, service.getName());
                SRVRecord srv = new SRVRecord(service.getName(), DClass.IN + CACHE_FLUSH, DEFAULT_SRV_TTL, 0, 0, service.getPort(), service.getHost());
                TXTRecord txt = new TXTRecord(service.getName(), DClass.IN + CACHE_FLUSH, DEFAULT_TXT_TTL, Arrays.asList(service.getText()));
                NSECRecord serviceNSEC = new NSECRecord(service.getName(), DClass.IN + CACHE_FLUSH, DEFAULT_RR_WITHOUT_HOST_TTL, service.getName(), new int[]{Type.TXT, Type.SRV});
                NSECRecord addressNSEC = new NSECRecord(service.getHost(), DClass.IN + CACHE_FLUSH, DEFAULT_RR_WITH_HOST_TTL, service.getHost(), new int[]{Type.A, Type.AAAA});
                
                InetAddress[] addresses = service.getAddresses();
                
                update.add(txt);
                update.add(srv);
                
                if (addresses != null)
                {
                    for (int index = 0; index < addresses.length; index++)
                    {
                        if (addresses[index] != null)
                        {
                            if (addresses[index].getAddress().length == 4)
                            {
                                update.addRecord(new ARecord(service.getHost(), DClass.IN + CACHE_FLUSH, DEFAULT_A_TTL, addresses[index]), Section.ADDITIONAL);
                            } else
                            {
                                update.addRecord(new AAAARecord(service.getHost(), DClass.IN + CACHE_FLUSH, DEFAULT_A_TTL, addresses[index]), Section.ADDITIONAL);
                            }
                        }
                    }
                }
                
                update.add(ptr);
                update.add(serviceTypeReg);
                
                update.addRecord(addressNSEC, Section.ADDITIONAL);
                update.addRecord(serviceNSEC, Section.ADDITIONAL);
                
                // Updates are sent at least 2 times, one second apart, as per RFC 6762 Section 8.3
                ResolverListener resolverListener = new ResolverListener()
                {
                    public void receiveMessage(Object id, Message m)
                    {
                        replies.add(m);
                        replies.notifyAll();
                    }
                    
                    
                    public void handleException(Object id, Exception e)
                    {
                        replies.add(e);
                        replies.notifyAll();
                    }
                };
                
                tries = 0;
                while (tries++ < 2)
                {
                    querier.sendAsync(update, resolverListener);
                    
                    long retry = System.currentTimeMillis() + 1000;
                    while (System.currentTimeMillis() < retry)
                    {
                        try
                        {
                            Thread.sleep(1000);
                        } catch (InterruptedException e)
                        {
                            // ignore
                        }
                    }
                }
            } catch (Exception e)
            {
                synchronized (replies)
                {
                    replies.add(e);
                    replies.notifyAll();
                }
            }
            
            long endTime = System.currentTimeMillis() + 10000;
            ServiceInstance[] instances = null;
            while (instances == null && System.currentTimeMillis() < endTime)
            {
                if (replies.size() == 0)
                {
                    try
                    {
                        synchronized (replies)
                        {
                            replies.wait(Querier.DEFAULT_RETRY_INTERVAL);
                        }
                    } catch (InterruptedException e)
                    {
                        // ignore
                    }
                }

                Lookup lookup = new Lookup(new Name[]{service.getName()}, Type.ANY);
                try
                {
                    instances = lookup.lookupServices();
                    
                    if (instances != null && instances.length > 0)
                    {
                        if (instances.length > 1)
                        {
                            if (Options.check("mdns_verbose"))
                            {
                                System.err.println("Warning: Somehow more than one service with the name \"" + service.getName() + "\" was registered.");
                            }
                        }
                        
                        System.out.println("Response received");
                        if (instances.length > 1)
                        {
                            throw new IOException("Too many services returned! + Instances: " + Arrays.toString(instances));
                        }
                        
                        return instances[0];
                    }
                } finally
                {
                    try
                    {
                        lookup.close();
                    } catch (IOException e)
                    {
                        // ignore
                    }
                }
            }
            
            return null;
        }


        protected void close()
        throws IOException
        {
        }
    }

    
    protected class Unregister
    {
        private ServiceName serviceName;
        
        protected Unregister(ServiceInstance service)
        {
            this(service.getName());
        }
        
        protected Unregister(ServiceName serviceName)
        {
            super();
            this.serviceName = serviceName;
        }
        
        
        protected boolean unregister()
        throws IOException
        {
            /*
             * Steps to Registering a Service.
             * 
             * 1. Send a standard Query Response containing the service records, Opcode: QUERY, Flags: Response, Authoritative, NO ERROR
             *   a. Add PTR record to ANSWER section. TTL: 0  Ex. _mdc._tcp.local. IN PTR Test._mdc._tcp.local.
             *   b. Repeat 3 queries with a 2 second delay between each query response.
             */
            Name domain = new Name(serviceName.getDomain());
            Update update = new Update(domain);
            update.add(new PTRRecord(serviceName.getServiceTypeName(), DClass.IN, 0, serviceName));
            
            // Updates are sent at least 2 times, one second apart, as per RFC 6762 Section 8.3
            ResolverListener resolverListener = new ResolverListener()
            {
                public void receiveMessage(Object id, Message m)
                {
                }
                
                
                public void handleException(Object id, Exception e)
                {
                }
            };
            
            int tries = 0;
            while (tries++ < 3)
            {
                querier.sendAsync(update, resolverListener);
                
                long retry = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < retry)
                {
                    try
                    {
                        Thread.sleep(2000);
                    } catch (InterruptedException e)
                    {
                        // ignore
                    }
                }
            }
            
            Lookup lookup = new Lookup(new Name[]{serviceName.getServiceTypeName()}, Type.PTR,DClass.ANY);
            try
            {
                Record[] records = lookup.lookupRecords();
                return records == null || records.length == 0;
            } finally
            {
                lookup.close();
            }
        }
        
        
        protected void close()
        throws IOException
        {
        }
    }
    /**
     * The Browse Operation manages individual browse sessions.  Retrying broadcasts. 
     * Refer to the mDNS specification [RFC 6762]
     * 
     * @author Steve Posick
     */
    protected class ServiceDiscoveryOperation implements ResolverListener
    {
        private Browse browser;
        
        private ListenerProcessor<DNSSDListener> listenerProcessor = new ListenerProcessor<DNSSDListener>(DNSSDListener.class);
        
        private Map services = new HashMap();
        
        
        ServiceDiscoveryOperation(Browse browser)
        {
            this(browser, null);
        }


        ServiceDiscoveryOperation(Browse browser, DNSSDListener listener)
        {
            this.browser = browser;
            
            if (listener != null)
            {
                registerListener(listener);
            }
        }


        Browse getBrowser()
        {
            return browser;
        }
        
        
        boolean answersQuery(Record record)
        {
            if (record != null)
            {
                for (Message query : browser.queries)
                {
                    for (Record question : MulticastDNSUtils.extractRecords(query, Section.QUESTION))
                    {
                        Name questionName = question.getName();
                        Name recordName = record.getName();
                        int questionType = question.getType();
                        int recordType = record.getType();
                        int questionDClass = question.getDClass();
                        int recordDClass = record.getDClass();
                        
                        if ((questionType == Type.ANY || questionType == recordType) &&
                            (questionName.equals(recordName) || questionName.subdomain(recordName) ||
                            recordName.toString().endsWith("." + questionName.toString())) &&
                            (questionDClass == DClass.ANY || (questionDClass & 0x7FFF) == (recordDClass & 0x7FFF)))
                        {
                            return true;
                        }
                    }
                }
            }
            
            return false;
        }
        
        
        boolean matchesBrowse(Message message)
        {
            if (message != null)
            {
                Record[] thatAnswers = MulticastDNSUtils.extractRecords(message, Section.ANSWER, Section.AUTHORITY, Section.ADDITIONAL);
                
                for (Record thatAnswer : thatAnswers)
                {
                    if (answersQuery(thatAnswer))
                    {
                        return true;
                    }
                }
            }
            
            return false;
        }
        
        
        DNSSDListener registerListener(DNSSDListener listener)
        {
            return listenerProcessor.registerListener(listener);
        }
        
        
        DNSSDListener unregisterListener(DNSSDListener listener)
        {
            return listenerProcessor.unregisterListener(listener);
        }
        

        public void receiveMessage(Object id, Message message)
        {
            if (matchesBrowse(message))
            {
                listenerProcessor.getDispatcher().receiveMessage(id, message);
                
                Record[] records = MulticastDNSUtils.extractRecords(message, Section.ANSWER, Section.AUTHORITY, Section.ADDITIONAL);
                
                for (int index = 0; index < records.length; index++)
                {
                    try
                    {
                        ServiceInstance service = null;
                        
                        switch (records[index].getType())
                        {
                            case Type.SRV :
                                SRVRecord srv = (SRVRecord) records[index];
                                if (srv.getTTL() > 0)
                                {
                                    if (!services.containsKey(srv.getName()))
                                    {
                                        service = new ServiceInstance(srv);
                                        if (!services.containsKey(srv.getName()))
                                        {
                                            service = new ServiceInstance(srv);
                                            services.put(srv.getName(), service);
                                            listenerProcessor.getDispatcher().serviceDiscovered(id, service);
                                        }
                                    }
                                } else
                                {
                                    service = (ServiceInstance) services.get(srv.getName());
                                    if (service != null)
                                    {
                                        services.remove(service.getName());
                                        listenerProcessor.getDispatcher().serviceRemoved(id, service);
                                    }
                                }
                                break;
                            case Type.PTR :
                                PTRRecord ptr = (PTRRecord) records[index];
                                
                                if (ptr.getTTL() > 0)
                                {
                                    ServiceInstance[] instances = extractServiceInstances(querier.send(Message.newQuery(Record.newRecord(ptr.getTarget(), Type.ANY, dclass))));
                                    if (instances.length > 0)
                                    {
                                        for (int i = 0; i < instances.length; i++)
                                        {
                                            if (!services.containsKey(instances[i].getName()))
                                            {
                                                services.put(instances[i].getName(), instances[i]);
                                                listenerProcessor.getDispatcher().serviceDiscovered(id, instances[i]);
                                            }
                                        }
                                    }
                                } else
                                {
                                    service = (ServiceInstance) services.get(ptr.getTarget());
                                    if (service != null)
                                    {
                                        services.remove(service.getName());
                                        listenerProcessor.getDispatcher().serviceRemoved(id, service);
                                    }
                                }
                                break;
                        }
                    } catch (IOException e)
                    {
                        System.err.print("error parsing SRV record - " + e.getMessage());
                        if (Options.check("mdns_verbose"))
                        {
                            e.printStackTrace(System.err);
                        }
                    }
                }
            }
        }


        public void handleException(Object id, Exception e)
        {
            listenerProcessor.getDispatcher().handleException(id, e);
        }
        
        
        public void start()
        {
            browser.start(this);
        }


        public void close()
        {
            try
            {
                listenerProcessor.close();
            } catch (IOException e)
            {
                // ignore
            }
            
            try
            {
                browser.close();
            } catch (IOException e)
            {
                // ignore
            }
        }
    }
    
    
    protected ScheduledExecutorService scheduledExecutor = null;

    protected ArrayList<ServiceDiscoveryOperation> discoveryOperations = new ArrayList<ServiceDiscoveryOperation>();
    

    public ServiceInstance register(ServiceInstance service)
    throws IOException
    {
        Register register = new Register(service);
        try
        {
            return register.register();
        } finally
        {
            register.close();
        }
    }
    
    
    public boolean unregister(ServiceInstance service)
    throws IOException
    {
        Unregister unregister = new Unregister(service);
        try
        {
            return unregister.unregister();
        } finally
        {
            unregister.close();
        }
    }
    
    
    /**
     * Starts a Service Discovery Browse Operation and returns an identifier to be used later to stop 
     * the Service Discovery Browse Operation.
     * 
     * @param browser An instance of a Browse object containing the mDNS/DNS Queries
     * @param listener The DNS Service Discovery Listener to which the events are sent.
     * @return An Object that identifies the Service Discovery Browse Operation.
     * @throws IOException
     */
    public Object startServiceDiscovery(Browse browser, DNSSDListener listener)
    throws IOException
    {
        if (scheduledExecutor == null || scheduledExecutor.isShutdown())
        {
            scheduledExecutor = Executors.newScheduledThreadPool(1, new ThreadFactory()
            {
                public Thread newThread(Runnable r)
                {
                    return new Thread(r, "Service Discover Browser Thread");
                }
            });
        }
        browser.setScheduledExecutor(scheduledExecutor);
        
        ServiceDiscoveryOperation discoveryOperation = new ServiceDiscoveryOperation(browser, listener);
        
        synchronized (discoveryOperations)
        {
            this.discoveryOperations.add(discoveryOperation);
        }
        discoveryOperation.start();
        
        return discoveryOperation;
    }
    
    
    /**
     * Stops a Service Discovery Browse Operation.
     * 
     * @param id The object identifying the Service Discovery Browse Operation that was returned by "startServiceDiscovery" 
     * @return true, if the Service Discovery Browse Operation was successfully stopped, otherwise false.
     * @throws IOException
     */
    public boolean stopServiceDiscovery(Object id)
    throws IOException
    {
        synchronized (discoveryOperations)
        {
            int pos = discoveryOperations.indexOf(id);
            if (pos >= 0)
            {
                ServiceDiscoveryOperation discoveryOperation = discoveryOperations.get(pos);
                if (discoveryOperation != null)
                {
                    this.discoveryOperations.remove(pos);
                    discoveryOperation.close();
                    return true;
                }
            }
        }
        
        if (id instanceof ServiceDiscoveryOperation)
        {
            ((ServiceDiscoveryOperation) id).close();
            return true;
        }
        
        return false;
    }


    public void close()
    throws IOException
    {
        for (ServiceDiscoveryOperation discoveryOperation : discoveryOperations)
        {
            try
            {
                discoveryOperation.close();
            } catch (Exception e)
            {
                // ignore
            }
        }
    }
}
