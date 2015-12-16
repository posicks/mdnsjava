# Multicast DNS (mDNS) & DNS-Based Service Discovery (DNS-SD) in Java

## Introduction
The Multicast DNS (mDNS) [[RFC 6762](http://tools.ietf.org/html/rfc6762)] & DNS-Based Service Discovery (DNS-SD) [[RFC 6763](http://tools.ietf.org/html/rfc6763)] in Java (mdnsjava) project is an extension of dnsjava ([dnsjava.org](http://www.dnsjava.org/)) that implements Multicast DNS (mDNS) [[RFC 6762](http://tools.ietf.org/html/rfc6762)] and DNS-Based Service Discovery (DNS-SD) [[RFC 6763](http://tools.ietf.org/html/rfc6763)] in Java (aka. Bonjour in Java). Unlike other mDNS/DNS-SD implementations mdnsjava does not artificially bind the mDNS and DNS-SD functionality into a single API, instead treating each as a separate feature that is independent from, but related to, the other. This allows clients to use Multicast DNS (mDNS) [RFC 6762](http://tools.ietf.org/html/rfc6762) for name resolution without having to worry about service discovery and simplifies the use of DNS-Base Service Discovery using plain old Unicast DNS (mDNS can be used as a substitiute for DNS for name resolution and DNS can be used as a substitute for mDNS for service discovery).

### Features

* Multicast DNS (mDNS) Responder
* Multicast DNS (mDNS) Querier
* Service Registration/Unregistration
* Browsing for Services
* Browsing for DNS/mDNS Resource Records
* Resolving/Looking up Services synchronously and asynchronously
* Resolving/Looking up DNS/mDNS Resource Records synchronously and asynchronously
* Tested with dnsjava versions 2.1.4, 2.1.5, 2.1.6, and 2.1.7.

### Dependencies
This project depends on:

* [dnsjava.org](http://www.dnsjava.org/) project, version 2.1.5 or higher. (may work with early versions)
* Java SE 1.5 or higher

### Command Line Tool Usage
```
$java -jar mdnsjava.jar dnssd
 
 Command Line:  dnssd <option> [parameters] 

 dnssd -E                         (Enumerate recommended registration domains)
 dnssd -F                             (Enumerate recommended browsing domains)
 dnssd -B        <Type> [<Domain>]             (Browse for services instances)
 dnssd -L <Name> <Type> [<Domain>]                (Look up a service instance)
 dnssd -R <Name> <Type> <Domain> <Port> <Host> [<TXT>...] (Register a service)
 dnssd -Q <FQDN> <rrtype> <rrclass>        (Generic query for any record type)
 dnssd -G v4/v6/v4v6 <Hostname>         (Get address information for hostname)
 dnssd -V           (Get version of currently running daemon / system service)
$
```
### API Usage

Lookup the registered Browse and Registration Domains [RFC 6263 Section 11](http://tools.ietf.org/html/rfc6763#section-11) using the default DNS and mDNS search paths.

```
Lookup lookup = new Lookup(MulticastDNSService.DEFAULT_REGISTRATION_DOMAIN_NAME,
                           MulticastDNSService.REGISTRATION_DOMAIN_NAME,
                           MulticastDNSService.DEFAULT_BROWSE_DOMAIN_NAME,
                           MulticastDNSService.BROWSE_DOMAIN_NAME,
                           MulticastDNSService.LEGACY_BROWSE_DOMAIN_NAME);
                               
Domain[] domains = lookup.lookupDomains();
for (Domain domain : domains)
{
    System.out.println(domain);
}
lookup.close();
```

####<a name="lookup-service"/></a> Lookup (Resolve) Services (one shot synchronous).

```
Lookup lookup = new Resolve("Test._org.smpte.st2071.service:service_v1.0._sub._mdc._tcp.local.");
ServiceInstance[] services = lookup.lookupServices();
for (ServiceInstance service : services)
{
    System.out.println(service);
}
```

Asynchronously browse for registered services. The DNSSDListener receives service registration and removal events as they are received. To locate services that were registered before starting the browse operation us the [Lookup (Resolve) Services](#lookup-service) process described above.

```
String[] serviceTypes = new String[]
{
    "_http._tcp.",              // Web pages
    "_printer._sub._http._tcp", // Printer configuration web pages
    "_org.smpte.st2071.device:device_v1.0._sub._mdc._tcp",  // SMPTE ST2071 Devices
    "_org.smpte.st2071.service:service_v1.0._sub._mdc._tcp"  // SMPTE ST2071 Services
};

Browse browse = new Browse(serviceTypes);
browse.start(new DNSSDListener()
{
    public void serviceDiscovered(Object id, ServiceInstance service)
    {
        System.out.println("Service Discovered - " + service);
    }
                             
    public void serviceRemoved(Object id, ServiceInstance service)
    {
        System.out.println("Service Removed - " + service);
    }
 
    public void handleException(Object id, Exception e)
    {
        System.err.println("Exception: " + e.getMessage());
        e.printStackTrace(System.err);
    }
});
while (true)
{
    Thread.sleep(10);
    if (System.in.read() == 'q')
    {
        break;
    }
}
browse.close();
```

Lookup (Resolve) a Records, (one shot synchronously).

```
Lookup lookup = new Lookup("Test._mdc._tcp.local.", Type.ANY, DClass.IN);
Record[] records = lookup.lookupRecords();
for (Record record : records)
{
    System.out.println(records);
}
```

Lookup (Resolve) a Records asynchronously, (one shot asynchronously).

```
Lookup lookup = new Lookup("Test._mdc._tcp.local.", Type.ANY, DClass.IN);
Record[] records = Lookup. LookupRecordsAsych(new RecordListener()
{
    public void receiveRecord(Object id, Record record)
    {
        System.out.println("Record Found - " + record);
    }
 
    public void handleException(Object id, Exception e)
    {
        System.err.println("Exception: " + e.getMessage());
        e.printStackTrace(System.err);
    }
});
Thread.sleep(1000);
resolve.close();
```

Register and Unregister a Service

```
MulticastDNSService mDNSService = new MulticastDNSService();
ServiceInstance service = new ServiceInstance(serviceName, 0, 0, port, hostname, MulticastDNSService.DEFAULT_SRV_TTL, addresses, txtValues);
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
```

### Workaround for Java IPv6 Issues

Numerous bugs have been reported with Java's support of IPv6. Among them is an issue where the IP header's Time To Live (TTL) value for datagrams does not get set properly. If the IP header's Time To Live (TTL) value is not set to 255, then the Java VM must be started with IPv6 disabled, using the "-Djava.net.preferIPv4Stack=true" VM option. This is true for IPv4 datagrams as well. Disabling IPv6 fixes the TTL issue for IPv4.

For Example:

```
java -Djava.net.preferIPv4Stack=true -jar mdnsjava.jar dnssd -E
```

### SMPTE ST2071 Media & Device Control over IP

This project was originally created for the development of a proofing of concept application for the Society of Motion Picture and Television Engineers (SMPTE) suite of standards on Media & Device Control over IP networks, [SMPTE ST2071](http://standards.smpte.org/search?fulltext=2071&smptes-status=in-force&submit=yes&content-group=smptes). The SMPTE ST2071 suite of standards defines an open standard for the representation of devices and services within an Internet of Things (IoT) and defines extensions to the DNS-SD protocol that allows for the service discovery DNS infrastructure to differ from the DNS infrastructure used by clients for name resolution.