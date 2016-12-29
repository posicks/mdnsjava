import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.MulticastDNSUtils;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;

import net.posick.mDNS.Browse;
import net.posick.mDNS.Constants;
import net.posick.mDNS.DNSSDListener;
import net.posick.mDNS.Lookup;
import net.posick.mDNS.MulticastDNSService;
import net.posick.mDNS.ServiceInstance;
import net.posick.mDNS.ServiceName;
import net.posick.mDNS.Lookup.Domain;
import net.posick.mDNS.utils.ExecutionTimer;

@SuppressWarnings({"unchecked", "rawtypes"})
public class dnssd
{
    private static final String VERSION = "mdnsjava: 2.1.7";
    
    private static final String COMMAND_LINE =
    "------------------------------------------------------------------------------\n" +
    "| Command Line: dnssd <option> [parameters]                                  |\n" +
    "------------------------------------------------------------------------------\n" +
    "dnssd -E                         (Enumerate recommended registration domains)\n" +
    "dnssd -F                             (Enumerate recommended browsing domains)\n" +
    "dnssd -B        <Type> [<Domain>]             (Browse for services instances)\n" +
    "dnssd -L <Name> <Type> [<Domain>]                (Look up a service instance)\n" +
    "dnssd -R <Name> <Type> <Domain> <Port> <Host> [<TXT>...] (Register a service)\n" +
    "dnssd -Q <FQDN> <rrtype> <rrclass>        (Generic query for any record type)\n" +
    "dnssd -G v4/v6/v4v6 <Hostname>         (Get address information for hostname)\n" +
    "dnssd -V           (Get version of currently running daemon / system service)\n" +
    "------------------------------------------------------------------------------";
    
    
    protected dnssd()
    throws UnknownHostException
    {
    }
    
    
    /**
     * Test program
     * @param args The input parameters
     * 
     * <code>
     * Command Line:
     * dnssd -E                         (Enumerate recommended registration domains)
     * dnssd -F                             (Enumerate recommended browsing domains)
     * dnssd -B        &lt;Type&gt; [&lt;Domain&gt;]             (Browse for services instances)
     * dnssd -L &lt;Name&gt; &lt;Type&gt; [&lt;Domain&gt;]                (Look up a service instance)
     * dnssd -R &lt;Name&gt; &lt;Type&gt; &lt;Domain&gt; &lt;Port&gt; &lt;Host&gt; [&lt;TXT&gt;...] (Register a service)
     * dnssd -Q &lt;FQDN&gt; &lt;rrtype&gt; &lt;rrclass&gt;        (Generic query for any record type)
     * dnssd -G v4/v6/v4v6 &lt;Hostname&gt;         (Get address information for hostname)
     * dnssd -V           (Get version of currently running daemon / system service)
     * </code>
     */
    public static void main(final String[] args)
    throws Exception
    {
        /*
         * TODO: Java has a bug were IPv6 doesn't work and another bug where the IP TTL does not
         *       get set on outbound packets if IPv6 is enabled.  The following code works around
         *       this issue.
         * 
         *       Remove when IPv6 and Socket.setTimeToLive() are working.
         */
        //        Properties props = System.getProperties();
        //        props.setProperty("java.net.preferIPv4Stack","true");
        //        System.setProperties(props);
        
        StringBuilder timingBuilder = new StringBuilder();
        try
        {
            if (args.length > 0)
            {
                timingBuilder.append("Execution of \"dnssd ");
                for (String arg : args)
                {
                    timingBuilder.append(arg).append(" ");
                }
                timingBuilder.setLength(timingBuilder.length() - 1);
                timingBuilder.append("\"");
                
                String temp = args[0];
                
                if (temp != null && (temp.length() == 2 || temp.length() == 3) &&
                (temp.startsWith("-") || temp.startsWith("--")))
                {
                    Name[] browseDomains;
                    ArrayList domainNames;
                    Name[] serviceTypes;
                    Domain[] domains = null;
                    Record[] records = null;
                    int type = Type.ANY;
                    int dclass = DClass.ANY;
                    char option = temp.charAt(temp.length() - 1);
                    ExecutionTimer._start();
                    switch (option)
                    {
                        case 'E':
                            // Enumerate recommended registration domains
                            ExecutionTimer._start();
                            Lookup lookup = new Lookup(Constants.DEFAULT_REGISTRATION_DOMAIN_NAME, Constants.REGISTRATION_DOMAIN_NAME);
                            try
                            {
                                domains = lookup.lookupDomains();
                            } finally
                            {
                                lookup.close();
                            }
                            System.out.println("Registration Domains:");
                            dnssd.printArray(domains, "\t%s\n");
                            System.out.println("\n" + timingBuilder.toString() + " - took " + ExecutionTimer._took(TimeUnit.SECONDS) + " seconds.");
                            break;
                        case 'F':
                            // Enumerate recommended browse domains
                            ExecutionTimer._start();
                            lookup = new Lookup(Constants.DEFAULT_BROWSE_DOMAIN_NAME, Constants.BROWSE_DOMAIN_NAME, Constants.LEGACY_BROWSE_DOMAIN_NAME);
                            try
                            {
                                domains = lookup.lookupDomains();
                            } finally
                            {
                                lookup.close();
                            }
                            System.out.println("Browse Domains:");
                            dnssd.printArray(domains, "\t%s\n");
                            System.out.println("\n" + timingBuilder.toString() + " - took " + ExecutionTimer._took(TimeUnit.SECONDS) + " seconds.");
                            break;
                        case 'B':
                            // Browse for service instances
                            if (args.length < 2 || args[1] == null || args[1].length() == 0)
                            {
                                throw new IllegalArgumentException("Too few arguments for -B option");
                            }
                            
                            ExecutionTimer._start();
                            if (args.length == 2)
                            {
                                domainNames = new ArrayList();
                                
                                lookup = new Lookup(Constants.DEFAULT_BROWSE_DOMAIN_NAME, Constants.BROWSE_DOMAIN_NAME, Constants.LEGACY_BROWSE_DOMAIN_NAME);
                                try
                                {
                                    domains = lookup.lookupDomains();
                                } finally
                                {
                                    lookup.close();
                                }
                                
                                if (domains != null && domains.length > 0)
                                {
                                    for (int index = 0; index < domains.length; index++)
                                    {
                                        if (domains[index] != null && !domainNames.contains(domains[index]))
                                        {
                                            domainNames.add(domains[index].getName());
                                        }
                                    }
                                }
                                
                                browseDomains = (Name[]) domainNames.toArray(new Name[domainNames.size()]);
                            } else
                            {
                                browseDomains = new Name[] {new Name(args[2] + (args[2].endsWith(".") ? "" : "."))};
                            }
                            System.out.println("Searching for Browse Domains - took " + ExecutionTimer._took(TimeUnit.SECONDS) + " seconds.");
                            
                            serviceTypes = new Name[browseDomains.length];
                            for (int i = 0; i < browseDomains.length; i++)
                            {
                                serviceTypes[i] = new Name(args[1], browseDomains[i]);
                            }
                            System.out.println("Browsing for Services of the following types:");
                            dnssd.printArray(serviceTypes, "\t%s\n");
                            System.out.println();
                            System.out.println("Services Found:");
                            ExecutionTimer._start();
                            MulticastDNSService mDNSService = new MulticastDNSService();
                            Object id = mDNSService.startServiceDiscovery(new Browse(serviceTypes), new DNSSDListener()
                            {
                                public void handleException(final Object id, final Exception e)
                                {
                                    if (!(e instanceof IOException && "no route to host".equalsIgnoreCase(e.getMessage())))
                                    {
                                        System.err.println("Exception: " + e.getMessage());
                                        e.printStackTrace(System.err);
                                    }
                                }
                                
                                
                                public void receiveMessage(final Object id, final Message m)
                                {
                                }
                                
                                
                                public void serviceDiscovered(final Object id, final ServiceInstance service)
                                {
                                    System.out.println("Service Discovered - " + service);
                                }
                                
                                
                                public void serviceRemoved(final Object id, final ServiceInstance service)
                                {
                                    System.out.println("Service Removed - " + service);
                                }
                            });
                            System.out.println("\nStarting Browse for " + timingBuilder.toString() + " - took " + ExecutionTimer._took(TimeUnit.SECONDS) + " seconds.");
                            while (true)
                            {
                                Thread.sleep(10);
                                if (System.in.read() == 'q')
                                {
                                    break;
                                }
                            }
                            mDNSService.stopServiceDiscovery(id);
                            mDNSService.close();
                            System.out.println("\n" + timingBuilder.toString() + " - took " + ExecutionTimer._took(TimeUnit.SECONDS) + " seconds.");
                            break;
                        case 'L':
                            // Lookup a service
                            if (args.length < 3 || args[2] == null || args[2].length() == 0)
                            {
                                throw new IllegalArgumentException("Too few arguments for -L option");
                            }
                            
                            ExecutionTimer._start();
                            if (args.length == 3)
                            {
                                domainNames = new ArrayList();
                                
                                lookup = new Lookup(Constants.DEFAULT_BROWSE_DOMAIN_NAME, Constants.BROWSE_DOMAIN_NAME, Constants.LEGACY_BROWSE_DOMAIN_NAME);
                                try
                                {
                                    domains = lookup.lookupDomains();
                                } finally
                                {
                                    lookup.close();
                                }
                                
                                if (domains != null && domains.length > 0)
                                {
                                    for (int index = 0; index < domains.length; index++)
                                    {
                                        if (domains[index] != null && !domainNames.contains(domains[index]))
                                        {
                                            domainNames.add(domains[index].getName());
                                        }
                                    }
                                }
                                
                                browseDomains = (Name[]) domainNames.toArray(new Name[domainNames.size()]);
                            } else
                            {
                                browseDomains = new Name[] {new Name(args[3] + (args[3].endsWith(".") ? "" : "."))};
                            }
                            System.out.println("\nSearching for Browse Domains - took " + ExecutionTimer._took(TimeUnit.SECONDS) + " seconds.");
                            
                            serviceTypes = new Name[browseDomains.length];
                            for (int i = 0; i < browseDomains.length; i++)
                            {
                                serviceTypes[i] = new Name(args[1] + "." + args[2], browseDomains[i]);
                            }
                            ExecutionTimer._start();
                            System.out.println("Lookup Service :");
                            dnssd.printArray(serviceTypes, "\t%s\n");
                            System.out.println();
                            System.out.println("Services Found:");
                            lookup = new Lookup(serviceTypes);
                            try
                            {
                                dnssd.printArray(lookup.lookupServices(), "\t%s\n");
                            } finally
                            {
                                lookup.close();
                            }
                            System.out.println("\n" + timingBuilder.toString() + " - took " + ExecutionTimer._took(TimeUnit.SECONDS) + " seconds.");
                            break;
                        case 'R':
                            // Register a Service
                            if (args.length < 5)
                            {
                                throw new IllegalArgumentException("Too few arguments for -R option");
                            }
                            String[] txtValues = new String[args.length - 6];
                            if (args.length > 6)
                            {
                                System.arraycopy(args, 6, txtValues, 0, txtValues.length);
                            }
                            ServiceName serviceName = new ServiceName(args[1] + "." + args[2] + "." + args[3]);
                            
                            String host = args[5];
                            int port = Integer.parseInt(args[4]);
                            
                            if (host == null || host.length() == 0)
                            {
                                String machineName = MulticastDNSUtils.getMachineName();
                                if (machineName == null)
                                {
                                    host = MulticastDNSUtils.getHostName();
                                } else
                                {
                                    host = machineName.endsWith(".") ? machineName : machineName + ".";
                                }
                            }
                            Name hostname = new Name(host);
                            
                            InetAddress[] addresses = null;
                            try
                            {
                                addresses = InetAddress.getAllByName(hostname.toString());
                            } catch (UnknownHostException e)
                            {
                            }
                            
                            if (addresses == null || addresses.length == 0)
                            {
                                addresses = MulticastDNSUtils.getLocalAddresses();
                            }
                            ExecutionTimer._start();
                            mDNSService = new MulticastDNSService();
                            ServiceInstance service = new ServiceInstance(serviceName, 0, 0, port, hostname/*, MulticastDNSService.DEFAULT_SRV_TTL*/, addresses, txtValues);
                            ServiceInstance registeredService = mDNSService.register(service);
                            if (registeredService != null)
                            {
                                System.out.println("Services Successfully Registered: \n\t" + registeredService);
                            } else
                            {
                                System.err.println("Services Registration Failed!");
                            }
                            while (true)
                            {
                                Thread.sleep(10);
                                if (System.in.read() == 'q')
                                {
                                    if (mDNSService.unregister(registeredService))
                                    {
                                        System.out.println("Services Successfully Unregistered: \n\t" + service);
                                    } else
                                    {
                                        System.err.println("Services Unregistration Failed!");
                                    }
                                    break;
                                }
                            }
                            mDNSService.close();
                            System.out.println("\n" + timingBuilder.toString() + " - took " + ExecutionTimer._took(TimeUnit.SECONDS) + " seconds.");
                            break;
                        case 'Q':
                            // TODO: Fix Query Results!  Add Additional "Known" Records.  Why doesn't the SRV record return after expirey?
                            if (args.length < 4)
                            {
                                throw new IllegalArgumentException("Too few arguments for -" + option + " option");
                            }
                            type = Type.value(args[2], true);
                            if (type < 0)
                            {
                                throw new IllegalArgumentException("Invalid Type \"" + args[2] + "\" specified.");
                            }
                            dclass = DClass.value(args[3]);
                            if (dclass < 0)
                            {
                                throw new IllegalArgumentException("Invalid DClass \"" + args[3] + "\" specified.");
                            }
                            ExecutionTimer._start();
                            lookup = new Lookup(new Name[]{new Name(args[1])}, type, dclass);
                            try
                            {
                                records = lookup.lookupRecords();
                                System.out.println("Query Resource Records :\n\tName: " + args[1] + ", Type: " + Type.string(type) + ", DClass: " + DClass.string(dclass));
                                System.out.println();
                                System.out.println("Resource Records Found:");
                                dnssd.printArray(records, "\t%s\n");
                            } finally
                            {
                                lookup.close();
                            }
                            System.out.println("\n" + timingBuilder.toString() + " - took " + ExecutionTimer._took(TimeUnit.SECONDS) + " seconds.");
                            break;
                        case 'G':
                            if (args.length < 2)
                            {
                                throw new IllegalArgumentException("Too few arguments for -" + option + " option");
                            }
                            if (args.length > 2)
                            {
                                type = Type.value(args[2], true);
                                if (type < 0)
                                {
                                    throw new IllegalArgumentException("Invalid Type \"" + args[2] + "\" specified.");
                                }
                            }
                            if (args.length > 3)
                            {
                                dclass = DClass.value(args[3]);
                                if (dclass < 0)
                                {
                                    throw new IllegalArgumentException("Invalid DClass \"" + args[3] + "\" specified.");
                                }
                            }
                            
                            Name name = new Name(args[1]);
                            Name[] names;
                            if (!name.isAbsolute() && name.labels() > 1)
                            {
                            	names = new Name[]{name, new Name(args[1] + ".")};
                            } else
                            {
                            	names = new Name[]{name};
                            }
                            lookup = new Lookup(names, type, dclass);
                            try
                            {
                                records = lookup.lookupRecords();
                            } finally
                            {
                                lookup.close();
                            }
                            
                            System.out.println("Lookup Names:");
                            dnssd.printArray(lookup.getNames(), "\t%s\n");
                            System.out.println();
                            System.out.println("Resource Records Found:");
                            dnssd.printArray(records, "\t%s\n");
                            break;
                        case 'V':
                            System.out.println("\n" + dnssd.VERSION);
                            break;
                        default:
                            throw new IllegalArgumentException("Invalid option \"" + args[0] + "\" specified!");
                    }
                } else
                {
                    throw new IllegalArgumentException("Invalid option \"" + args[0] + "\" specified!");
                }
            } else
            {
                throw new IllegalArgumentException((String) null);
            }
        } catch (IllegalArgumentException e)
        {
            dnssd.printHelp(e.getMessage());
        } finally
        {
            System.out.println("\nTotal " + timingBuilder.toString() + " - took " + ExecutionTimer._took(TimeUnit.SECONDS) + " seconds.");
        }
        
        System.exit(0);
    }
    
    
    private static void printArray(final Object[] array, final String... format)
    {
        if (array != null && format != null && array.length > 0 && format.length > 0)
        {
            int startFormat = 0;
            int endFormat = format.length - 1;
            int lastElementFormat = -1;
            
            // Process Headers
            if (format.length > 1)
            {
                while (startFormat < endFormat && !format[startFormat].contains("%"))
                {
                    System.out.print(format[startFormat++]);
                }
            }
            
            // Process Footers
            if (format.length > 1)
            {
                while (endFormat > startFormat && !format[endFormat].contains("%"))
                {
                    endFormat--;
                }
            }
            
            // Get Last Element Format (last format that is not a footer)
            lastElementFormat = endFormat;
            if (endFormat - startFormat > 0)
            {
                endFormat--;
            }
            
            int index = 0;
            int fIndex = startFormat;
            
            for (; index < array.length - 1;)
            {
                while (!format[fIndex].contains("%"))
                {
                    System.out.print(format[fIndex++]);
                    if (fIndex > endFormat)
                    {
                        fIndex = startFormat;
                    }
                }
                
                System.out.printf(format[fIndex++], array[index++]);
                if (fIndex > endFormat)
                {
                    fIndex = startFormat;
                }
            }
            
            // Format last element
            while (!format[fIndex].contains("%"))
            {
                System.out.print(format[fIndex++]);
                if (fIndex > endFormat)
                {
                    fIndex = startFormat;
                }
            }
            
            if (index < array.length)
            {
                System.out.printf(format[lastElementFormat], array[index++]);
            }
            
            for (fIndex = lastElementFormat + 1; fIndex < format.length; fIndex++)
            {
                System.out.print(format[fIndex]);
            }
        }
    }
    
    
    private static void printHelp(final String message)
    {
        if (message != null && message.length() > 0)
        {
            System.out.println("\n==>>" + message + "<<==\n");
        }
        System.out.println(dnssd.COMMAND_LINE);
    }
}
