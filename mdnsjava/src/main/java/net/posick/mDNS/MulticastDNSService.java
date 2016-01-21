package net.posick.mDNS;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

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

import net.posick.mDNS.Lookup.Domain;
import net.posick.mDNS.utils.Executors;
import net.posick.mDNS.utils.ListenerProcessor;

@SuppressWarnings({"unchecked", "rawtypes"})
public class MulticastDNSService extends MulticastDNSLookupBase
{
    protected class Register
    {
        private final ServiceInstance service;
        
        
        protected Register(final ServiceInstance service)
        throws UnknownHostException
        {
            super();
            this.service = service;
        }
        
        
        protected void close()
        throws IOException
        {
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
             * 1. Query the service name of type ANY. Ex. Test._mdc._tcp.local IN ANY Flags: QM
             * a. Add the Service Record to the Authoritative section.
             * b. Repeat 3 queries with a 250 millisecond delay between each query.
             * 2. Send a standard Query Response containing the service records, Opcode: QUERY, Flags: QR, AA, NO ERROR
             * a. Add TXT record to ANSWER section. TTL: 3600
             * b. Add SRV record to ANSWER section. TTL: 120
             * c. Add DNS-SD Services PTR record to ANSWER section. TTL: 3600 Ex. _services._dns-sd.udp.local. IN PTR _mdc._tcp.local.
             * d. Add PTR record to ANSWER section. Ex. _mdc._tcp.local. IN PTR Test._mdc._tcp.local. TTL: 3600
             * e. Add A record to ADDITIONAL section. TTL: 120 Ex. hostname.local. IN A 192.168.1.83
             * f. Add AAAA record to ADDITIONAL section. TTL: 120 Ex. hostname.local. IN AAAA fe80::255:ff:fe4a:6369
             * g. Add NSEC record to ADDITIONAL section. TTL: 120 Ex. hostname.local. IN NSEC next domain: hostname.local. RRs: A AAAA
             * h. Add NSEC record to ADDITIONAL section. TTL: 3600 Ex. Test._mdc._tcp.local. IN NSEC next domain: Test._mdc._tcp.local. RRs: TXT SRV
             * b. Repeat 3 queries with a 2 second delay between each query response.
             */
            final List replies = new ArrayList();
            Message query = Message.newQuery(Record.newRecord(service.getName(), Type.ANY, DClass.IN));
            SRVRecord srvRecord = new SRVRecord(service.getName(), DClass.IN, 3600, 0, 0, service.getPort(), service.getHost());
            query.addRecord(srvRecord, Section.AUTHORITY);
            // TODO: Add support for Unicast answers for first query mDNS.createQuery(DClass.IN + 0x8000, Type.ANY, service.getName());
            
            int tries = 0;
            while (tries++ < 3)
            {
                querier.sendAsync(query, new ResolverListener()
                {
                    public void handleException(final Object id, final Exception e)
                    {
                        synchronized (replies)
                        {
                            replies.add(e);
                            replies.notifyAll();
                        }
                    }
                    
                    
                    public void receiveMessage(final Object id, final Message m)
                    {
                        synchronized (replies)
                        {
                            replies.add(m);
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
                                    for (int r = 0; r < records.length; r++ )
                                    {
                                        if ((records[r].getType() == Type.SRV) && (records[r].getTTL() > 0))
                                        {
                                            if (!srvRecord.equals(records[r]))
                                            {
                                                // Another Service with this same name was found, so registration must fail.
                                                return null;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            replies.clear();
            
            ServiceName serviceName = service.getName();
            Name domain = new Name(serviceName.getDomain());
            final Update[] updates = new Update[] {new Update(domain),
                                                   new Update(domain)};
            Name fullTypeName = new Name(serviceName.getFullType() + "." + domain);
            Name typeName = new Name(serviceName.getType() + "." + domain);
            ServiceName shortSRVName = new ServiceName(serviceName.getInstance(), typeName);
            
            try
            {
                
                ArrayList<Record> records = new ArrayList<Record>();
                ArrayList<Record> additionalRecords = new ArrayList<Record>();
                
                InetAddress[] addresses = service.getAddresses();
                
                if (addresses != null)
                {
                    for (int index = 0; index < addresses.length; index++ )
                    {
                        if (addresses[index] != null)
                        {
                            if (addresses[index].getAddress().length == 4)
                            {
                                additionalRecords.add(new ARecord(service.getHost(), DClass.IN + CACHE_FLUSH, DEFAULT_A_TTL, addresses[index]));
                            } else
                            {
                                additionalRecords.add(new AAAARecord(service.getHost(), DClass.IN + CACHE_FLUSH, DEFAULT_A_TTL, addresses[index]));
                            }
                        }
                    }
                }
                
                /*
                 * Old
                 * PTRRecord ptrType = new PTRRecord(typeName, DClass.IN, DEFAULT_SRV_TTL, serviceName);
                 * PTRRecord ptrFullName = new PTRRecord(fullTypeName, DClass.IN, DEFAULT_SRV_TTL, serviceName);
                 * SRVRecord srv = new SRVRecord(serviceName, DClass.IN + CACHE_FLUSH, DEFAULT_SRV_TTL, 0, 0, service.getPort(), service.getHost());
                 * TXTRecord txt = new TXTRecord(serviceName, DClass.IN + CACHE_FLUSH, DEFAULT_TXT_TTL, Arrays.asList(service.getText()));
                 * NSECRecord serviceNSEC = new NSECRecord(serviceName, DClass.IN + CACHE_FLUSH, DEFAULT_RR_WITHOUT_HOST_TTL, serviceName, new int[]{Type.TXT,
                 * Type.SRV});
                 * NSECRecord addressNSEC = new NSECRecord(service.getHost(), DClass.IN + CACHE_FLUSH, DEFAULT_RR_WITH_HOST_TTL, service.getHost(), new
                 * int[]{Type.A, Type.AAAA});
                 * 
                 * update.add(txt);
                 * update.add(srv);
                 * update.add(serviceTypeReg1);
                 * update.add(serviceTypeReg2);
                 * update.add(ptrType);
                 * update.add(ptrFulName);
                 * 
                 * update.addRecord(addressNSEC, Section.ADDITIONAL);
                 * update.addRecord(serviceNSEC, Section.ADDITIONAL);
                 */
                
                // Add Service and Pointer Records
                /*
                 * Original Working code!
                 * records.add(new SRVRecord(serviceName, DClass.IN + CACHE_FLUSH, DEFAULT_SRV_TTL, 0, 0, service.getPort(), service.getHost()));
                 * records.add(new TXTRecord(serviceName, DClass.IN + CACHE_FLUSH, DEFAULT_TXT_TTL, Arrays.asList(service.getText())));
                 * records.add(new PTRRecord(typeName, DClass.IN, DEFAULT_SRV_TTL, serviceName));
                 * if (!fullTypeName.equals(typeName))
                 * {
                 * records.add(new PTRRecord(fullTypeName, DClass.IN, DEFAULT_SRV_TTL, serviceName));
                 * // For compatibility with legacy clients, register the NON sub-protocol service as well.
                 * if (shortSRVName != null && !shortSRVName.equals(serviceName))
                 * {
                 * records.add(new PTRRecord(typeName, DClass.IN, DEFAULT_SRV_TTL, shortSRVName));
                 * records.add(new SRVRecord(shortSRVName, DClass.IN + CACHE_FLUSH, DEFAULT_SRV_TTL, 0, 0, service.getPort(), service.getHost()));
                 * records.add(new TXTRecord(shortSRVName, DClass.IN + CACHE_FLUSH, DEFAULT_TXT_TTL, Arrays.asList(service.getText())));
                 * additionalRecords.add(new NSECRecord(shortSRVName, DClass.IN + CACHE_FLUSH, DEFAULT_RR_WITHOUT_HOST_TTL, shortSRVName, new int[]{Type.TXT,
                 * Type.SRV}));
                 * }
                 * }
                 */
                if (!fullTypeName.equals(typeName))
                {
                    records.add(new PTRRecord(fullTypeName, DClass.IN, DEFAULT_SRV_TTL, shortSRVName));
                }
                // For compatibility with legacy clients, register the NON sub-protocol service name.
                records.add(new PTRRecord(typeName, DClass.IN, DEFAULT_SRV_TTL, shortSRVName));
                records.add(new SRVRecord(shortSRVName, DClass.IN + CACHE_FLUSH, DEFAULT_SRV_TTL, 0, 0, service.getPort(), service.getHost()));
                records.add(new TXTRecord(shortSRVName, DClass.IN + CACHE_FLUSH, DEFAULT_TXT_TTL, Arrays.asList(service.getText())));
                additionalRecords.add(new NSECRecord(shortSRVName, DClass.IN + CACHE_FLUSH, DEFAULT_RR_WITHOUT_HOST_TTL, shortSRVName, new int[] {Type.TXT,
                                                                                                                                                  Type.SRV}));
                
                // Add Security (NSEC) records
                // Original additionalRecords.add(new NSECRecord(serviceName, DClass.IN + CACHE_FLUSH, DEFAULT_RR_WITHOUT_HOST_TTL, serviceName, new
                // int[]{Type.TXT, Type.SRV}));
                additionalRecords.add(new NSECRecord(shortSRVName, DClass.IN + CACHE_FLUSH, DEFAULT_RR_WITHOUT_HOST_TTL, shortSRVName, new int[] {Type.TXT,
                                                                                                                                                  Type.SRV}));
                additionalRecords.add(new NSECRecord(service.getHost(), DClass.IN + CACHE_FLUSH, DEFAULT_RR_WITH_HOST_TTL, service.getHost(), new int[] {Type.A,
                                                                                                                                                         Type.AAAA}));
                
                for (Record record : records)
                {
                    updates[0].add(record);
                }
                
                for (Record record : additionalRecords)
                {
                    updates[0].addRecord(record, Section.ADDITIONAL);
                }
                
                records.clear();
                additionalRecords.clear();
                
                // Register Service Types in a separate request!
                records.add(new PTRRecord(new Name(SERVICES_NAME + "." + domain), DClass.IN, DEFAULT_SRV_TTL, typeName));
                if (!fullTypeName.equals(typeName))
                {
                    records.add(new PTRRecord(new Name(SERVICES_NAME + "." + domain), DClass.IN, DEFAULT_SRV_TTL, fullTypeName));
                }
                
                for (Record record : records)
                {
                    updates[1].add(record);
                }
                
                // Updates are sent at least 2 times, one second apart, as per RFC 6762 Section 8.3
                ResolverListener resolverListener = new ResolverListener()
                {
                    public void handleException(final Object id, final Exception e)
                    {
                        synchronized (replies)
                        {
                            replies.add(e);
                            replies.notifyAll();
                        }
                    }
                    
                    
                    public void receiveMessage(final Object id, final Message m)
                    {
                        synchronized (replies)
                        {
                            replies.add(m);
                            replies.notifyAll();
                        }
                    }
                };
                
                tries = 0;
                while (tries++ < 2)
                {
                    querier.sendAsync(updates[0], resolverListener);
                    
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
                querier.sendAsync(updates[1], resolverListener);
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
            while ((instances == null) && (System.currentTimeMillis() < endTime))
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
                
                // Origonal Lookup lookup = new Lookup(new Name[]{serviceName}, Type.ANY);
                Lookup lookup = new Lookup(new Name[] {shortSRVName}, Type.ANY);
                try
                {
                    instances = lookup.lookupServices();
                    
                    if ((instances != null) && (instances.length > 0))
                    {
                        if (instances.length > 1)
                        {
                            if (Options.check("mdns_verbose"))
                            {
                                System.err.println("Warning: Somehow more than one service with the name \"" + shortSRVName + "\" was registered.");
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
    }
    
    
    /**
     * The Browse Operation manages individual browse sessions. Retrying broadcasts.
     * Refer to the mDNS specification [RFC 6762]
     * 
     * @author Steve Posick
     */
    protected class ServiceDiscoveryOperation implements ResolverListener
    {
        private final Browse browser;
        
        private final ListenerProcessor<DNSSDListener> listenerProcessor = new ListenerProcessor<DNSSDListener>(DNSSDListener.class);
        
        private final Map services = new LinkedHashMap();
        
        
        ServiceDiscoveryOperation(final Browse browser)
        {
            this(browser, null);
        }
        
        
        ServiceDiscoveryOperation(final Browse browser, final DNSSDListener listener)
        {
            this.browser = browser;
            
            if (listener != null)
            {
                registerListener(listener);
            }
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
        
        
        public void handleException(final Object id, final Exception e)
        {
            listenerProcessor.getDispatcher().handleException(id, e);
        }
        
        
        public void receiveMessage(final Object id, final Message message)
        {
            if (message == null)
            {
                return;
            }
            
            // Strip the records that are not related to the query.
            Set<Name> additionalNames = new LinkedHashSet<Name>();
            List<Record> ignoredRecords = new LinkedList<Record>();
            List<Record> filteredRecords = new LinkedList<Record>();
            Record[] thatAnswers = MulticastDNSUtils.extractRecords(message, Section.ANSWER, Section.AUTHORITY, Section.ADDITIONAL);
            for (Record record : thatAnswers)
            {
                if (answersQuery(record))
                {
                    Name additionalName = record.getAdditionalName();
                    if (additionalName != null)
                    {
                        additionalNames.add(additionalName);
                    }
                    
                    switch (record.getType())
                    {
                        case Type.PTR:
                            PTRRecord ptr = (PTRRecord) record;
                            additionalNames.add(ptr.getTarget());
                            break;
                        case Type.SRV:
                            SRVRecord srv = (SRVRecord) record;
                            additionalNames.add(srv.getTarget());
                            break;
                        default:
                            // ignore
                            break;
                    }
                    filteredRecords.add(record);
                } else
                {
                    ignoredRecords.add(record);
                }
            }
            
            for (Record record : ignoredRecords)
            {
                if (additionalNames.contains(record.getName()))
                {
                    filteredRecords.add(record);
                }
            }
            
            if (filteredRecords.size() > 0)
            {
                listenerProcessor.getDispatcher().receiveMessage(id, message);
                
                Map<Name, ServiceInstance> foundServices = new HashMap<Name, ServiceInstance>();
                Map<Name, ServiceInstance> removedServices = new HashMap<Name, ServiceInstance>();
                
                for (Record record : filteredRecords)
                {
                    try
                    {
                        ServiceInstance service = null;
                        
                        switch (record.getType())
                        {
                            case Type.PTR:
                                PTRRecord ptr = (PTRRecord) record;
                                
                                if (ptr.getTTL() > 0)
                                {
                                    ServiceInstance[] instances = extractServiceInstances(querier.send(Message.newQuery(Record.newRecord(ptr.getTarget(), Type.ANY, dclass))));
                                    if (instances.length > 0)
                                    {
                                        synchronized (services)
                                        {
                                            for (int i = 0; i < instances.length; i++ )
                                            {
                                                if (!services.containsKey(instances[i].getName()))
                                                {
                                                    services.put(instances[i].getName(), instances[i]);
                                                    foundServices.put(instances[i].getName(), instances[i]);
                                                }
                                            }
                                        }
                                    }
                                } else
                                {
                                    synchronized (services)
                                    {
                                        service = (ServiceInstance) services.get(ptr.getTarget());
                                        if (service != null)
                                        {
                                            services.remove(service.getName());
                                            removedServices.put(service.getName(), service);
                                        }
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
                // TODO: Check found services against already found services!
                for (ServiceInstance service : foundServices.values())
                {
                    try
                    {
                        listenerProcessor.getDispatcher().serviceDiscovered(id, service);
                    } catch (Exception e)
                    {
                        System.err.print("Error sending serviceDiscovered event - " + e.getMessage());
                        if (Options.check("mdns_verbose"))
                        {
                            e.printStackTrace(System.err);
                        }
                    }
                }
                
                for (ServiceInstance service : removedServices.values())
                {
                    try
                    {
                        listenerProcessor.getDispatcher().serviceRemoved(id, service);
                    } catch (Exception e)
                    {
                        System.err.print("Error sending serviceRemoved event - " + e.getMessage());
                        if (Options.check("mdns_verbose"))
                        {
                            e.printStackTrace(System.err);
                        }
                    }
                }
            }
        }
        
        
        public void start()
        {
            browser.start(this);
        }
        
        
        boolean answersQuery(final Record record)
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
                        
                        if (((questionType == Type.ANY) || (questionType == recordType)) && (questionName.equals(recordName) || questionName.subdomain(recordName) || recordName.toString().endsWith("." + questionName.toString())) && ((questionDClass == DClass.ANY) || ((questionDClass & 0x7FFF) == (recordDClass & 0x7FFF))))
                        {
                            return true;
                        }
                    }
                }
            }
            
            return false;
        }
        
        
        Browse getBrowser()
        {
            return browser;
        }
        
        
        boolean matchesBrowse(final Message message)
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
        
        
        DNSSDListener registerListener(final DNSSDListener listener)
        {
            return listenerProcessor.registerListener(listener);
        }
        
        
        DNSSDListener unregisterListener(final DNSSDListener listener)
        {
            return listenerProcessor.unregisterListener(listener);
        }
    }
    
    
    protected class Unregister
    {
        private final ServiceName serviceName;
        
        
        protected Unregister(final ServiceInstance service)
        {
            this(service.getName());
        }
        
        
        protected Unregister(final ServiceName serviceName)
        {
            super();
            this.serviceName = serviceName;
        }
        
        
        protected void close()
        throws IOException
        {
        }
        
        
        protected boolean unregister()
        throws IOException
        {
            /*
             * Steps to Registering a Service.
             * 
             * 1. Send a standard Query Response containing the service records, Opcode: QUERY, Flags: Response, Authoritative, NO ERROR
             * a. Add PTR record to ANSWER section. TTL: 0 Ex. _mdc._tcp.local. IN PTR Test._mdc._tcp.local.
             * b. Repeat 3 queries with a 2 second delay between each query response.
             */
            String domain = serviceName.getDomain();
            Name fullTypeName = new Name(serviceName.getFullType() + "." + domain);
            Name typeName = new Name(serviceName.getType() + "." + domain);
            ServiceName shortSRVName = new ServiceName(serviceName.getInstance(), typeName);
            
            ArrayList<Record> records = new ArrayList<Record>();
            ArrayList<Record> additionalRecords = new ArrayList<Record>();
            
            records.add(new PTRRecord(typeName, DClass.IN, 0, serviceName));
            if (!fullTypeName.equals(typeName))
            {
                records.add(new PTRRecord(fullTypeName, DClass.IN, 0, serviceName));
                records.add(new PTRRecord(typeName, DClass.IN, 0, shortSRVName));
            }
            
            Update update = new Update(new Name(domain));
            for (Record record : records)
            {
                update.add(record);
            }
            
            for (Record record : additionalRecords)
            {
                update.addRecord(record, Section.ADDITIONAL);
            }
            
            // Updates are sent at least 2 times, one second apart, as per RFC 6762 Section 8.3
            ResolverListener resolverListener = new ResolverListener()
            {
                public void handleException(final Object id, final Exception e)
                {
                }
                
                
                public void receiveMessage(final Object id, final Message m)
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
            
            Lookup lookup = new Lookup(new Name[] {serviceName.getServiceTypeName()}, Type.PTR, DClass.ANY);
            try
            {
                Record[] results = lookup.lookupRecords();
                return (results == null) || (results.length == 0);
            } finally
            {
                lookup.close();
            }
        }
    }
    
    
    protected Executors executors = Executors.newInstance();
    
    
    protected ArrayList<ServiceDiscoveryOperation> discoveryOperations = new ArrayList<ServiceDiscoveryOperation>();
    
    
    public MulticastDNSService()
    throws IOException
    {
        super();
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
    
    public Set<Domain> getBrowseDomains(final Set<Name> searchPath)
    {
        Set<Domain> results = new LinkedHashSet<Domain>();
        Name[] defaultDomains = Constants.ALL_MULTICAST_DNS_DOMAINS;
        for (Name name : defaultDomains)
        {
            results.add(new Domain(name));
        }
        results.addAll(getDomains(new String[] {Constants.DEFAULT_BROWSE_DOMAIN_NAME,
                                                Constants.BROWSE_DOMAIN_NAME,
                                                Constants.LEGACY_BROWSE_DOMAIN_NAME}, searchPath.toArray(new Name[searchPath.size()])));
        return results;
    }
    
    public Set<Domain> getDefaultBrowseDomains(final Set<Name> searchPath)
    {
        Set<Domain> results = new LinkedHashSet<Domain>();
        Name[] defaultDomains = Constants.ALL_MULTICAST_DNS_DOMAINS;
        for (Name name : defaultDomains)
        {
            results.add(new Domain(name));
        }
        searchPath.addAll(Arrays.asList(Constants.ALL_MULTICAST_DNS_DOMAINS));
        results.addAll(getDomains(new String[] {Constants.DEFAULT_BROWSE_DOMAIN_NAME}, searchPath.toArray(new Name[searchPath.size()])));
        return results;
    }
    
    
    public Set<Domain> getDefaultRegistrationDomains(final Set<Name> searchPath)
    {
        Set<Domain> results = new LinkedHashSet<Domain>();
        Name[] defaultDomains = Constants.ALL_MULTICAST_DNS_DOMAINS;
        for (Name name : defaultDomains)
        {
            results.add(new Domain(name));
        }
        searchPath.addAll(Arrays.asList(Constants.ALL_MULTICAST_DNS_DOMAINS));
        results.addAll(getDomains(new String[] {Constants.DEFAULT_REGISTRATION_DOMAIN_NAME}, searchPath.toArray(new Name[searchPath.size()])));
        return results;
    }
    
    
    public Set<Domain> getRegistrationDomains(final Set<Name> searchPath)
    {
        Set<Domain> results = new LinkedHashSet<Domain>();
        Name[] defaultDomains = Constants.ALL_MULTICAST_DNS_DOMAINS;
        for (Name name : defaultDomains)
        {
            results.add(new Domain(name));
        }
        results.addAll(getDomains(new String[] {Constants.DEFAULT_REGISTRATION_DOMAIN_NAME,
                                                Constants.REGISTRATION_DOMAIN_NAME}, searchPath.toArray(new Name[searchPath.size()])));
        return results;
    }
    
    
    public ServiceInstance register(final ServiceInstance service)
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
    
    
    /**
     * Starts a Service Discovery Browse Operation and returns an identifier to be used later to stop
     * the Service Discovery Browse Operation.
     * 
     * @param browser An instance of a Browse object containing the mDNS/DNS Queries
     * @param listener The DNS Service Discovery Listener to which the events are sent.
     * @return An Object that identifies the Service Discovery Browse Operation.
     * @throws IOException
     */
    public Object startServiceDiscovery(final Browse browser, final DNSSDListener listener)
    throws IOException
    {
        ServiceDiscoveryOperation discoveryOperation = new ServiceDiscoveryOperation(browser, listener);
        
        synchronized (discoveryOperations)
        {
            discoveryOperations.add(discoveryOperation);
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
    public boolean stopServiceDiscovery(final Object id)
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
                    discoveryOperations.remove(pos);
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
    
    
    public boolean unregister(final ServiceInstance service)
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
    
    
    protected Set<Domain> getDomains(final String[] names, final Name[] path)
    {
        Set<Domain> results = new LinkedHashSet<Domain>();
        
        Stack<Name[]> stack = new Stack<Name[]>();
        stack.push(path);
        
        while (!stack.isEmpty())
        {
            Name[] searchPath = stack.pop();
            
            Lookup lookup = null;
            try
            {
                lookup = new Lookup(names);
                lookup.setSearchPath(searchPath);
                lookup.setQuerier(querier);
                Domain[] domains = lookup.lookupDomains();
                if ((domains != null) && (domains.length > 0))
                {
                    List<Name> newDomains = new ArrayList<Name>();
                    for (int index = 0; index < domains.length; index++ )
                    {
                        if (!results.contains(domains[index].getName()))
                        {
                            newDomains.add(domains[index].getName());
                            results.add(domains[index]);
                        }
                    }
                    if (newDomains.size() > 0)
                    {
                        stack.push(newDomains.toArray(new Name[newDomains.size()]));
                    }
                }
            } catch (IOException e)
            {
                System.err.println(e.getMessage());
                e.printStackTrace(System.err);
            } finally
            {
                if (lookup != null)
                {
                    try
                    {
                        lookup.close();
                    } catch (Exception e)
                    {
                        // ignore
                    }
                }
            }
        }
        
        return results;
    }
    
    
    public static boolean hasMulticastDomains(final Message query)
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
    
    
    public static boolean hasUnicastDomains(final Message query)
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
    
    
    public static boolean isMulticastDomain(final Name name)
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
}
