package net.posicks.mDNS;

import static org.junit.Assert.fail;

import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.TextParseException;

import net.posick.mDNS.Browse;
import net.posick.mDNS.DNSSDListener;
import net.posick.mDNS.MulticastDNSService;
import net.posick.mDNS.ServiceInstance;
import net.posick.mDNS.ServiceName;
import net.posick.mDNS.utils.Misc;

/**
 * Test Cases for the Service Registration
 * 
 * @author Steve Posick
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ServiceRegistrationTest
{
    private static final String TYPE = "_test._sub._syncmate._tcp";
    
    private static final String NAME = "Steve Posick's Service." + TYPE;
    
    private static final String DOMAIN = "localhost.";
    
    
    /**
     * @throws java.lang.Exception
     */
    @Before
    public void setUp()
    throws Exception
    {
    }
    
    
    /**
     * @throws java.lang.Exception
     */
    @After
    public void tearDown()
    throws Exception
    {
    }
    
    
    @Test
    public void test1RegisterService()
    {
        MulticastDNSService service = null;
        
        try
        {
            final Name domainName = new Name(DOMAIN);
            final ServiceName serviceName = new ServiceName(NAME, domainName);
            
            service = new MulticastDNSService();
            service.startServiceDiscovery(new Browse(TYPE), new DNSSDListener()
            {
                public void serviceRemoved(Object id, ServiceInstance service)
                {
                    System.out.println("Service Removed " + service.getNiceText());
                }
                
                
                public void serviceDiscovered(Object id, ServiceInstance service)
                {
                    System.out.println("Service Discovered " + service.getNiceText());
                }
                
                
                public void receiveMessage(Object id, Message m)
                {
                    System.out.println("Message Received \n" + m);
                }
                
                
                public void handleException(Object id, Exception e)
                {
                    System.out.println("Exception \n" + e);
                }
            });
            
            System.out.println("Registering Service \"" + NAME + "\" in DOMAIN \"" + DOMAIN + "\".");
            
            ServiceInstance serviceInstance = service.register(new ServiceInstance(serviceName, domainName));
            
            System.out.println("Service \"" + serviceName + "\" registered in DOMAIN \"" + domainName + "\" as \n" + serviceInstance);
            
            try
            {
                System.out.println("Waiting for registration events.");
                Thread.sleep(3000);
            } catch (InterruptedException e)
            {
                System.err.println("Interrupted! - " + e.toString());
            }
            
            System.out.println("Unregistering Service \"" + NAME + "\" in DOMAIN \"" + DOMAIN + "\".");
            
            service.unregister(new ServiceInstance(serviceName, domainName));
            
            try
            {
                System.out.println("Waiting for unregistration events.");
                Thread.sleep(3000);
            } catch (InterruptedException e)
            {
                System.err.println("Interrupted! - " + e.toString());
            }
        } catch (TextParseException e)
        {
            fail(Misc.throwableToString(e));
        } catch (IOException e)
        {
            fail(Misc.throwableToString(e));
        } finally
        {
            Misc.close(service);
        }
    }
    
    
    @Test
    public void test2UnregisterService()
    {
        MulticastDNSService service = null;
        
        try
        {
            service = new MulticastDNSService();
            service.startServiceDiscovery(new Browse(TYPE), new DNSSDListener()
            {
                public void serviceRemoved(Object id, ServiceInstance service)
                {
                    System.out.println("Service Removed " + service.getNiceText());
                }
                
                
                public void serviceDiscovered(Object id, ServiceInstance service)
                {
                    System.out.println("Service Discovered " + service.getNiceText());
                }
                
                
                public void receiveMessage(Object id, Message m)
                {
                    System.out.println("Message Received \n" + m);
                }
                
                
                public void handleException(Object id, Exception e)
                {
                    System.out.println("Exception \n" + e);
                }
            });

            final Name domainName = new Name(DOMAIN);
            final ServiceName serviceName = new ServiceName(NAME, domainName);
            
            System.out.println("Unregistering Service \"" + NAME + "\" in DOMAIN \"" + DOMAIN + "\".");
            
            service.unregister(new ServiceInstance(serviceName, domainName));
            
            try
            {
                System.out.println("Waiting for unregistration events.");
                Thread.sleep(3000);
            } catch (InterruptedException e)
            {
                System.err.println("Interrupted! - " + e.toString());
            }
        } catch (TextParseException e)
        {
            fail(Misc.throwableToString(e));
        } catch (IOException e)
        {
            fail(Misc.throwableToString(e));
        } finally
        {
            Misc.close(service);
        }
    }
}
