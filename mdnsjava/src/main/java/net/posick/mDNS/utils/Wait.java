package net.posick.mDNS.utils;

import org.xbill.DNS.Options;

import net.posick.mDNS.Querier;

/**
 * The Wait utility provides default wait logic, such as waiting for responses.
 * 
 * @author Steve Posick
 */
@SuppressWarnings("rawtypes")
public class Wait
{
    public static final long waitTill()
    {
        int wait = Options.intValue("mdns_resolve_wait");
        return System.currentTimeMillis() + (wait > 0 ? wait : Querier.DEFAULT_RESPONSE_WAIT_TIME);
    }
    
    
    public static final void forResponse(Iterable monitor)
    {
        synchronized (monitor)
        {
            long waitTill = waitTill();
            while (!monitor.iterator().hasNext() && System.currentTimeMillis() < waitTill)
            {
                try
                {
                    monitor.wait(waitTill - System.currentTimeMillis());
                } catch (InterruptedException e)
                {
                    // ignore
                }
            }
        }
    }
    
    
    public static final void forResponse(Object monitor)
    {
        synchronized (monitor)
        {
            long waitTill = waitTill();
            while (System.currentTimeMillis() < waitTill)
            {
                try
                {
                    monitor.wait(waitTill - System.currentTimeMillis());
                } catch (InterruptedException e)
                {
                    // ignore
                }
            }
        }
    }
}
