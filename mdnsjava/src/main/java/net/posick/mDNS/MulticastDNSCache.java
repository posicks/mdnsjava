package net.posick.mDNS;

import java.io.Closeable;
import java.io.File;
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
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

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

import net.posick.mDNS.utils.Executors;
import net.posick.mDNS.utils.Misc;

/**
 * A cache of mDNS records and responses. The cache obeys TTLs, so items are
 * purged after their validity period is complete. Negative answers are cached,
 * to avoid repeated failed DNS queries. The credibility of each RRset is
 * maintained, so that more credible records replace less credible records, and
 * lookups can specify the minimum credibility of data they are requesting.
 * 
 * !!! This cache implementation extends the Cache class. Reflection had to be used to access
 * !!! private fields and methods in the Cache class that prevent its direct extensibility.
 * 
 * @see Cache
 * 
 * @author Steve Posick
 */
@SuppressWarnings({"unchecked",
"rawtypes"})
public class MulticastDNSCache extends Cache implements Closeable
{
    protected static final Logger logger = Misc.getLogger(MulticastDNSCache.class.getName(), Options.check("mdns_verbose") || Options.check("dns_verbose") || Options.check("verbose"));
    
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
         * Called at the beginning of a Monitor task. Clears the state and prepares the CacheMonitor
         * for the receipt of check and expired calls.
         */
        public void begin();
        
        
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
         * Called at the end of a Monitor task. Clears the state, releases resources, and performs
         * refresh of items identified as requiring a refresh.
         */
        public void end();
        
        
        /**
         * Called for RRsets that have expired.
         * 
         * @param rrs The expired RRset
         * @param credibility The credibility of the RRset
         */
        public void expired(RRset rrs, int credibility);
        
        
        /**
         * Returns true if the CacheMonitor is operational (being called regularly).
         * 
         * @return true if the CacheMonitor is operational (being called regularly)
         */
        public boolean isOperational();
    }
    
    
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
        
        
        protected ElementHelper(final Cache cache, final Object element)
        throws SecurityException, NoSuchMethodException, NoSuchFieldException,
        IllegalArgumentException
        {
            this.cache = cache;
            this.element = element;
            clazz = element.getClass();
            
            expireField = findField(clazz, "expire");
            credibilityField = findField(clazz, "credibility");
            
            expired = findMethod(clazz, "expired", new Class[0]);
            compareCredibility = findMethod(clazz, "compareCredibility", new Class[] {Integer.TYPE});
            getType = findMethod(clazz, "getType", new Class[0]);
            getTTL = findMethod(clazz, "getTTL", new Class[0]);
            
            AccessibleObject.setAccessible(new AccessibleObject[] {expireField,
                                                                   credibilityField}, true);
            AccessibleObject.setAccessible(new AccessibleObject[] {expired,
                                                                   compareCredibility,
                                                                   getType,
                                                                   getTTL,
                                                                   expireField,
                                                                   credibilityField}, true);
        }
        
        
        public int getCredibility()
        throws IllegalArgumentException, IllegalAccessException
        {
            return credibilityField.getInt(element);
        }
        
        
        public int getExpiresIn()
        throws IllegalArgumentException, IllegalAccessException
        {
            return getExpire() - (int) (System.currentTimeMillis() / 1000);
        }
        
        
        public long getTTL()
        throws IllegalArgumentException, IllegalAccessException,
        InvocationTargetException
        {
            if (getTTL != null)
            {
                Long value = (Long) getTTL.invoke(element, new Object[0]);
                return value == null ? 0L : value.longValue();
            }
            return 0;
        }
        
        
        public void resetExpire()
        throws IllegalArgumentException, IllegalAccessException,
        InvocationTargetException
        {
            expireField.setInt(element, limitExpire(getTTL(), cache.getMaxCache()));
        }
        
        
        protected int compareCredibility(final int cred)
        throws IllegalArgumentException, IllegalAccessException,
        InvocationTargetException
        {
            return (Integer) compareCredibility.invoke(element, new Object[] {cred});
        }
        
        
        protected boolean expired()
        throws IllegalArgumentException, IllegalAccessException,
        InvocationTargetException
        {
            return (Boolean) expired.invoke(element, new Object[0]);
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
        
        
        protected int getType()
        throws IllegalArgumentException, IllegalAccessException,
        InvocationTargetException
        {
            return (Integer) getType.invoke(element, new Object[0]);
        }
    }
    
    
    private class MonitorTask implements Runnable
    {
        private boolean shutdown = false;
        
        
        MonitorTask()
        {
            this(false);
        }
        
        
        MonitorTask(final boolean shutdown)
        {
            this.shutdown = shutdown;
        }
        
        
        public void run()
        {
            try
            {
                CacheMonitor cacheMonitor = getCacheMonitor();
                if ((cacheMonitor == null) || shutdown)
                {
                    return;
                }
                
                try
                {
                    cacheMonitor.begin();
                } catch (Exception e)
                {
                    logger.log(Level.WARNING, e.getMessage(), e);
                }
                
                Object[] sets;
                synchronized (MulticastDNSCache.this)
                {
                    Collection values = dataCopy.values();
                    sets = values.toArray(new Object[values.size()]);
                }
                
                for (int index = 0; index < sets.length; index++ )
                {
                    try
                    {
                        Object types = sets[index];
                        if (types instanceof List)
                        {
                            List list = (List) types;
                            Object[] elements;
                            synchronized (MulticastDNSCache.this)
                            {
                                elements = list.toArray(new Object[list.size()]);
                            }
                            for (int eIndex = 0; eIndex < elements.length; eIndex++ )
                            {
                                processElement(new ElementHelper(MulticastDNSCache.this, elements[eIndex]));
                            }
                        } else
                        {
                            processElement(new ElementHelper(MulticastDNSCache.this, types));
                        }
                    } catch (Exception e)
                    {
                        logger.log(Level.WARNING, e.getMessage(), e);
                    }
                }
                
                try
                {
                    cacheMonitor.end();
                } catch (Exception e)
                {
                    logger.log(Level.WARNING, e.getMessage(), e);
                }
            } catch (Throwable e)
            {
                logger.log(Level.WARNING, e.getMessage(), e);
            }
        }
        
        
        private void processElement(final ElementHelper element)
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
                    
                    CacheMonitor cacheMonitor = getCacheMonitor();
                    int expiresIn = element.getExpiresIn();
                    if ((expiresIn <= 0) || (rrs.getTTL() <= 0))
                    {
                        cacheMonitor.expired(rrs, element.getCredibility());
                    } else
                    {
                        cacheMonitor.check(rrs, element.getCredibility(), expiresIn);
                    }
                } else
                {
                    logger.logp(Level.INFO, getClass().getName(), "processElement", "Element is an unexpected type \"" + (element.getElement() != null ? element.getElement().getClass().getName() : "null") + "\"");
                }
            } catch (Exception e)
            {
                logger.log(Level.WARNING, e.getMessage(), e);
            }
        }
    }
    
    protected final static MulticastDNSCache DEFAULT_MDNS_CACHE;
    
    public final static String MDNS_CACHE_FILENAME = MulticastDNSMulticastOnlyQuerier.class.getSimpleName() + ".cache";

    static
    {
        MulticastDNSCache temp = null;
        try
        {
            try
            {
                String filename = MDNS_CACHE_FILENAME;
                File file = new File(filename);
                if (file.exists() && file.canRead())
                {
                    temp = new MulticastDNSCache(filename);
                } else
                {
                    temp = new MulticastDNSCache();
                }
            } catch (IOException e)
            {
                temp = new MulticastDNSCache();
                
                logger.log(Level.WARNING, "Error loading default cache values - " + e.getMessage(), e);
            }
        } catch (NoSuchFieldException e)
        {
            logger.log(Level.WARNING, "Unrecoverable Error: The base " + Cache.class + " class does not implement required fields - " + e.getMessage(), e);
        } catch (NoSuchMethodException e)
        {
            logger.log(Level.WARNING, "Unrecoverable Error: The base " + Cache.class + " class does not implement required methods - " + e.getMessage(), e);
        }
        
        DEFAULT_MDNS_CACHE = temp;
    }
    
    private CacheMonitor cacheMonitor = null;
    
    private LinkedHashMap dataCopy;
    
    private Field dataField = null;
    
    private Method findElement = null;
    
    private Method removeElement = null;
    
    private Executors executors = Executors.newInstance();
    
    
    /**
     * Creates an empty Cache for class IN.
     * 
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
     * Creates an empty Cache
     * 
     * @param dclass The DNS class of this cache
     * @throws NoSuchFieldException
     * @see DClass
     */
    public MulticastDNSCache(final int dclass)
    throws NoSuchFieldException, NoSuchMethodException
    {
        super(dclass);
        populateReflectedFields();
    }
    
    
    /**
     * Creates a Cache which initially contains all records in the specified
     * file.
     * 
     * @throws IOException
     * @throws IllegalAccessException
     * @throws NoSuchFieldException
     * @throws IllegalArgumentException
     * @throws SecurityException
     */
    public MulticastDNSCache(final String file)
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
    MulticastDNSCache(final Cache cache)
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
    
    
    @Override
    public synchronized void addRecord(final Record r, final int cred, final Object o)
    {
        super.addRecord(r, cred, o);
    }
    
    
    @Override
    public synchronized void addRRset(final RRset rrset, final int cred)
    {
        super.addRRset(rrset, cred);
    }
    
    
    public synchronized void close()
    throws IOException
    {
        if (this != DEFAULT_MDNS_CACHE)
        {
            // Run final cache check, sending mDNS messages if needed
            if (cacheMonitor != null)
            {
                MonitorTask task = new MonitorTask(true);
                task.run();
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
    
    
    public Message queryCache(final Message query)
    {
        return queryCache(query, Credibility.ANY);
    }
    
    
    public Message queryCache(final Message query, final int credibility)
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
        }
        
        Message message = new Message(query.getHeader().getID());
        Header header = message.getHeader();
        header.setRcode(Rcode.NXDOMAIN);
        
        Record[] questions = MulticastDNSUtils.extractRecords(query, Section.QUESTION);
        if ((questions != null) && (questions.length > 0))
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
                    if ((answers != null) && (answers.length > 0))
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
    
    
    /**
     * Acquires additional information from the cache so that the returned results have all the
     * information required in 1 query.
     * 
     * @param name The root name to begin the query sequence
     * @return The list of additional records
     */
    public Record[] queryCacheForAdditionalRecords(final Record record, final int credibility)
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
    
    
    public void removeElementCopy(final Name name, final int type)
    {
        try
        {
            removeElement.invoke(this, new Object[] {name,
                                                     new Integer(type)});
        } catch (Exception e)
        {
            logger.log(Level.WARNING, e.getMessage(), e);
        }
    }
    
    
    /**
     * Sets the CacheMonitor used to monitor cache data. Can be used to
     * implement Cache record refreshing.
     * 
     * @param monitor the CacheMonitor
     */
    public synchronized void setCacheMonitor(final CacheMonitor monitor)
    {
        if (monitor != null)
        {
            cacheMonitor = monitor;
        }
    }
    
    
    /**
     * Removes an RRset from the Cache.
     * 
     * @param rrset The RRset to be removed
     * @see RRset
     */
    synchronized void removeRRset(final RRset rrset)
    {
        removeElementCopy(rrset.getName(), rrset.getType());
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
    synchronized void updateRRset(final Record record, final int cred)
    throws IllegalArgumentException, IllegalAccessException,
    InvocationTargetException
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
    
    
    @Override
    protected void finalize()
    throws Throwable
    {
        try
        {
            close();
            super.finalize();
        } catch (Throwable t)
        {
            logger.log(Level.WARNING,  t.getMessage(), t);
        }
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
        executors.scheduleAtFixedRate(new MonitorTask(), 1, 1, TimeUnit.SECONDS);
        
        Class clazz = getClass().getSuperclass();
        
        try
        {
            dataField = findField(clazz, "data");
            
            AccessibleObject.setAccessible(new AccessibleObject[] {dataField}, true);
            
            dataCopy = (LinkedHashMap) dataField.get(this);
        } catch (NoSuchFieldException e)
        {
            logger.log(Level.WARNING, e.getMessage(), e);
            throw e;
        } catch (Exception e)
        {
            logger.log(Level.WARNING, e.getMessage(), e);
        }
        
        try
        {
            findElement = findMethod(clazz, "findElement", new Class[] {Name.class,
                                                                        Integer.TYPE,
                                                                        Integer.TYPE});
            removeElement = findMethod(clazz, "removeElement", new Class[] {Name.class,
                                                                            Integer.TYPE});
            
            AccessibleObject.setAccessible(new AccessibleObject[] {findElement,
                                                                   removeElement}, true);
        } catch (NoSuchMethodException e)
        {
            logger.log(Level.WARNING, e.getMessage(), e);
            throw e;
        } catch (Exception e)
        {
            logger.log(Level.WARNING, e.getMessage(), e);
        }
    }
    
    
    private ElementHelper findElementCopy(final Name name, final int type, final int minCred)
    throws IllegalArgumentException, IllegalAccessException,
    InvocationTargetException
    {
        Object o = findElement.invoke(this, new Object[] {name,
                                                          new Integer(type),
                                                          new Integer(minCred)});
        
        try
        {
            return o == null ? (ElementHelper) null : new ElementHelper(this, o);
        } catch (Exception e)
        {
            logger.log(Level.WARNING, e.getMessage(), e);
            return null;
        }
    }
    
    
    private static Field findField(final Class clazz, final String name)
    throws NoSuchFieldException
    {
        Class clz = clazz;
        Field field = null;
        
        while ((clz != null) && (field == null))
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
    
    
    private static Method findMethod(final Class clazz, final String name, final Class[] parameters)
    throws NoSuchMethodException
    {
        Class clz = clazz;
        Method method = null;
        
        while ((clz != null) && (method == null))
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
    
    
    private static int limitExpire(long ttl, final long maxttl)
    {
        if ((maxttl >= 0) && (maxttl < ttl))
        {
            ttl = maxttl;
        }
        
        long expire = (System.currentTimeMillis() / 1000) + ttl;
        
        if ((expire < 0) || (expire > Integer.MAX_VALUE))
        {
            return Integer.MAX_VALUE;
        }
        
        return (int) expire;
    }
}
