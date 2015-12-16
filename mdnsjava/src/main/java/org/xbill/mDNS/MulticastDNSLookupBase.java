package org.xbill.mDNS;

import java.io.Closeable;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.MulticastDNSUtils;
import org.xbill.DNS.Name;
import org.xbill.DNS.NameTooLongException;
import org.xbill.DNS.Options;
import org.xbill.DNS.PTRRecord;
import org.xbill.DNS.Record;
import org.xbill.DNS.ResolverConfig;
import org.xbill.DNS.SRVRecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

@SuppressWarnings({"unchecked", "rawtypes"})
public abstract class MulticastDNSLookupBase implements Closeable, Constants
{
    protected static Querier defaultQuerier;
    
    protected static Name[] defaultSearchPath;
    
    
    protected static final Comparator SERVICE_RECORD_SORTER = new Comparator()
    {
        public int compare(final Object o1, final Object o2)
        {
            if (o1 instanceof Record)
            {
                if (o2 instanceof Record)
                {
                    final Record thisRecord = (Record) o1;
                    final Record thatRecord = (Record) o2;
                    
                    final int thisType = thisRecord.getType();
                    final int thatType = thatRecord.getType();
                    
                    switch (thisType)
                    {
                        case Type.SRV:
                            return thatType == Type.SRV ? 0 : -1;
                        case Type.PTR:
                            switch (thatType)
                            {
                                case Type.SRV:
                                    return +1;
                                case Type.PTR:
                                    return 0;
                                default:
                                    return -1;
                            }
                        case Type.TXT:
                            switch (thatType)
                            {
                                case Type.PTR:
                                case Type.SRV:
                                    return +1;
                                case Type.TXT:
                                    return 0;
                                default:
                                    return -1;
                            }
                        case Type.A:
                        case Type.AAAA:
                            switch (thatType)
                            {
                                case Type.PTR:
                                case Type.SRV:
                                case Type.TXT:
                                    return +1;
                                case Type.A:
                                case Type.AAAA:
                                    return 0;
                                default:
                                    return -1;
                            }
                        case Type.NSEC:
                            switch (thatType)
                            {
                                case Type.PTR:
                                case Type.SRV:
                                case Type.TXT:
                                case Type.A:
                                case Type.AAAA:
                                    return +1;
                                case Type.NSEC:
                                    return 0;
                                default:
                                    return -1;
                            }
                        default:
                            return -1;
                            
                    }
                }
            }
            
            return -1;
        }
    };
    
    
    protected Name[] names;
    
    protected Querier querier;
    
    protected Name[] searchPath;
    
    protected int type = Type.ANY;
    
    protected Object browseID;
    
    protected int dclass = DClass.ANY;
    
    protected Message[] queries;
    
