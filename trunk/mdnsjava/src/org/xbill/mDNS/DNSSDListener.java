package org.xbill.mDNS;

public interface DNSSDListener
{
    public void serviceDiscovered(Object id, ServiceInstance service);
    
    
    public void serviceRemoved(Object id, ServiceInstance service);
    
    
    public void handleException(Object id, Exception e);
}
