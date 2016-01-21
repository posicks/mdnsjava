package net.posick.mDNS;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.MulticastDNSUtils;
import org.xbill.DNS.Name;
import org.xbill.DNS.PTRRecord;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.ResolverListener;
import org.xbill.DNS.Section;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import net.posick.mDNS.utils.Wait;

@SuppressWarnings({"unchecked", "rawtypes"})
public class Lookup extends MulticastDNSLookupBase
{
    public static class Domain
    {
        private final Name name;
        
        private boolean isDefault;
        
        private boolean isLegacy;
        
        
        protected Domain(final Name name)
        {
            this.name = name;
            
            byte[] label = name.getLabel(0);
            if (label != null)
            {
                switch ((char) label[0])
                {
                    case 'd':
                        isDefault = true;
                        break;
                    case 'l':
                        isLegacy = true;
                        break;
                }
            }
        }
        
        
        @Override
        public boolean equals(final Object obj)
        {
            if (obj == this)
            {
                return true;
            } else if (name == obj)
            {
                return true;
            } else if (obj instanceof Domain)
            {
                return name.equals(((Domain) obj).name);
            }
            
            return false;
        }
        
        
        public Name getName()
        {
            return name;
        }
        
        
        @Override
        public int hashCode()
        {
            return name.hashCode();
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
        public String toString()
        {
            return name + (isDefault ? "  [default]" : "") + (isLegacy ? "  [legacy]" : "");
        }
    }
    
    
    public static interface RecordListener
    {
        public void handleException(Object id, Exception e);
        
        
        public void receiveRecord(Object id, Record r);
    }

    
    public Lookup(final Name... names)
    throws IOException
    {
        super(names);
    }
    
    
    public Lookup(final Name[] names, final int type)
    throws IOException
    {
        super(names, type);
    }
    
    
    public Lookup(final Name name, final int type)
    throws IOException
    {
        super(new Name[]{name}, type);
    }
    
    
    public Lookup(final Name[] names, final int type, final int dclass)
    throws IOException
    {
        super(names, type, dclass);
    }
    
    
    public Lookup(final Name name, final int type, final int dclass)
    throws IOException
    {
        super(new Name[]{name}, type, dclass);
    }
    
    
    public Lookup(final String... names)
    throws IOException
    {
        super(names);
    }
    
    
    public Lookup(final String[] names, final int type)
    throws IOException
    {
        super(names, type);
    }
    
    
    public Lookup(final String[] names, final int type, final int dclass)
    throws IOException
    {
        super(names, type, dclass);
    }
    
    
    protected Lookup()
    throws IOException
    {
        super();
    }
    
    
    protected Lookup(final Message message)
    throws IOException
    {
        super(message);
    }
    
    
    public void close()
    throws IOException
    {
    }
    
    
    public Domain[] lookupDomains()
    throws IOException
    {
        final Set domains = Collections.synchronizedSet(new HashSet());
        final List exceptions = Collections.synchronizedList(new LinkedList());
        
        if ((queries != null) && (queries.length > 0))
        {
            lookupRecordsAsync(new RecordListener()
            {
                public void handleException(final Object id, final Exception e)
                {
                    exceptions.add(e);
                }
                
                
                public void receiveRecord(final Object id, final Record record)
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
                                domains.add(new Domain(new Name(value)));
                            } catch (TextParseException e)
                            {
                                e.printStackTrace(System.err);
                            }
                        }
                    }
                }
            });
            
            Wait.forResponse(domains);
        }
        
        for (Name name : searchPath)
        {
            domains.add(new Domain(name));
        }
        
        return (Domain[]) domains.toArray(new Domain[domains.size()]);
    }
    
    
    public Record[] lookupRecords()
    throws IOException
    {
        final List messages = Collections.synchronizedList(new LinkedList());
        final List exceptions = Collections.synchronizedList(new LinkedList());
        
        lookupRecordsAsync(new ResolverListener()
        {
            public void handleException(final Object id, final Exception e)
            {
                exceptions.add(e);
            }
            
            
            public void receiveMessage(final Object id, final Message m)
            {
                messages.add(m);
            }
        });
        
        Wait.forResponse(messages);

        List records = new ArrayList();
        
        for (Object o : messages)
        {
            Message m = (Message) o;
            switch (m.getRcode())
            {
                case Rcode.NOERROR:
                    records.addAll(Arrays.asList(MulticastDNSUtils.extractRecords(m, Section.ANSWER, Section.AUTHORITY, Section.ADDITIONAL)));
                    break;
                case Rcode.NXDOMAIN:
                    break;
            }
        }
        
        return (Record[]) records.toArray(new Record[records.size()]);
    }
    
    
    public void lookupRecordsAsync(final RecordListener listener)
    throws IOException
    {
        lookupRecordsAsync(new ResolverListener()
        {
            public void handleException(final Object id, final Exception e)
            {
                listener.handleException(id, e);
            }
            
            
            public void receiveMessage(final Object id, final Message m)
            {
                Record[] records = MulticastDNSUtils.extractRecords(m, Section.ANSWER, Section.ADDITIONAL, Section.AUTHORITY);
                for (Record r : records)
                {
                    listener.receiveRecord(id, r);
                }
            }
        });
    }
    
    
    public void lookupRecordsAsync(final ResolverListener listener)
    throws IOException
    {
        for (Message query : queries)
        {
            getQuerier().sendAsync(query, listener);
        }
    }
    
    
    public ServiceInstance[] lookupServices()
    throws IOException
    {
        final List results = new ArrayList();
        results.addAll(Arrays.asList(extractServiceInstances(lookupRecords())));
        return (ServiceInstance[]) results.toArray(new ServiceInstance[results.size()]);
    }
    
    
    public static Record[] lookupRecords(Name name)
    throws IOException
    {
        return lookupRecords(new Name[] {name}, Type.ANY, DClass.ANY);
    }
    
    
    public static Record[] lookupRecords(Name[] names)
    throws IOException
    {
        return lookupRecords(names, Type.ANY, DClass.ANY);
    }
    
    
    public static Record[] lookupRecords(Name name, int type)
    throws IOException
    {
        return lookupRecords(new Name[] {name}, type, DClass.ANY);
    }
    
    
    public static Record[] lookupRecords(Name[] names, int type)
    throws IOException
    {
        return lookupRecords(names, type, DClass.ANY);
    }
    
    
    public static Record[] lookupRecords(Name name, int type, int dclass)
    throws IOException
    {
        return lookupRecords(new Name[] {name}, type, dclass);
    }
    
    
    public static Record[] lookupRecords(Name[] names, int type, int dclass)
    throws IOException
    {
        Lookup lookup = new Lookup(names, type, dclass);
        try
        {
            return lookup.lookupRecords();
        } finally
        {
            lookup.close();
        }
    }
    
    
    public static ServiceInstance[] lookupServices(Name name)
    throws IOException
    {
        return lookupServices(new Name[] {name}, Type.ANY, DClass.ANY);
    }
    
    
    public static ServiceInstance[] lookupServices(Name[] names)
    throws IOException
    {
        return lookupServices(names, Type.ANY, DClass.ANY);
    }
    
    
    public static ServiceInstance[] lookupServices(Name name, int type)
    throws IOException
    {
        return lookupServices(new Name[] {name}, type, DClass.ANY);
    }
    
    
    public static ServiceInstance[] lookupServices(Name[] names, int type)
    throws IOException
    {
        return lookupServices(names, type, DClass.ANY);
    }
    
    
    public static ServiceInstance[] lookupServices(Name name, int type, int dclass)
    throws IOException
    {
        return lookupServices(new Name[] {name}, type, dclass);
    }
    
    
    public static ServiceInstance[] lookupServices(Name[] names, int type, int dclass)
    throws IOException
    {
        Lookup lookup = new Lookup(names, type, dclass);
        try
        {
            return lookup.lookupServices();
        } finally
        {
            lookup.close();
        }
    }
}
