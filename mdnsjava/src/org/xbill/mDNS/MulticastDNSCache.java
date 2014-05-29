package org.xbill.mDNS;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.xbill.DNS.Cache;
import org.xbill.DNS.Credibility;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Header;
import org.xbill.DNS.Message;
import org.xbill.DNS.MulticastDNSUtils;
import org.xbill.DNS.Name;
import org.xbill.DNS.Opcode;
import org.xbill.DNS.Options;
import org.xbill.DNS.RRset;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.SetResponse;
import org.xbill.DNS.Type;

/**
 * A cache of mDNS records and responses. The cache obeys TTLs, so items are
 * purged after their validity period is complete. Negative answers are cached,
 * to avoid repeated failed DNS queries. The credibility of each RRset is
 * maintained, so that more credible records replace less credible records, and
 * lookups can specify the minimum credibility of data they are requesting.
 * 
 * !!! This cache implementation extends the Cache class.  Reflection had to be used to access 
 * !!! private fields and methods in the Cache class that prevent its direct extensibility. 
 * 
 * @see Cache
 * 
 * @author Steve Posick
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class MulticastDNSCache extends Cache implements Closeable
{
    protected static class ElementHelper
    {
        private Cache cache = null;
        
        private Object element = null;
        
        private Class clazz = null;
        
        private Method expired = null;
        
        private Method compareCredibility = null;
        
        private Method getType = null;
        
        private Method getTTL = null;
        
        private Field expireField = null;
        
        private Field credibilityField = null;
        
        
        protected ElementHelper(Cache cache, Object element) 
        throws SecurityException, NoSuchMethodException, NoSuchFieldException, 
               IllegalArgumentException, IllegalAccessException
        {
            this.cache = cache;
            this.element = element;
            this.clazz = element.getClass();
            
            expireField = findField(clazz, "expire");
            credibilityField = findField(clazz, "credibility");
            
            expired = findMethod(clazz, "expired", new Class[0]);
            compareCredibility = findMethod(clazz, "compareCredibility", new Class[]{Integer.TYPE});
            getType = findMethod(clazz, "getType", new Class[0]);
            getTTL = findMethod(clazz, "getTTL", new Class[0]);
            
            Field.setAccessible(new AccessibleObject[]{expireField, credibilityField}, true);
            Method.setAccessible(new AccessibleObject[]{expired, compareCredibility, getType, getTTL, 
                                                        expireField, credibilityField}, true);
        }
        
        
        protected boolean expired() 
        throws IllegalArgumentException, IllegalAccessException, InvocationTargetException
        {
            return (Boolean) expired.invoke(element, new Object[0]);
        }
        
        
        protected int compareCredibility(int cred)
        throws IllegalArgumentException, IllegalAccessException, InvocationTargetException
        {
            return (Integer) compareCredibility.invoke(element, new Object[]{cred});
        }
        
        
        protected int getType()
        throws IllegalArgumentException, IllegalAccessException, InvocationTargetException
        {
            return (Integer) getType.invoke(element, new Object[0]);
        }
        
        protected Object getElement()
        {
            return element;
        }
        
        
        protected int getExpire()
        throws IllegalArgumentException, IllegalAccessException
        {
            return expireField.getInt(element);
        }


        public int getExpiresIn()
        throws IllegalArgumentException, IllegalAccessException
        {
            return getExpire() - (int)(System.currentTimeMillis() / 1000);
        }


        public int getCredibility() 
        throws IllegalArgumentException, IllegalAccessException
        {
            return credibilityField.getInt(element);
        }


        public void resetExpire()
        throws IllegalArgumentException, IllegalAccessException, InvocationTargetException
        {
            expireField.setInt(element, limitExpire(getTTL(), cache.getMaxCache()));
        }


        public long getTTL() 
        throws IllegalArgumentException, IllegalAccessException, InvocationTargetException
        {
            if (getTTL != null)
            {
                Long value = (Long) getTTL.invoke(element,new Object[0]);
                return value == null ? 0L : value.longValue();
            } else
            {
                return 0;
            }
        }
    }
    
    
    /**
     * The CacheCheck interface allows a client to monitor the Cache data
     * and implement a cache refresh mechanism, such as what is required for
     * mDNS Caching. See RFC 6762 Section 5.2
     * 
     * @author Steve Posick
     */
    public static interface CacheMonitor
    {
        /**
         * Called before a RRset is removed.
         * 
         * @param rrs The RRset
         * @param credibility The credibility of the RRset
         * @param expiresIn The time remaining before the record expires.
         * @return true if the RRset can be removed from the cache
         */
        public void check(RRset rrs, int credibility, int expiresIn);
        
        
        /**
         * Called for RRsets that have expired.
         * 
         * @param rrs The expired RRset
         * @param credibility The credibility of the RRset
         */
        public void expired(RRset rrs, int credibility);
        
        
        /**
         * Called at the beginning of a Monitor task.  Clears the state and prepares the CacheMonitor
         * for the receipt of check and expired calls.  
         */
        public void begin();
        
        
        /**
         * Called at the end of a Monitor task.  Clears the state, releases resources, and performs
         * refresh of items identified as requiring a refresh.
         */
        public void end();
        
        
        /**
         * Returns true if the CacheMonitor is operational (being called regularly).
         * 
         * @return true if the CacheMonitor is operational (being called regularly)
         */
        public boolean isOperational();
    }
    
    
    private static class MonitorTask implements Runnable
    {
        private boolean shutdown = false;
        
        private MulticastDNSCache cache;
        
        private boolean mdnsVerbose = false;
        
        
        MonitorTask(MulticastDNSCache cache)
        {
            this(cache, false);
        }
        
        
        MonitorTask(MulticastDNSCache cache, boolean shutdown)
        {
            this.cache = cache;
            this.shutdown = false;
            
            mdnsVerbose = Options.check("mdns_verbose");
        }
        
        
        public void run()
        {
            try
            {
                CacheMonitor cacheMonitor = cache.getCacheMonitor();
                if (cacheMonitor == null || shutdown)
                {
                    return;
                }
                
                try
                {
                    cacheMonitor.begin();
                } catch (Exception e)
                {
                    if (mdnsVerbose)
                    {
                        System.err.println(e.getMessage());
                        e.printStackTrace(System.err);
                    }
                }
                
                Object[] sets;
                synchronized (cache)
                {
                    Collection values = cache.dataCopy.values();
                    sets = values.toArray(new Object[values.size()]);
                }
                
                for (int index = 0; index < sets.length; index++)
                {
                    try
                    {
                        Object types = sets[index];
                        if (types instanceof List)
                        {
                            List list = (List) types;
                            Object[] elements;
                            synchronized (cache)
                            {
                                elements = (Object[]) list.toArray(new Object[list.size()]);
                            }
                            for (int eIndex = 0; eIndex < elements.length; eIndex++)
                            {
                                processElement(new ElementHelper(cache, elements[eIndex]));
                            }
                        } else
                        {
                            processElement(new ElementHelper(cache, types));
                        }
                    } catch (Exception e)
                    {
                        if (mdnsVerbose)
                        {
                            System.err.println(e.getMessage());
                            e.printStackTrace(System.err);
                        }
                    }
                }
                
                try
                {
                    cacheMonitor.end();
                } catch (Exception e)
                {
                    if (mdnsVerbose)
                    {
                        System.err.println(e.getMessage());
                        e.printStackTrace(System.err);
                    }
                }
            } catch (Throwable e)
            {
                System.err.println(e.getMessage());
                e.printStackTrace(System.err);
            }
        }
        
        
        private void processElement(ElementHelper element)
        {
            try
            {
                if (element.getElement() instanceof RRset)
                {
                    RRset rrs = (RRset) element.getElement();
                    
                    if (shutdown)
                    {
                        Record[] records = MulticastDNSUtils.extractRecords(rrs);
                        for (Record record : records)
                        {
                            if (element.getCredibility() >= Credibility.AUTH_AUTHORITY)
                            {
                                MulticastDNSUtils.setTLLForRecord(record, 0);
                            }
                        }
                    }
                    
                    CacheMonitor cacheMonitor = cache.getCacheMonitor();
                    int expiresIn = element.getExpiresIn();
                    if (expiresIn <= 0 || rrs.getTTL() <= 0)
                    {
                        cacheMonitor.expired(rrs, element.getCredibility());
                    } else
                    {
                        cacheMonitor.check(rrs, element.getCredibility(), expiresIn);
                    }
                } else
                {
                    System.err.println("Element is an unexpected type \"" + (element.getElement() != null ? element.getElement().getClass().getName() : "null") + "\"");
                }
            } catch (Exception e)
            {
                System.err.println(e.getMessage());
                e.printStackTrace(System.err);
            }
        }
    }
    
    protected final static MulticastDNSCache DEFAULT_MDNS_CACHE;
    
    
    static
    {
        MulticastDNSCache temp = null;
        try
        {
            try
            {
                temp = new MulticastDNSCache(MulticastDNSMulticastOnlyQuerier.class.getSimpleName());
            } catch (IOException e)
            {
                temp = new MulticastDNSCache();
                
                if (Options.check("mdns_verbose") || Options.check("verbose"))
                {
                    System.err.println("Error loading default cache values.");
                    e.printStackTrace(System.err);
                }
            }
        } catch (NoSuchFieldException e)
        {
            System.err.println("Unrecoverable Error: The base " + Cache.class + " class does not implement required fields!");
            e.printStackTrace();
        } catch (NoSuchMethodException e)
        {
            System.err.println("Unrecoverable Error: The base " + Cache.class + " class does not implement required methods!");
            e.printStackTrace();
        }
        
        DEFAULT_MDNS_CACHE = temp; 
    }
    
    
    private CacheMonitor cacheMonitor = null;
    
    private ScheduledExecutorService executor = null;
    
    private LinkedHashMap dataCopy;
    
    private Field dataField = null;
    
    private Method findElement = null;
    
    private Method removeElement = null;

    private boolean mdnsVerbose;
    
    
    /**
     * Creates an empty Cache
     * 
     * @param dclass The DNS class of this cache
     * @throws NoSuchFieldException 
     * @see DClass
     */
    public MulticastDNSCache(int dclass)
    throws NoSuchFieldException, NoSuchMethodException
    {
        super(dclass);
        populateReflectedFields();
    }
    
    
    /**
     * Creates an empty Cache for class IN.
     * @throws NoSuchFieldException 
     * 
     * @see DClass
     */
    public MulticastDNSCache()
    throws NoSuchFieldException, NoSuchMethodException
    {
        super();
        populateReflectedFields();
    }
    
    
    /**
     * Creates a Cache which initially contains all records in the specified
     * file.
     * @throws IOException 
     * @throws IllegalAccessException 
     * @throws NoSuchFieldException 
     * @throws IllegalArgumentException 
     * @throws SecurityException 
     */
    public MulticastDNSCache(String file) 
    throws IOException, NoSuchFieldException, NoSuchMethodException
    {
        super(file);
        populateReflectedFields();
    }
    
    
    /**
     * Initializes a new mDNSCahce with the records from the provided Cache.
     * 
     * @param cache The Cache to use to populate this mDNSCache.
     * @throws NoSuchFieldException if the "data" field does not exist in either Cache
     * @throws IllegalAccessException if the "data" field could not be accessed in either Cache
     */
    MulticastDNSCache(Cache cache)
    throws NoSuchFieldException, NoSuchMethodException, IllegalAccessException
    {
        this();
        
        Field field = cache.getClass().getDeclaredField("data");
        field.setAccessible(true);
        Object cacheData = field.get(cache);
        
        field = super.getClass().getDeclaredField("data");
        field.setAccessible(true);
        field.set(this, cacheData);
        
        populateReflectedFields();
    }
    
    
    public boolean isOperational()
    {
        return executor != null && !executor.isShutdown() && !executor.isTerminated(); 
    }
    
    
    /**
     * !!! Work around to access private methods in Cache superclass
     * !!! This method will be removed when the super class is made extensible (private members made protected)
     * Populates the local copies of the needed private super fields. 
     * 
     * @throws SecurityException
     * @throws NoSuchFieldException
     * @throws NoSuchMethodException 
     * @throws IllegalArgumentException
     * @throws IllegalAccessException
     */
    protected void populateReflectedFields()
    throws NoSuchFieldException, NoSuchMethodException 
    {
        mdnsVerbose = Options.check("mdns_verbose") || Options.check("verbose");
        
        Class clazz = getClass().getSuperclass();
        
        try
        {
            dataField = findField(clazz, "data");
            
            Field.setAccessible(new AccessibleObject[]{dataField}, true);
            
            dataCopy = (LinkedHashMap) dataField.get(this);
        } catch (NoSuchFieldException e)
        {
            System.err.println(e.getMessage());
            throw e;
        } catch (Exception e)
        {
            if (mdnsVerbose)
            {
                System.err.println(e.getMessage());
                e.printStackTrace(System.err);
            }
        }
        
        try
        {
            findElement = findMethod(clazz, "findElement", new Class[]{Name.class, Integer.TYPE, Integer.TYPE});
            removeElement = findMethod(clazz, "removeElement", new Class[]{Name.class, Integer.TYPE});
            
            Method.setAccessible(new AccessibleObject[]{findElement, removeElement}, true);
        } catch (NoSuchMethodException e)
        {
            System.err.println(e.getMessage());
            throw e;
        } catch (Exception e)
        {
            if (mdnsVerbose)
            {
                System.err.println(e.getMessage());
                e.printStackTrace(System.err);
            }
        }
    }
    
    
    /**
     * Sets the CacheMonitor used to monitor cache data. Can be used to
     * implement Cache record refreshing.
     * 
     * @param monitor the CacheMonitor
     */
    public synchronized void setCacheMonitor(CacheMonitor monitor)
    {
        if (monitor != null)
        {
            ScheduledExecutorService executor = this.executor;
            if (executor != null)
            {
                executor.shutdown();
            }
            this.executor = executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory()
            {
                public Thread newThread(Runnable r)
                {
                    Thread t = new Thread(r, "mDNSCache Monitor Thread");
                    t.setDaemon(true);
                    t.setPriority(Thread.MAX_PRIORITY - 1);
                    return t;
                }
            });
            this.cacheMonitor = monitor;
            executor.scheduleAtFixedRate(new MonitorTask(this), 1, 1, TimeUnit.SECONDS);
        } else
        {
            ScheduledExecutorService executor = this.executor;
            if (executor != null)
            {
                executor.shutdown();
                this.executor = executor = null;
            }
        }
    }
    
    
    /**
     * Gets the CacheMonitor used to monitor cache data.
     * 
     * @return monitor the CacheMonitor
     */
    public CacheMonitor getCacheMonitor()
    {
        return cacheMonitor;
    }
    
    
    @Override
    public synchronized void addRecord(Record r, int cred, Object o)
    {
        super.addRecord(r, cred, o);
    }


    @Override
    public synchronized void addRRset(RRset rrset, int cred)
    {
        super.addRRset(rrset, cred);
    }


    /**
     * Updates an RRset in the Cache. Typically used to update expirey.
     * 
     * @param rrset The RRset to be updated
     * @throws InvocationTargetException 
     * @throws IllegalAccessException 
     * @throws IllegalArgumentException 
     * @see RRset
     */
    synchronized void updateRRset(Record record, int cred) 
    throws IllegalArgumentException, IllegalAccessException, InvocationTargetException
    {
        long ttl = record.getTTL();
        Name name = record.getName();
        int type = record.getType();
        ElementHelper element = findElementCopy(name, type, 0);
        if (element != null)
        {
            if (element.compareCredibility(cred) <= 0)
            {
                if (element.getElement() instanceof RRset)
                {
                    ((RRset) element.getElement()).addRR(record);
                    if (element.getTTL() == ttl)
                    {
                        element.resetExpire();
                    } else
                    {
                        addRecord(record, cred, this);
                    }
                } else
                {
                    addRecord(record, cred, this);
                }
            }
        } else
        {
            addRecord(record, cred, this);
        }
    }
    
    
    /**
     * Removes an RRset from the Cache.
     * 
     * @param rrset The RRset to be removed
     * @see RRset
     */
    synchronized void removeRRset(RRset rrset)
    {
        removeElementCopy(rrset.getName(), rrset.getType());
    }
    
    
    private ElementHelper findElementCopy(Name name, int type, int minCred)
    throws IllegalArgumentException, IllegalAccessException, InvocationTargetException
    {
        Object o = findElement.invoke(this, new Object[]{name, new Integer(type), new Integer(minCred)});
        
        try
        {
            return o == null ? (ElementHelper) null : new ElementHelper(this, o);
        } catch (Exception e)
        {
            System.err.println(e.getMessage());
            if (mdnsVerbose)
            {
                e.printStackTrace(System.err);
            }
            return null;
        }
    }
    
    
    public void removeElementCopy(Name name, int type)
    {
        try
        {
            removeElement.invoke(this, new Object[]{name, new Integer(type)});
        } catch (Exception e)
        {
            System.err.println(e.getMessage());
            if (mdnsVerbose)
            {
                e.printStackTrace(System.err);
            }
        }
    }
    
    
    private static int limitExpire(long ttl, long maxttl)
    {
        if (maxttl >= 0 && maxttl < ttl)
        {
            ttl = maxttl;
        }
        
        long expire = (System.currentTimeMillis() / 1000) + ttl;
        
        if (expire < 0 || expire > Integer.MAX_VALUE)
        {
            return Integer.MAX_VALUE;
        }
        
        return (int) expire;
    }
    
    
    private static Field findField(Class clazz, String name) 
    throws NoSuchFieldException
    {
        Class clz = clazz;
        Field field = null;
        
        while (clz != null && field == null)
        {
            try
            {
                field = clz.getDeclaredField(name);
            } catch (SecurityException e)
            {
            } catch (NoSuchFieldException e)
            {
            }
            
            if (field != null)
            {
                return field;
            }
            
            clz = clz.getSuperclass();
        }
        
        throw new NoSuchFieldException("Field \"" + name + "\" does not exist in class \"" + clazz.getName() + "\".");
    }
    
    
    private static Method findMethod(Class clazz, String name, Class[] parameters) 
    throws NoSuchMethodException
    {
        Class clz = clazz;
        Method method = null;
        
        while (clz != null && method == null)
        {
            try
            {
                method = clz.getDeclaredMethod(name, parameters);
            } catch (SecurityException e)
            {
            } catch (NoSuchMethodException e)
            {
            }
            
            if (method != null)
            {
                return method;
            }
            
            clz = clz.getSuperclass();
        }
        
        throw new NoSuchMethodException("Method \"" + name + "\" does not exist in class \"" + clazz.getName() + "\".");
    }
    
    
    /**
     * Acquires additional information from the cache so that the returned results have all the 
     * information required in 1 query.
     * 
     * @param name The root name to begin the query sequence
     * @return The list of additional records
     */
    public Record[] queryCacheForAdditionalRecords(Record record, int credibility)
    {
        if (record == null)
        {
            return MulticastDNSUtils.EMPTY_RECORDS;
        }
        
        LinkedList results = new LinkedList();
        
        Name target = MulticastDNSUtils.getTargetFromRecord(record);
        if (target != null)
        {
            SetResponse response = lookupRecords(target, Type.ANY, credibility);
            if (response.isSuccessful())
            {
                Record[] answers = MulticastDNSUtils.extractRecords(response.answers());
                for (Record answer : answers)
                {
                    results.add(answer);
                    Record[] tempRecords = queryCacheForAdditionalRecords(answer, credibility);
                    for (Record tempRecord : tempRecords)
                    {
                        results.add(tempRecord);
                    }
                }
            }
        }
        
        return (Record[]) results.toArray(new Record[results.size()]);
    }
    
    
    public Message queryCache(Message query)
    {
        return queryCache(query, Credibility.ANY);
    }
    
    
    public Message queryCache(Message query, int credibility)
    {
        if (query.getHeader().getOpcode() == Opcode.UPDATE)
        {
            Message message = new Message(query.getHeader().getID());
            Header header = message.getHeader();
            header.setRcode(Rcode.NXDOMAIN);
            
            Stack stack = new Stack();
            
            Record[] updates = MulticastDNSUtils.extractRecords(query, Section.UPDATE);
            for (Record update : updates)
            {
                stack.push(update.getName());
            }
            
            while (!stack.isEmpty())
            {
                Name name = (Name) stack.pop();
                SetResponse response = lookupRecords(name, Type.ANY, credibility);
                if (response.isSuccessful())
                {
                    header.setRcode(Rcode.NOERROR);
                    header.setOpcode(Opcode.QUERY);
                    header.setFlag(Flags.QR);
                    
                    Record[] answers = MulticastDNSUtils.extractRecords(response.answers());
                    for (Record answer : answers)
                    {
                        if (!message.findRecord(answer))
                        {
                            message.addRecord(answer, Section.ANSWER);
                        }
                        
                        Name target = MulticastDNSUtils.getTargetFromRecord(answer);
                        if (target != null)
                        {
                            stack.push(target);
                        }
                    }
                }
            }

            return message;
        } else
        {
            Message message = new Message(query.getHeader().getID());
            Header header = message.getHeader();
            header.setRcode(Rcode.NXDOMAIN);
            
            Record[] questions = MulticastDNSUtils.extractRecords(query, Section.QUESTION);
            if (questions != null && questions.length > 0)
            {
                for (Record question : questions)
                {
                    message.addRecord(question, Section.QUESTION);
                    
                    MulticastDNSUtils.setDClassForRecord(question, question.getDClass() & 0x7FFF);
                    SetResponse response = lookupRecords(question.getName(), Type.ANY, credibility);
                    if (response.isSuccessful())
                    {
                        header.setRcode(Rcode.NOERROR);
                        header.setOpcode(Opcode.QUERY);
                        header.setFlag(Flags.QR);
                        
                        Record[] answers = MulticastDNSUtils.extractRecords(response.answers());
                        if (answers != null && answers.length > 0)
                        {
                            for (Record answer : answers)
                            {
                                if (!message.findRecord(answer))
                                {
                                    message.addRecord(answer, Section.ANSWER);
                                }
                                
                                Record[] additionalAnswers = queryCacheForAdditionalRecords(answer, credibility);
                                for (Record additionalAnswer : additionalAnswers)
                                {
                                    if (!message.findRecord(additionalAnswer))
                                    {
                                        message.addRecord(additionalAnswer, Section.ADDITIONAL);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            return message;
        }
    }
    
    
    public synchronized void close()
    throws IOException
    {
        if (this != DEFAULT_MDNS_CACHE)
        {
            ScheduledExecutorService executor = this.executor;
            if (executor != null)
            {
                executor.shutdown();
                
                while (!executor.isTerminated())
                {
                    try
                    {
                        Thread.sleep(0);
                    } catch (InterruptedException e)
                    {
                        // ignore
                    }
                }
            }
            
            // Run final cache check, sending mDNS messages if needed
            if (cacheMonitor != null)
            {
                MonitorTask task = new MonitorTask(this, true);
                task.run();
            }
        }
    }
    
    
    protected void finalize()
    throws Throwable
    {
        try
        {
            close();
            super.finalize();
        } catch (Throwable t)
        {
            System.err.println(t.getMessage());
            t.printStackTrace(System.err);
        }
    }
}
