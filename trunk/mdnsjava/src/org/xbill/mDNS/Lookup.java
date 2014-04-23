package org.xbill.mDNS;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.MulticastDNSUtils;
import org.xbill.DNS.Name;
import org.xbill.DNS.Options;
import org.xbill.DNS.PTRRecord;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.ResolverListener;
import org.xbill.DNS.Section;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

@SuppressWarnings({"unchecked", "rawtypes"})
public class Lookup extends MulticastDNSLookupBase
{
    public static class Domain
    {
        private Name name;
        
        private boolean isDefault;
        
        private boolean isLegacy;
        
        
        protected Domain(Name name)
        {
            this(false, false, name);
        }
        
        
        protected Domain(boolean isDefault, boolean isLegacy, Name name)
        {
            this.name = name;
            this.isDefault = isDefault; 
            this.isLegacy = isLegacy;
        }


        public Name getName()
        {
            return name;
        }


        public boolean isDefault()
        {
            return isDefault;
        }


        public boolean isLegacy()
        {
            return isLegacy;
        }
        
        
        @Override
        public boolean equals(Object obj)
        {
            if (obj == this)
            {
                return true;
            } else if (this.name == obj)
            {
                return true;
            } else if (obj instanceof Domain)
            {
                return name.equals(((Domain) obj).name);
            }
            
            return false;
        }


        @Override
        public String toString()
        {
            return name + (isDefault ? "  [default]" : "") + (isLegacy ? "  [legacy]" : ""); 
        }
    }
    
    
    public interface RecordListener
    {
        public void receiveRecord(Object id, Record r);
        
        public void handleException(Object id, Exception e);
    }
    
    
    protected Lookup()
    throws IOException
    {
        super();
    }
    
    
    public Lookup(Name... names)
    throws IOException
    {
        super(names);
    }
    
    
    public Lookup(Name[] names, int type)
    throws IOException
    {
        super(names, type);
    }
    
    
    public Lookup(Name[] names, int type, int dclass)
    throws IOException
    {
        super(names, type, dclass);
    }
    
    
    protected Lookup(Message message)
    throws IOException
    {
        super(message);
    }
    
    
    public Lookup(String... names)
    throws IOException
    {
        super(names);
    }
    
    
    public Lookup(String[] names, int type)
    throws IOException
    {
        super(names, type);
    }
    
    
    public Lookup(String[] names, int type, int dclass)
    throws IOException
    {
        super(names, type, dclass);
    }
    
    
    public void lookupRecordsAsync(final RecordListener listener)
    throws IOException
    {
        for (Message query : queries)
        {
            getQuerier().sendAsync(query, new ResolverListener()
            {
                public void receiveMessage(Object id, Message m)
                {
                    Record[] records = MulticastDNSUtils.extractRecords(m, Section.ANSWER, Section.ADDITIONAL, Section.AUTHORITY);
                    for (Record r : records)
                    {
                        listener.receiveRecord(id, r);
                    }
                }
                
                
                public void handleException(Object id, Exception e)
                {
                    listener.handleException(id, e);
                }
            });
        }
    }
    
    
    public Record[] lookupRecords()
    throws IOException
    {
        final List results = new ArrayList();
        final List records = new ArrayList();
        
        for (Message query : queries)
        {
            results.add(getQuerier().send(query));
        }
        
        Message[] messages;
        synchronized (results)
        {
            messages = (Message[]) results.toArray(new Message[results.size()]);
        }
        
        for (Message m : messages)
        {
            switch (m.getRcode())
            {
                case Rcode.NOERROR :
                    records.addAll(Arrays.asList(MulticastDNSUtils.extractRecords(m, Section.ANSWER, Section.AUTHORITY, Section.ADDITIONAL)));
                    break;
                case Rcode.NXDOMAIN :
                    break;
            }
        }
        
        return (Record[]) records.toArray(new Record[records.size()]);
    }
    
    
    public ServiceInstance[] lookupServices()
    throws IOException
    {
        List results = new ArrayList();
        IOException ioe = null;
        
        for (Message query : queries)
        {
            try
            {
                results.addAll(Arrays.asList(extractServiceInstances(getQuerier().send(query))));
            } catch (IOException e)
            {
                ioe = e;
            }
        }
        
        if (results.size() > 0)
        {
            return (ServiceInstance[]) results.toArray(new ServiceInstance[results.size()]);
        } else if (ioe != null)
        {
            throw ioe;
        } else
        {
            return null;
        }
    }

    
    public Domain[] lookupDomains()
    throws IOException
    {
        final List domains = Collections.synchronizedList(new LinkedList());
        final List exceptions = Collections.synchronizedList(new LinkedList());
        List resolvers = new LinkedList();
        
        if (names != null && names.length > 0)
        {
            for (final Name name : names)
            {
                Lookup lookup = new Lookup(new Name[]{name}, Type.PTR, DClass.ANY);
                resolvers.add(lookup);
                
                Name[] defaultBrowseDomains = lookup.getQuerier().getMulticastDomains();
                if (defaultBrowseDomains != null && defaultBrowseDomains.length > 0)
                {
                    for (int index = 0; index < defaultBrowseDomains.length; index++)
                    {
                        Domain d = new Domain(false, false, defaultBrowseDomains[index]);
                        if (!domains.contains(d))
                        {
                            domains.add(d);
                        }
                    }
                }
                
                lookup.lookupRecordsAsync(new RecordListener()
                {
                    public void receiveRecord(Object id, Record record)
                    {
                        if (record.getTTL() > 0)
                        {
                            if (record.getType() == Type.PTR)
                            {
                                String value = ((PTRRecord) record).getTarget().toString();
                                if (!value.endsWith("."))
                                {
                                    value += ".";
                                }
                                
                                // Check if domain is already in the list, add if not, otherwise manipulate booleans.
                                try
                                {
                                    Domain domain = new Domain(false, false, new Name(value));
                                    int index = domains.indexOf(domain);
                                    if (index >= 0)
                                    {
                                        domain = (Domain) domains.get(index);
                                    } else
                                    {
                                        domains.add(domain);
                                    }
                                    
                                    switch (name.toString().charAt(0))
                                    {
                                        case 'd' :
                                            domain.isDefault = true;
                                            break;
                                        case 'l' :
                                            domain.isLegacy = true;
                                            break;
                                    }
                                } catch (TextParseException e)
                                {
                                    e.printStackTrace(System.err);
                                }
                            }
                        }
                    }
                    
                    
                    public void handleException(Object id, Exception e)
                    {
                        exceptions.add(e);
                    }
                });
            }
            
            synchronized (domains)
            {
                int wait = Options.intValue("mdns_resolve_wait");
                long waitTill = System.currentTimeMillis() + (wait > 0 ? wait : Querier.DEFAULT_RESPONSE_WAIT_TIME);
                while (domains.size() == 0 && System.currentTimeMillis() < waitTill)
                {
                    try
                    {
                        domains.wait(waitTill - System.currentTimeMillis());
                    } catch (InterruptedException e)
                    {
                        // ignore
                    }
                }
            }
            
            for (Object o : resolvers)
            {
                Closeable c = (Closeable) o;
                c.close();
            }
        }
            
        return (Domain[]) domains.toArray(new Domain[domains.size()]);
    }
    
    
    public void close()
    throws IOException
    {
    }
}
