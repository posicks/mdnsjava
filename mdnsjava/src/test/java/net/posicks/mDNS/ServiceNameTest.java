package net.posicks.mDNS;

import static org.junit.Assert.*;

import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.xbill.DNS.TextParseException;

import net.posick.mDNS.ServiceName;
import net.posick.mDNS.utils.ExecutionTimer;
import net.posick.mDNS.utils.Misc;

/**
 * Test Cases for the ServiceName
 * 
 * @author Steve Posick
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ServiceNameTest
{
    private static final String DOMAINS[] = 
    {
        "posick.net.",
        "example.com.",
        "local."
    };
    
    private static final String RFC_6763_TYPES[] = 
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
    
    private static final String RFC_6763_SUB_TYPES[] = 
    {
        "_printer",
        "_test",
        "_http",
        "_xml",
        "_org.smpte.device:Device_v1.0",           // SMPTE ST2071 Device SRV Name
        "_org.smpte.service:Service_v1.0",         // SMPTE ST2071 Service SRV Name
        "_org.smpte.device:Device_v1.0#Fragment",  // SMPTE ST2071 Device SRV Name
        "_org.smpte.service:Service_v1.0#Fragment" // SMPTE ST2071 Service SRV Name
    };
    
    private static final String RFC_2782_TYPES[] = 
    {
        "_printer",
        "_ipp._printer",
        "_test._test",
        "_steve._posick._test",
        "_test"
    };
    
    private static final String INSTANCES[] = 
    {
        "steveposick",
        "steve-posick",
        "steve posick",
        "steve.posick",
        "Steve's Test",
        "Steve's Test Name",
        "Steve's_Test_Name",
        "%",
        "*"
    };

    private static final int PERFORMANCE_ITERATIONS = 1000;
    
    
    @Before
    public void setUp()
    throws Exception
    {
    }
    
    
    @After
    public void tearDown()
    throws Exception
    {
    }
    
    
    @Test
    public void Test_RFC_2782_ServiceNames()
    {
        ServiceName name;
        
        for (String domain : DOMAINS)
        {
            for (String type : RFC_2782_TYPES)
            {
                String fullName = type + "." + domain;
                try
                {
                    name = new ServiceName(fullName);
                    assertEquals(null, Misc.unescape(name.getInstance()));
                    assertEquals(null, Misc.unescape(name.getFullSubType()));
                    assertEquals(null, Misc.unescape(name.getSubType()));
                    assertEquals(type, Misc.unescape(name.getType()));
                    assertEquals(type, Misc.unescape(name.getFullType()));
                    assertEquals(type, Misc.unescape(name.getApplication()));
                    assertEquals(null, Misc.unescape(name.getProtocol()));
                    assertEquals(domain, Misc.unescape(name.getDomain()));
                    assertEquals(type + "." + domain, Misc.unescape(name.getServiceTypeName().toString()));
                    assertEquals(fullName, Misc.unescape(name.toString()));
                } catch (TextParseException e)
                {
                    fail(Misc.throwableToString(e));
                }
            }
        }
    }
    
    
    @Test
    public void Test_RFC_6763_Type_ServiceNames()
    {
        ServiceName name;
        
        for (String domain : DOMAINS)
        {
            for (String type : RFC_6763_TYPES)
            {
                String fullName = type + "." + domain;
                try
                {
                    name = new ServiceName(fullName);
                    assertEquals(null, Misc.unescape(name.getInstance()));
                    assertEquals(null, Misc.unescape(name.getFullSubType()));
                    assertEquals(null, Misc.unescape(name.getSubType()));
                    assertEquals(type, Misc.unescape(name.getType()));
                    assertEquals(type, Misc.unescape(name.getFullType()));
                    assertEquals(type.substring(0, type.indexOf('.')), Misc.unescape(name.getApplication()));
                    assertEquals(type.substring(type.indexOf('.') + 1), Misc.unescape(name.getProtocol()));
                    assertEquals(domain, Misc.unescape(name.getDomain()));
                    assertEquals(type + "." + domain, Misc.unescape(name.getServiceTypeName().toString()));
                    assertEquals(fullName, Misc.unescape(name.toString()));
                } catch (TextParseException e)
                {
                    fail(Misc.throwableToString(e));
                }
            }
        }
    }
    
    
    @Test
    public void Test_RFC_6763_Type_InstanceNames()
    {
        ServiceName name;
        
        for (String domain : DOMAINS)
        {
            for (String type : RFC_6763_TYPES)
            {
                for (String instance : INSTANCES)
                {
                    String fullName =  instance + "." + type + "." + domain;
                    try
                    {
                        name = new ServiceName(fullName);
                        assertEquals(instance, Misc.unescape(name.getInstance()));
                        assertEquals(null, Misc.unescape(name.getFullSubType()));
                        assertEquals(null, Misc.unescape(name.getSubType()));
                        assertEquals(type, Misc.unescape(name.getType()));
                        assertEquals(type, Misc.unescape(name.getFullType()));
                        assertEquals(type.substring(0, type.indexOf('.')), Misc.unescape(name.getApplication()));
                        assertEquals(type.substring(type.indexOf('.') + 1), Misc.unescape(name.getProtocol()));
                        assertEquals(domain, Misc.unescape(name.getDomain()));
                        assertEquals(type + "." + domain, Misc.unescape(name.getServiceTypeName().toString()));
                        assertEquals(fullName, Misc.unescape(name.toString()));
                    } catch (TextParseException e)
                    {
                        fail(Misc.throwableToString(e));
                    }
                }
            }
        }
    }
    
    
    @Test
    public void Test_RFC_6763_SubType_ServiceNames()
    {
        ServiceName name;
        
        for (String domain : DOMAINS)
        {
            for (String type : RFC_6763_TYPES)
            {
                for (String subType : RFC_6763_SUB_TYPES)
                {
                    String fullSubType = subType + "._sub." + type;
                    String fullName =  fullSubType + "." + domain;
                    try
                    {
                        name = new ServiceName(fullName);
                        assertEquals(null, Misc.unescape(name.getInstance()));
                        assertEquals(subType + "._sub", Misc.unescape(name.getFullSubType()));
                        assertEquals(subType, Misc.unescape(name.getSubType()));
                        assertEquals(type, Misc.unescape(name.getType()));
                        assertEquals(fullSubType, Misc.unescape(name.getFullType()));
                        assertEquals(type.substring(0, type.indexOf('.')), Misc.unescape(name.getApplication()));
                        assertEquals(type.substring(type.indexOf('.') + 1), Misc.unescape(name.getProtocol()));
                        assertEquals(domain, Misc.unescape(name.getDomain()));
                        assertEquals(type + "." + domain, Misc.unescape(name.getServiceTypeName().toString()));
                        assertEquals(fullName, Misc.unescape(name.toString()));
                    } catch (TextParseException e)
                    {
                        fail(Misc.throwableToString(e));
                    }
                }
            }
        }
    }
    
    
    @Test
    public void Test_RFC_6763_SubType_InstanceNames()
    {
        ServiceName name;
        
        for (String domain : DOMAINS)
        {
            for (String type : RFC_6763_TYPES)
            {
                for (String subType : RFC_6763_SUB_TYPES)
                {
                    for (String instance : INSTANCES)
                    {
                        String fullSubType = subType + "._sub." + type;
                        String fullName =  instance + "." + fullSubType + "." + domain;
                        try
                        {
                            name = new ServiceName(fullName);
                            assertEquals(instance, Misc.unescape(name.getInstance()));
                            assertEquals(subType + "._sub", Misc.unescape(name.getFullSubType()));
                            assertEquals(subType, Misc.unescape(name.getSubType()));
                            assertEquals(type, Misc.unescape(name.getType()));
                            assertEquals(fullSubType, Misc.unescape(name.getFullType()));
                            assertEquals(type.substring(0, type.indexOf('.')), Misc.unescape(name.getApplication()));
                            assertEquals(type.substring(type.indexOf('.') + 1), Misc.unescape(name.getProtocol()));
                            assertEquals(domain, Misc.unescape(name.getDomain()));
                            assertEquals(type + "." + domain, Misc.unescape(name.getServiceTypeName().toString()));
                            assertEquals(fullName, Misc.unescape(name.toString()));
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
    public void Performance_Test()
    {
        @SuppressWarnings("unused")
        ServiceName name;
        int iterations = PERFORMANCE_ITERATIONS;
        double totalTime = 0;
        int count = 0;
        double average = 0;
        
        for (int index = 0; index < iterations; index++)
        {
            for (String domain : DOMAINS)
            {
                for (String type : RFC_6763_TYPES)
                {
                    for (String subType : RFC_6763_SUB_TYPES)
                    {
                        for (String instance : INSTANCES)
                        {
                            String fullSubType = subType + "._sub." + type;
                            String fullName =  instance + "." + fullSubType + "." + domain;
                            try
                            {
                                ExecutionTimer._start();
                                name = new ServiceName(fullName);
                                double took = ExecutionTimer._took(TimeUnit.NANOSECONDS);
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
        
        average = (totalTime + average) / count;
        System.out.println("Took " + (totalTime / 1000000) + " milliseconds to parse " + count + " service names at " + (average / 1000000) + " milliseconds per name.");
    }
}
