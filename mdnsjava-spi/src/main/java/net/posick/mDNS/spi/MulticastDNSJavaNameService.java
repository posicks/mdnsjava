package net.posick.mDNS.spi;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.StringTokenizer;

import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.PTRRecord;
import org.xbill.DNS.Record;
import org.xbill.DNS.ReverseMap;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import net.posick.mDNS.Lookup;

/**
 * This class implements a Name Service Provider, which Java can use
 * (starting with version 1.4), to perform mDNS resolutions instead of using
 * the standard calls.
 * <p>
 * This Name Service Provider uses mdnsjava.
 * <p>
 * To use this provider, you must set the following system property:
 * <b>sun.net.spi.nameservice.provider.1=dns,mdnsjava</b>
 *
 * @author Brian Wellington
 * @author Paul Cowan (pwc21@yahoo.com)
 * @author Steve Posick (posicks@gmail.com)
 */

public class MulticastDNSJavaNameService implements InvocationHandler
{
    
    private static final String nsProperty = "sun.net.spi.nameservice.nameservers";
    
    private static final String domainProperty = "sun.net.spi.nameservice.domain";
    
    private static final String v6Property = "java.net.preferIPv6Addresses";
    
    private boolean preferV6 = false;
    
    
    /**
     * Creates a mDNSJavaNameService instance.
     * <p>
     * Uses the
     * <b>sun.net.spi.nameservice.nameservers</b>,
     * <b>sun.net.spi.nameservice.domain</b>, and
     * <b>java.net.preferIPv6Addresses</b> properties for configuration.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    protected MulticastDNSJavaNameService()
    {
        String nameServers = System.getProperty(nsProperty);
        String domain = System.getProperty(domainProperty);
        String v6 = System.getProperty(v6Property);
        
        if (v6 != null && v6.equalsIgnoreCase("true"))
            preferV6 = true;
            
        if (nameServers != null)
        {
            StringTokenizer st = new StringTokenizer(nameServers, ",");
            String[] servers = new String[st.countTokens()];
            ArrayList resolvers = new ArrayList(servers.length);
            int n = 0;
            while (st.hasMoreTokens())
            {
                servers[n++] = st.nextToken();
                try
                {
                    resolvers.add(new SimpleResolver(servers[n]));
                } catch (UnknownHostException e)
                {
                    System.err.println("mDNSJavaNameService: Unknown Host " + servers[n]);
                }
            }
        }
        
        if (domain != null)
        {
            try
            {
                Lookup.setDefaultSearchPath(new String[] {domain});
            } catch (TextParseException e)
            {
                System.err.println("mDNSJavaNameService: invalid " + domainProperty);
            }
        }
    }
    
    
    @SuppressWarnings("rawtypes")
    public Object invoke(Object proxy, Method method, Object[] args)
    throws Throwable
    {
        try
        {
            if (method.getName().equals("getHostByAddr"))
            {
                return this.getHostByAddr((byte[]) args[0]);
            } else if (method.getName().equals("lookupAllHostAddr"))
            {
                InetAddress[] addresses;
                addresses = this.lookupAllHostAddr((String) args[0]);
                Class returnType = method.getReturnType();
                if (returnType.equals(InetAddress[].class))
                {
                    // method for Java >= 1.6
                    return addresses;
                } else if (returnType.equals(byte[][].class))
                {
                    // method for Java <= 1.5
                    int naddrs = addresses.length;
                    byte[][] byteAddresses = new byte[naddrs][];
                    byte[] addr;
                    for (int i = 0; i < naddrs; i++ )
                    {
                        addr = addresses[i].getAddress();
                        byteAddresses[i] = addr;
                    }
                    return byteAddresses;
                } else
                {
                    throw new IllegalArgumentException("No method matching signature \"" + method.toString() + "\".");
                }
            } else
            {
                throw new IllegalArgumentException("Unknown method \"" + method.toString() + "\".");
            }
        } catch (Throwable e)
        {
            System.err.println("mDNSJavaNameService: Unexpected error.");
            e.printStackTrace();
            throw e;
        }
    }


    /**
     * Performs a forward DNS lookup for the host name.
     * 
     * @param host The host name to resolve.
     * @return All the ip addresses found for the host name.
     */
    public InetAddress[] lookupAllHostAddr(String host)
    throws UnknownHostException, IOException
    {
        Name name = null;
        
        try
        {
            name = new Name(host);
        } catch (TextParseException e)
        {
            throw new UnknownHostException(host);
        }
        
        Record[] records = null;
        if (preferV6)
        {
            records = Lookup.lookupRecords(name, Type.AAAA);
            if (records == null || records.length == 0)
            {
                records = Lookup.lookupRecords(name, Type.A);
            }
        } else
        {
            records = Lookup.lookupRecords(name, Type.A);
            if (records == null || records.length == 0)
            {
                records = Lookup.lookupRecords(name, Type.AAAA);
            }
        }
        
        if (records == null || records.length == 0)
        {
            throw new UnknownHostException(host);
        }
            
        InetAddress[] array = new InetAddress[records.length];
        for (int i = 0; i < records.length; i++ )
        {
            Record record = records[i];
            if (record instanceof ARecord)
            {
                ARecord a = (ARecord) record;
                array[i] = a.getAddress();
            } else
            {
                AAAARecord aaaa = (AAAARecord) record;
                array[i] = aaaa.getAddress();
            }
        }
        return array;
    }
    
    
    /**
     * Performs a reverse DNS lookup.
     * 
     * @param addr The ip address to lookup.
     * @return The host name found for the ip address.
     */
    public String getHostByAddr(byte[] addr)
    throws UnknownHostException, IOException
    {
        Name name = ReverseMap.fromAddress(InetAddress.getByAddress(addr));
        Record[] records = Lookup.lookupRecords(name, Type.PTR);
        
        if (records == null || records.length == 0)
        {
            throw new UnknownHostException();
        }
        
        return ((PTRRecord) records[0]).getTarget().toString();
    }
}