    protected boolean mdnsVerbose;
    
    
    public MulticastDNSLookupBase(final Name... names)
    throws IOException
    {
        this(names, Type.ANY, DClass.ANY);
    }
    
    
    public MulticastDNSLookupBase(final Name[] names, final int type)
    throws IOException
    {
        this(names, Type.ANY, DClass.ANY);
    }
    
    
    public MulticastDNSLookupBase(final Name[] names, final int type, final int dclass)
    throws IOException
    {
        this();
        
        this.names = names;
        this.type = type;
        this.dclass = dclass;
        buildQueries();
    }
    
    
    public MulticastDNSLookupBase(final String... names)
    throws IOException
    {
        this(names, Type.ANY, DClass.ANY);
    }
    
    
    public MulticastDNSLookupBase(final String[] names, final int type)
    throws IOException
    {
        this(names, type, DClass.ANY);
    }
    
    
    public MulticastDNSLookupBase(final String[] names, final int type, final int dclass)
    throws IOException
    {
        this();
        
        if ((names != null) && (names.length > 0))
        {
            ArrayList domainNames = new ArrayList();
            for (int index = 0; index < names.length; index++ )
            {
                if (names[index].endsWith("."))
                {
                    try
                    {
                        domainNames.add(new Name(names[index]));
                    } catch (TextParseException e)
                    {
                        if (mdnsVerbose)
                        {
                            System.err.println("Error parsing \"" + names[index] + "\" - " + e.getMessage());
                        }
                    }
                } else
                {
                    for (int i = 0; i < searchPath.length; i++ )
                    {
                        try
                        {
                            domainNames.add(new Name(names[index] + "." + searchPath[i]));
                        } catch (TextParseException e)
                        {
                            if (mdnsVerbose)
                            {
                                System.err.println("Error parsing \"" + (names[index] + "." + searchPath[i]) + "\" - " + e.getMessage());
                            }
                        }
                    }
                }
            }
            
            this.names = (Name[]) domainNames.toArray(new Name[domainNames.size()]);
            this.type = type;
            this.dclass = dclass;
            buildQueries();
        } else
        {
            throw new UnknownHostException("Invalid Name(s) specified!");
        }
    }
    
    
    protected MulticastDNSLookupBase()
    throws IOException
    {
        super();
        
        mdnsVerbose = Options.check("mdns_verbose") || Options.check("verbose");
        
        querier = getDefaultQuerier();
        searchPath = getDefaultSearchPath();
    }
    
    
    protected MulticastDNSLookupBase(final Message message)
    throws IOException
    {
        this();
        queries = new Message[] {(Message) message.clone()};
        
        int type = -1;
        int dclass = -1;
        List list = new ArrayList();
        Record[] records = MulticastDNSUtils.extractRecords(message, Section.QUESTION);
        for (Record r : records)
        {
            if (!list.contains(r))
            {
                list.add(r.getName());
            }
            
            type = type < 0 ? r.getType() : Type.ANY;
            dclass = dclass < 0 ? r.getDClass() : DClass.ANY;
        }
        
        if (list.size() > 0)
        {
            this.type = type;
            this.dclass = dclass;
            names = (Name[]) list.toArray(new Record[list.size()]);
        }
    }
    
    
    /**
     * Adds the name to the list of names to browse
     * 
     * @param names Names to add
     */
    public void addNames(final Name[] names)
    {
        if ((names != null) && (names.length > 0))
        {
            Name[] temp = this.names;
            Name[] newNames = new Name[temp.length + names.length];
            System.arraycopy(temp, 0, newNames, 0, temp.length);
            System.arraycopy(temp, temp.length, newNames, temp.length, names.length);
            this.names = newNames;
            buildQueries();
        }
    }
    
    
    /**
     * Adds the name to the list of names to browse
     * 
     * @param names Names to add
     * @throws TextParseException If name is invalid
     */
    public void addNames(final String[] names)
    throws TextParseException
    {
        if ((names != null) && (names.length > 0))
        {
            Name[] newnames = new Name[names.length];
            for (int i = 0; i < names.length; i++ )
            {
                newnames[i] = Name.fromString(names[i], Name.root);
            }
            addNames(newnames);
        }
    }
    
    
    /**
     * Adds a domain to the search path that is used during lookups.
     * 
     * @param searchPath Name to add to search path
     */
    public void addSearchPath(final Name[] searchPath)
    {
        if ((searchPath != null) && (searchPath.length > 0))
        {
            Name[] temp = this.searchPath;
            Name[] newNames = new Name[temp.length + searchPath.length];
            System.arraycopy(temp, 0, newNames, 0, temp.length);
            System.arraycopy(temp, temp.length, newNames, temp.length, names.length);
            this.searchPath = newNames;
            buildQueries();
        }
    }
    
    
    /**
     * Adds a domain to the search path that is used during lookups.
     * 
     * @param searchPath Name to add to search path
     * @throws TextParseException If name is invalid
     */
    public void addSearchPath(final String[] searchPath)
    throws TextParseException
    {
        if ((searchPath != null) && (searchPath.length > 0))
        {
            Name[] newnames = new Name[searchPath.length];
            for (int i = 0; i < searchPath.length; i++ )
            {
                newnames[i] = Name.fromString(searchPath[i], Name.root);
            }
            addSearchPath(newnames);
        }
    }
    
    
    public Name[] getNames()
    {
        return names;
    }
    
    
    /**
     * Gets the Responder that is being used for this browse operations.
     * 
     * @return The responder
     */
    public synchronized Querier getQuerier()
    {
        return querier;
    }
    
    
    public Name[] getSearchPath()
    {
        return searchPath;
    }
    
    
    /**
     * Sets the names to browse
     * 
     * @param names Names to browse
     */
    public void setNames(final Name[] names)
    {
        this.names = names;
        buildQueries();
    }
    
    
    /**
     * Sets the names to browse
     * 
     * @param names Names to browse
     */
    public void setNames(final String[] names)
    throws TextParseException
    {
        if (names == null)
        {
            this.names = null;
            return;
        }
        Name[] newnames = new Name[names.length];
        for (int i = 0; i < names.length; i++ )
        {
            newnames[i] = Name.fromString(names[i], Name.root);
        }
        setNames(newnames);
    }
    
    
    /**
     * Sets the Responder to be used for this browse operation.
     * 
     * @param responder The responder
     */
    public synchronized void setQuerier(final Querier querier)
    {
        this.querier = querier;
    }
    
    
    /**
     * Sets the search path to use when performing this lookup. This overrides
     * the default value.
     * 
     * @param domains An array of names containing the search path.
     */
    public void setSearchPath(final Name[] domains)
    {
        searchPath = domains;
        buildQueries();
    }
    
    
    /**
     * Sets the search path to use when performing this lookup. This overrides
     * the default value.
     * 
     * @param domains An array of names containing the search path.
     * @throws TextParseException A name in the array is not a valid DNS name.
     */
    public void setSearchPath(final String[] domains)
    throws TextParseException
    {
        if (domains == null)
        {
            searchPath = null;
            return;
        }
        Name[] newdomains = new Name[domains.length];
        for (int i = 0; i < domains.length; i++ )
        {
            newdomains[i] = Name.fromString(domains[i], Name.root);
        }
        setSearchPath(newdomains);
    }
    
    
    protected void buildQueries()
    {
        if ((this.names != null) && (searchPath != null))
        {
        	ArrayList searchNames = new ArrayList();
        	ArrayList newQueries = new ArrayList();
        	Message multicastQuery = null;
            for (int index = 0; index < this.names.length; index++)
            {
                Name name = this.names[index];
                if (name.isAbsolute())
                {
                	if (MulticastDNSService.isMulticastDomain(name))
                	{
                		if (multicastQuery == null)
                		{
                			multicastQuery = Message.newQuery(Record.newRecord(name, type, dclass));
                		} else
                		{
                			multicastQuery.addRecord(Record.newRecord(name, type, dclass), Section.QUESTION);
                		}
                	} else
                	{
                		newQueries.add(Message.newQuery(Record.newRecord(name, type, dclass)));
                	}
                    searchNames.add(name);
                } else
                {
                    for (int i = 0; i < searchPath.length; i++ )
                    {
                        Name absoluteName;
                        try
                        {
                            absoluteName = Name.concatenate(name, searchPath[i]);
	                    	if (MulticastDNSService.isMulticastDomain(searchPath[i]))
	                    	{
	                    		// Use a single Message for Multicast Queries.
	                    		if (multicastQuery == null)
	                    		{
	                    			multicastQuery = Message.newQuery(Record.newRecord(absoluteName, type, dclass));
	                    		} else
	                    		{
	                    			multicastQuery.addRecord(Record.newRecord(absoluteName, type, dclass), Section.QUESTION);
	                    		}
	                    	} else
	                    	{
	                    		// Create a Message for each Unicast Query.
	                        	newQueries.add(Message.newQuery(Record.newRecord(absoluteName, type, dclass)));
	                    	}
                            searchNames.add(absoluteName);
                        } catch (NameTooLongException e)
                        {
                            if (mdnsVerbose)
                            {
                                System.err.println(e.getMessage());
                                e.printStackTrace(System.err);
                            }
                        }
                    }
                }
            }
            
            if (multicastQuery != null)
            {
            	newQueries.add(multicastQuery);
            }
            this.names = (Name[]) searchNames.toArray(new Name[searchNames.size()]);
            this.queries = (Message[]) newQueries.toArray(new Message[newQueries.size()]);
        }
    }
    
    
    /**
     * Gets the mDNS Querier that will be used as the default by future Lookups.
     * 
     * @return The default responder.
     */
    public static synchronized Querier getDefaultQuerier()
    {
        if (defaultQuerier == null)
        {
            try
            {
                defaultQuerier = new MulticastDNSQuerier(true, true);
            } catch (IOException e)
            {
                System.err.println(e.getMessage());
                e.printStackTrace(System.err);
            }
        }
        
        return defaultQuerier;
    }
    
    
    /**
     * Gets the search path that will be used as the default by future Lookups.
     * 
     * @return The default search path.
     */
    public static synchronized Name[] getDefaultSearchPath()
    {
        if (defaultSearchPath == null)
        {
            Name[] configuredSearchPath = ResolverConfig.getCurrentConfig().searchPath();
            defaultSearchPath = new Name[(configuredSearchPath != null ? configuredSearchPath.length : 0) + defaultQuerier.getMulticastDomains().length];
            int startPos = 0;
            if (configuredSearchPath != null)
            {
                defaultSearchPath = new Name[configuredSearchPath.length + defaultQuerier.getMulticastDomains().length];
                System.arraycopy(configuredSearchPath, 0, defaultSearchPath, startPos, configuredSearchPath.length);
                startPos = configuredSearchPath.length;
            } else
            {
                defaultSearchPath = new Name[defaultQuerier.getMulticastDomains().length];
            }
            System.arraycopy(defaultQuerier.getMulticastDomains(), 0, defaultSearchPath, startPos, defaultQuerier.getMulticastDomains().length);
        }
        
        return defaultSearchPath;
    }
    
    
    /**
     * Sets the default mDNS Querier to be used as the default by future Lookups.
     * 
     * @param responder The default responder.
     */
    public static synchronized void setDefaultQuerier(final Querier querier)
    {
        defaultQuerier = querier;
    }
    
    
    /**
     * Sets the search path to be used as the default by future Lookups.
     * 
     * @param domains The default search path.
     */
    public static synchronized void setDefaultSearchPath(final Name[] domains)
    {
        defaultSearchPath = domains;
    }
    
