package org.xbill.mDNS;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.MulticastDNSUtils;
import org.xbill.DNS.Name;
import org.xbill.DNS.Options;
import org.xbill.DNS.PTRRecord;
import org.xbill.DNS.Record;
import org.xbill.DNS.ResolverListener;
import org.xbill.DNS.SRVRecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

@SuppressWarnings({"unchecked", "rawtypes"})
public class Browse extends MulticastDNSLookupBase
{
    /**
     * The Browse Operation manages individual browse sessions.  Retrying broadcasts. 
     * Refer to the mDNS specification [RFC 6762]
     * 
     * @author Steve Posick
     */
    protected class BrowseOperation implements ResolverListener, Runnable
    {
        private Message[] queries;
        
        private int broadcastDelay = 0;
        
        private ListenerProcessor<DNSSDListener> listenerProcessor = new ListenerProcessor<DNSSDListener>(DNSSDListener.class);
        
        private Map services = new HashMap();

        private long lastBroadcast;
        
        
        BrowseOperation(Message... query)
        {
            this(null, query);
        }


        BrowseOperation(DNSSDListener listener, Message... query)
        {
            this.queries = query;
            
            if (listener != null)
            {
                registerListener(listener);
            }
        }


        Message[] getQueries()
        {
            return queries;
        }
        
        
        boolean answersQuery(Record record)
        {
            if (record != null)
            {
                for (Message query : queries)
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
        
        
        public void run()
        {
            if (Options.check("mdns_verbose"))
            {
                long now = System.currentTimeMillis();
                System.out.println("Broadcasting Query for Browse." + (lastBroadcast <= 0 ? "" : " Last broadcast was " + ((double) ((double) (now - lastBroadcast) / (double) 1000)) + " seconds ago.") );
                lastBroadcast = System.currentTimeMillis();
            }
            
            try
            {
                broadcastDelay = broadcastDelay > 0 ? Math.min(broadcastDelay * 2, 3600) : 1;
                scheduledExecutor.schedule(this, broadcastDelay, TimeUnit.SECONDS);
                
                if (Options.check("mdns_verbose") || Options.check("mdns_browse"))
                {
                    System.out.println("Broadcasting Query for Browse Operation.");
                }
                
                for (Message query : queries)
                {
                    querier.broadcast((Message) query.clone(), false);
                }
            } catch (Exception e)
            {
                System.err.println("Error broadcasting query for browse - " + e.getMessage());
                e.printStackTrace(System.err);
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
        }
    }
    
    protected List browseOperations = new LinkedList();

    protected ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(1, new ThreadFactory()
    {
        public Thread newThread(Runnable r)
        {
            return new Thread(r, "mDNSResolver Scheduled Thread");
        }
    });
    

    protected Browse()
    throws IOException
    {
        super();
    }


    public Browse(Name... names)
    throws IOException
    {
        super(names);
    }
    
    
    public Browse(Name[] names, int type)
    throws IOException
    {
        super(names, type);
    }
    
    
    public Browse(Name[] names, int type, int dclass)
    throws IOException
    {
        super(names, type, dclass);
    }
    
    
    protected Browse(Message message)
    throws IOException
    {
        super(message);
    }
    
    
    public Browse(String... names)
    throws IOException
    {
        super(names);
    }
    
    
    public Browse(String[] names, int type)
    throws IOException
    {
        super(names, type);
    }
    
    
    public Browse(String[] names, int type, int dclass)
    throws IOException
    {
        super(names, type, dclass);
    }


    /**
     * @param listener
     * @throws IOException
     */
    public synchronized void start(DNSSDListener listener)
    throws IOException
    {
        if (listener == null)
        {
            if (Options.check("mdns_verbose"))
            {
                System.err.println("Error sending asynchronous query, listener is null!");
            }
            throw new NullPointerException("Error sending asynchronous query, listener is null!");
        }
        
        if (queries == null || queries.length == 0)
        {
            if (Options.check("mdns_verbose"))
            {
                System.err.println("Error sending asynchronous query, No queries specified!");
            }
            throw new NullPointerException("Error sending asynchronous query, No queries specified!");
        }
        
        BrowseOperation browseOperation = new BrowseOperation(listener, queries);
        browseOperations.add(browseOperation);
        querier.registerListener(browseOperation);
        browseOperation.registerListener(listener);
        
        scheduledExecutor.submit(browseOperation);
    }


    public void close()
    throws IOException
    {
        try
        {
            scheduledExecutor.shutdown();
        } catch (Exception e)
        {
            // ignore
        }
        
        for (Object o : browseOperations)
        {
            BrowseOperation browseOperation = (BrowseOperation) o;
            try
            {
                browseOperation.close();
            } catch (Exception e)
            {
                // ignore
            }
        }
    }
}