package net.posicks.mDNS;

import java.util.concurrent.TimeUnit;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.xbill.DNS.TextParseException;

import junit.framework.TestCase;
import net.posick.mDNS.ServiceName;
import net.posick.mDNS.utils.ExecutionTimer;
import net.posick.mDNS.utils.Misc;

/**
 * Test Cases for the ServiceName
 * 
 * @author Steve Posick
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ServiceNameTest extends TestCase
{
    private static final String DOMAINS[] = 
    {
        "posick.net.",
        "example.com.",
        "local."
    };
    
    private static final String ROOT_SERVICE_NAMES[] = 
    {
        "_ftp._tcp",
        "_ftp._udp",
        "_ftp-data._tcp",
        "_ftp-data._udp",
        "_ftp-data._sctp",
        "_tftp._tcp",
        "_tftp._udp",
        "_ssh._tcp",
        "_telnet._tcp",
        "_http._tcp",
        "_http._udp",
        "_www._tcp",
        "_www._udp",
        "_www-http._tcp",
        "_name._tcp",
        "_name._udp",
        "_nameserver._tcp",
        "_nameserver._udp",
        "_domain._tcp",
        "_whoispp._tcp",
        "_whoispp._udp",
        "_whois++._tcp",
        "_whois++._udp",
        "_sql-net._tcp",
        "_sql-net._udp",
        "_sql*net._tcp",
        "_sql*net._udp",
        "_syncmate._tcp"
    };
    
    private static final String RFC_6763_SUB_SERVICE_NAMES[] = 
    {
        "_printer._sub",
        "_test._sub",
        "_http._sub",
        "_xml._sub",
        "_org.smpte.device.Device_v1.0._sub",       // SMPTE ST2071 Device SRV Name
        "_org.smpte.service.Service_v1.0._sub"      // SMPTE ST2071 Service SRV Name
    };
    
    private static final String RFC_2782_SUB_SERVICE_NAMES[] = 
    {
        "_printer",
        "_ipp._printer",
        "_test._test",
        "_steve._posick._test",
        "_test"
    };
    
    private static final String INSTANCE_NAMES[] = 
    {
        "steveposick",
        "steve-posick",
        "steve posick",
        "steve.posick",
        "steve.posick._steve",
        "Steve's Test",
        "Steve's Test Name",
        "Steve's_Test_Name",
        "%",
        "*"
    };

    private static final int PERFORMANCE_ITERATIONS = 100;
    
    
    /**
     * @param name
     */
    public ServiceNameTest(String name)
    {
        super(name);
    }
    
    
    /* (non-Javadoc)
     * @see junit.framework.TestCase#setUp()
     */
    protected void setUp()
    throws Exception
    {
        super.setUp();
    }
    
    
    /* (non-Javadoc)
     * @see junit.framework.TestCase#tearDown()
     */
    protected void tearDown()
    throws Exception
    {
        super.tearDown();
    }
    
    
    @Test
    public void test1ServiceNames()
    {
        ServiceName name;
        
        for (String domain : DOMAINS)
        {
            for (String root : ROOT_SERVICE_NAMES)
            {
                String fullName = root + "." + domain;
                try
                {
                    name = new ServiceName(fullName);
                    assertEquals(fullName, Misc.unescape(name.toString()));
                } catch (TextParseException e)
                {
                    fail(Misc.throwableToString(e));
                }
            }
        }
    }
    
    
    @Test
    public void test2SubServiceNames()
    {
        ServiceName name;
        
        for (String domain : DOMAINS)
        {
            for (String root : ROOT_SERVICE_NAMES)
            {
                for (String sub : RFC_6763_SUB_SERVICE_NAMES)
                {
                    String subServiceName = sub + "." + root + "." + domain;
                    try
                    {
                        name = new ServiceName(subServiceName);
                        assertEquals(subServiceName, Misc.unescape(name.toString()));
                    } catch (TextParseException e)
                    {
                        fail(Misc.throwableToString(e));
                    }
                }
            }
        }
    }
    
    
    @Test
    public void test3ServiceInstanceNames()
    {
        ServiceName name;
        
        for (String domain : DOMAINS)
        {
            for (String root : ROOT_SERVICE_NAMES)
            {
                for (String instance : INSTANCE_NAMES)
                {
                    String instanceName = instance + "." + root + "." + domain;
                    try
                    {
                        name = new ServiceName(instanceName);
                        assertEquals(instanceName, Misc.unescape(name.toString()));
                    } catch (TextParseException e)
                    {
                        fail(Misc.throwableToString(e));
                    }
                }
            }
        }
    }
    
    
    @Test
    public void test4RFC6763SubServiceInstanceNames()
    {
        ServiceName name;
        
        for (String domain : DOMAINS)
        {
            for (String root : ROOT_SERVICE_NAMES)
            {
                for (String sub : RFC_6763_SUB_SERVICE_NAMES)
                {
                    String subServiceName = sub + "." + root + "." + domain;
                    for (String instance : INSTANCE_NAMES)
                    {
                        String instanceName = instance + "." + subServiceName;
                        try
                        {
                            name = new ServiceName(instanceName);
                            assertEquals(instanceName, Misc.unescape(name.toString()));
                        } catch (TextParseException e)
                        {
                            fail(Misc.throwableToString(e));
                        }
                    }
                }
            }
        }
    }
    
    
    @Test
    public void test4RFC2782SubServiceInstanceNames()
    {
        ServiceName name;
        
        for (String domain : DOMAINS)
        {
            for (String root : ROOT_SERVICE_NAMES)
            {
                for (String sub : RFC_2782_SUB_SERVICE_NAMES)
                {
                    String subServiceName = sub + "." + root + "." + domain;
                    for (String instance : INSTANCE_NAMES)
                    {
                        String instanceName = instance + "." + subServiceName;
                        try
                        {
                            name = new ServiceName(instanceName);
                            assertEquals(instanceName, Misc.unescape(name.toString()));
                        } catch (TextParseException e)
                        {
                            fail(Misc.throwableToString(e));
                        }
                    }
                }
            }
        }
    }
    
    
    @Test
    public void test5Performance()
    {
        @SuppressWarnings("unused")
        ServiceName name;
        int iterations = PERFORMANCE_ITERATIONS;
        double totalTime = 0;
        int count = 0;
        double average = 0;
        
        for (int index = 0; index < iterations; index++ )
        {
            for (String domain : DOMAINS)
            {
                for (String root : ROOT_SERVICE_NAMES)
                {
                    for (String sub : RFC_6763_SUB_SERVICE_NAMES)
                    {
                        String subServiceName = sub + "." + root;
                        for (String instance : INSTANCE_NAMES)
                        {
                            String instanceName = instance + "." + subServiceName + "." + domain;
                            try
                            {
                                ExecutionTimer._start();
                                name = new ServiceName(instanceName);
                                double took = ExecutionTimer._took(TimeUnit.NANOSECONDS);
                                average = (took + average) / (double) 2;
                                totalTime += took;
                                count++;
                            } catch (TextParseException e)
                            {
                                fail(Misc.throwableToString(e));
                            }
                        }
                    }
                }
            }
        }

        System.out.println("Took " + (totalTime / (double) 1000000) + " milliseonds to parse " + count + " service names at " + (average / (double) 1000000) + " milliseconds per name.");
    }
}