    /**
     * Sets the search path that will be used as the default by future Lookups.
     * 
     * @param domains The default search path.
     * @throws TextParseException A name in the array is not a valid DNS name.
     */
    public static synchronized void setDefaultSearchPath(final String[] domains)
    throws TextParseException
    {
        if (domains == null)
        {
            defaultSearchPath = null;
            return;
        }
        Name[] newdomains = new Name[domains.length];
        for (int i = 0; i < domains.length; i++ )
        {
            newdomains[i] = Name.fromString(domains[i], Name.root);
        }
        defaultSearchPath = newdomains;
    }
    
    
    protected static ServiceInstance[] extractServiceInstances(final Message... messages)
    {
        Record[] records = null;
        
        for (Message message : messages)
        {
            Record[] temp = MulticastDNSUtils.extractRecords(message, Section.AUTHORITY, Section.ANSWER, Section.ADDITIONAL);
            if (records == null)
            {
                records = temp;
            } else
            {
                Record[] old = records;
                records = new Record[records.length + temp.length];
                System.arraycopy(old, 0, records, 0, records.length);
                System.arraycopy(temp, 0, records, records.length, temp.length);
            }
        }
        
        return extractServiceInstances(records);
    }
    
    
    protected static ServiceInstance[] extractServiceInstances(final Record[] records)
    {
        Map services = new HashMap();
        
        ServiceInstance service = null;
        Arrays.sort(records, SERVICE_RECORD_SORTER);
        for (Record record : records)
        {
            switch (record.getType())
            {
                case Type.SRV:
                    try
                    {
                        service = new ServiceInstance((SRVRecord) record);
                        services.put(service.getName(), service);
                    } catch (TextParseException e)
                    {
                        System.err.println("Error processing SRV record \"" + record.getName() + "\" - " + e.getMessage());
                    }
                    break;
                case Type.PTR:
                    PTRRecord ptr = (PTRRecord) record;
                    service = (ServiceInstance) services.get(ptr.getTarget());
                    if (service != null)
                    {
                        if (ptr.getTTL() > 0)
                        {
                            service.addPointer(ptr.getName());
                        } else
                        {
                            service.removePointer(ptr.getName());
                        }
                    }
                    break;
                case Type.TXT:
                    TXTRecord txt = (TXTRecord) record;
                    service = (ServiceInstance) services.get(txt.getName());
                    if (service != null)
                    {
                        if (txt.getTTL() > 0)
                        {
                            service.addTextRecords(txt);
                        } else
                        {
                            service.removeTextRecords(txt);
                        }
                    }
                    break;
                case Type.A:
                    ARecord a = (ARecord) record;
                    for (Object o : services.values())
                    {
                        service = (ServiceInstance) o;
                        if (a.getName().equals(service.getHost()))
                        {
                            if (a.getTTL() > 0)
                            {
                                service.addAddress(a.getAddress());
                            } else
                            {
                                service.removeAddress(a.getAddress());
                            }
                        }
                    }
                    break;
                case Type.AAAA:
                    AAAARecord aaaa = (AAAARecord) record;
                    for (Object o : services.values())
                    {
                        service = (ServiceInstance) o;
                        if (aaaa.getName().equals(service.getHost()))
                        {
                            if (aaaa.getTTL() > 0)
                            {
                                service.addAddress(aaaa.getAddress());
                            } else
                            {
                                service.removeAddress(aaaa.getAddress());
                            }
                        }
                    }
                    break;
            }
            service = null;
        }
        
        return (ServiceInstance[]) services.values().toArray(new ServiceInstance[services.size()]);
    }
}
