# Multicast DNS (mDNS) & DNS-Based Service Discovery (DNS-SD) in Java

## <a name="introduction"></a> Important
At this time please use version 2.1.5 or the 2.1.6 snapsots. The master branch has bugs that I am addressing. Once operational the master branch will be published as a new version and added to the Maven Repository. Thank you for your patience while I fix the previously reported bugs and add new functionality, such as support for all RFC 2782 SRV domain names.

## <a name="introduction"></a> Introduction
The Multicast DNS (mDNS) [[RFC 6762](http://tools.ietf.org/html/rfc6762)] & DNS-Based Service Discovery (DNS-SD) [[RFC 6763](http://tools.ietf.org/html/rfc6763)] in Java (mdnsjava) project is an extension of dnsjava ([dnsjava.org](http://www.dnsjava.org/)) that implements Multicast DNS (mDNS) [[RFC 6762](http://tools.ietf.org/html/rfc6762)] and DNS-Based Service Discovery (DNS-SD) [[RFC 6763](http://tools.ietf.org/html/rfc6763)] in Java (aka. Bonjour in Java). Unlike other mDNS/DNS-SD implementations mdnsjava does not artificially bind the mDNS and DNS-SD functionality into a single API, instead treating each as a separate feature that is independent from, but related to, the other. This allows clients to use Multicast DNS (mDNS) [RFC 6762](http://tools.ietf.org/html/rfc6762) for name resolution without having to worry about service discovery and simplifies the use of DNS-Base Service Discovery using plain old Unicast DNS (mDNS can be used as a substitiute for DNS for name resolution and DNS can be used as a substitute for mDNS for service discovery).

## <a name="features"></a> Features

* Multicast DNS (mDNS) Responder
* Multicast DNS (mDNS) Querier
* Service Registration/Unregistration
* Browsing for Services
* Browsing for DNS/mDNS Resource Records
* Resolving/Looking up Services synchronously and asynchronously
* Resolving/Looking up DNS/mDNS Resource Records synchronously and asynchronously
* Tested with dnsjava versions 2.1.4, 2.1.5, 2.1.6 and 2.1.7.

## <a name="changelog"></a> Changelog

### Version 2.2.0 Changes
* **Changed Java Package from org.xbill.mDNS -> net.posick.mdns**. mdnsjava uses dnsjava for DNS functionality, but is not part of the dnsjava project.
* **Full Maven Support**. Maven is now the build system for mdnsjava.

## <a name="dependencies"></a> Dependencies
This project depends on:

* [dnsjava.org](http://www.dnsjava.org/) project, version 2.1.5 or higher. (may work with early versions)
* Java SE 1.5 or higher

## <a name="command_line_tools"></a> Command Line Tool Usage
```
$java -jar mdnsjava.jar
          or
$java -cp mdnsjava.jar:dnsjava.jar dnssd
 
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
## <a name="api-usage"></a> API Usage

The following are examples of mdnsjava API usage.

All domain names in mdnsjava are considered to be relative to the domain search path unless they end with a period `.`.

For example:

* The domain name `posick.net.` will be treated as an absolute domain name, looking up records in the posick.net domain. 
* The domain name `posick.net` will be treated as a relative domain name and will be prepended to each domain listed in the DNS search path, ex, `posick.net.local.`, `posick.net.posick.net.`, etc...

### <a name="lookup_browse_registration_domains"></a> Lookup the registered Browse and Registration Domains [RFC 6263 Section 11](http://tools.ietf.org/html/rfc6763#section-11) using the default DNS and mDNS search paths.

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

### <a name="lookup-service"/></a> Lookup (Resolve) Services (one shot synchronous).

```
Lookup lookup = new Resolve("Test._org.smpte.st2071.service:service_v1.0._sub._mdc._tcp.local.");
ServiceInstance[] services = lookup.lookupServices();
for (ServiceInstance service : services)
{
    System.out.println(service);
}
```

### <a name="async_browse"></a> Asynchronously Browse for Registered Services.
The DNSSDListener receives service registration and removal events as they are received. To locate services that were registered before starting the browse operation us the [Lookup (Resolve) Services](#lookup-service) process described above.

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

### <a name="lookup_records"></a> Lookup (Resolve) a Records Synchronously.

```
Lookup lookup = new Lookup("Test._mdc._tcp.local.", Type.ANY, DClass.IN);
Record[] records = lookup.lookupRecords();
for (Record record : records)
{
    System.out.println(records);
}
```

### <a name="lookup_records_async"></a> Lookup (Resolve) a Records Asynchronously.

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

### <a name="register_unregister_services"></a> Registering and Unregistering a Services.

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

## Additional Information

### <a name="ipv6_workaround"></a> Workaround for Java IPv6 Issues

Numerous bugs have been reported with Java's support of IPv6. Among them is an issue where the IP header's Time To Live (TTL) value for datagrams does not get set properly. If the IP header's Time To Live (TTL) value is not set to 255, then the Java VM must be started with IPv6 disabled, using the "-Djava.net.preferIPv4Stack=true" VM option. This is true for IPv4 datagrams as well. Disabling IPv6 fixes the TTL issue for IPv4.

For Example:

```
java -Djava.net.preferIPv4Stack=true -jar mdnsjava.jar dnssd -E
```

## <a name="DNS-SD"></a> DNS-based Service Dicovery (DNS-SD)

DNS-based Service Discovery is an efficient service discovery protocol developed by Apple, originally as Bonjour. DNS-SD is part of the [Zeroconf](http://www.zeroconf.org/) specification and sclaes from the link-local network up to the Internet. Link-local network support is provided via mDNS using the `local.` domain name, while scaling to networks ouside than the link-local network is achieved thru Unicast DNS and regular domain names, for example `posick.net.`.

## <a name="st2071"></a> SMPTE ST2071 Media & Device Control over IP

This project was originally created for the development of a proof of concept application for the Society of Motion Picture and Television Engineers (SMPTE) suite of standards on Media & Device Control over IP networks, [SMPTE ST2071](http://ieeexplore.ieee.org/xpl/articleDetails.jsp?arnumber=7290627&filter=AND(p_Publication_Number:7290625)). The SMPTE ST2071 suite of standards defines an open standard for the representation of devices and services within an Internet of Things (IoT) and defines extensions to the DNS-SD protocol that allows for the service discovery DNS infrastructure to be separate from the DNS infrastructure used for name resolution.

### <a name="st2071_register_capabilty"></a> Registering and Unregistering ST2071 Capabilities

The SMPTE ST2071 standard defines [Capabilities](http://mdc.posick.net) as uniquely identified interfaces that describe an atomic behavior or single feature. The interface identities are made globally unique by assigning each to a namespace, that namespace corresponding to a registered DNS domain name. The DNS domain name's registrant manages the names within the namespace, guaranteeing the uniqueness of each namespace/interface name combination (essencially the model used to guarantee the uniqueness of DNS hostnames).

The unique interface identities should be represented as URIs with a procedure defined that facilitates the translation of the URI to a DNS domain name. Within the SMPTE ST2071 standard these identifiers are called *Uniform Capability Names* (UCNs) and are URNs in the format:

```
'urn:smpte:ucn:' ${namespace} ':' ${interface_name} '_v' ${version}
```

UCNs may also have a URI fragment `'#' ${feature name}` appended to the end, which allows for monolithic legacy interfaces to be divided into Capablities/features.

Devices and Services make themselves and may make the features or the subset of the features they support discoverable by registering the DNS domain names derived from their unique identifiers. 

The following is the format of the SMPTE ST2071 DNS-SD service name. The variables contained within match the values specified within the UCN format described above or any URI identity that contains their equivalent.

```
'_' ${namespace} ':' ${interface_name} '_v' ${version} '_sub._mdc._tcp.' ${domain}
```

<<<<<<< HEAD
where `${domain}` is the DNS or mDNS domain name of the network where the implementation is deployed, e.g, `local.` or `posick.net.`.

For example: 
The interface identifiers `urn:smpte:org.example:helloworld_v1.0` or `feature://example.org/HelloWord/v1` would be translated into the DNS-SD service name `_org.example:helloworld_v1._sub._mdc._tcp.local.` for mDNS applications or `_org.example:helloworld_v1._sub._mdc._tcp.posick.net.` for DNS applications.

It is important to note that `_mdc._tcp` is the DNS-SD service type for SMPTE ST2071 compliant services and that each Device, Service and Capability/feature that is registered for discovery via DNS-SD should also contain a service instance registered to a DNS PTR Resource Record named `'_mdc._tcp.' ${domain}`.

For example: The Hello World example above would result in 2 registered DNS-SD service names for mDNS discovery and 2 registered DNS-SD service names for Unicast DNS discovery. This allows for the service to be discoverable in a Zeroconf fashion on the Link-local network, on a managed network or over the Internet if the domain name is a publically registered domain name.

```
_org.example:helloworld_v1._sub._mdc._tcp.local.
_mdc._tcp.local.
_org.example:helloworld_v1._sub._mdc._tcp.posick.net.
_mdc._tcp.posick.net.
```

The using the `_mdc._tcp. ${domain}` service name registration allows for the discovery of all Devices, Services and Cpabilities/features that are made discoverable over the network.

The example below illustrates the BIND 9 configuration for the examples discussed above for Unicast DNS applications of DNS-SD. It is a common misconception that DNS-SD only applies to mDNS, this falshood originates from limitatons in the Bonjour specification and the restrictictions of many DNS-SD/mDNS implementations. mdnsjava does not have these limitations and therefore can scale from the link-local network up to the Internet with a single discovery protocol that has no need for "gateways." Thus, allowing implementers to make services discoverable within their local network and Internet domain with minimal effort and NO modification to the service implementation.

```
$TTL    86400
$ORIGIN posick.net.
@    1D IN SOA    posick.net. root.posick.net. (
     71        ; serial
     3H        ; refresh
     5M        ; retry
     5M        ; expiry
     1M )      ; minimum

            1D IN NS    @
@           1D IN A 192.168.0.1

; Hosts
server      1D IN A 192.168.0.21

; DNS-SD SRV and TXT records containing all of the information needed to construct a URL to the service endpoint
; Host Device/Server
Server._org.smpte.st2071.device:device_v1.0._sub._mdc._tcp               1M IN SRV 10 10 8080 server
    TXT ("txtvers=1" "rn=urn:smpte:udn:net.posick:server" "proto=mdcp" "path=/")
    
; Legacy Service Interface
LegacyInstance._org.smpte.st2071.service:service_v1.0._sub._mdc._tcp     1M IN SRV 10 10 8080 server
    TXT ("txtvers=1" "rn=urn:smpte:usn:net.posick:type=instance;Legacy" "proto=mdcp" "path=/Service/Legacy")
    
; Legacy Main Interface
Legacy._net.posick:legacy_v1.0._sub._mdc._tcp                            1M IN SRV 10 10 8080 server
    TXT ("txtvers=1" "rn=urn:smpte:usn:net.posick:type=instance;Legacy" "proto=mdcp" "path=/Legacy")

; Legacy Feature - Feature1 Interface
LegacyFeature1._net.posick:legacy_v1.0._sub._mdc._tcp                    1M IN SRV 10 10 8080 server
    TXT ("txtvers=1" "rn=urn:smpte:usn:net.posick:type=instance;Legacy" "proto=mdcp" "path=/Legacy/Feature1")
    
; HelloWorld Service Interface
HelloWorldInstance._org.smpte.st2071.service:service_v1.0._sub._mdc._tcp 1M IN SRV 10 10 8080 server
    TXT ("txtvers=1" "rn=urn:smpte:usn:net.posick:type=instance;HelloWorld" "proto=mdcp" "path=/Service/HelloWorld")
    
; HelloWorld Feature Interface
HelloWorld._org.example:helloworld_v1.0._sub._mdc._tcp                   1M IN SRV 10 10 8080 server
    TXT ("txtvers=1" "rn=urn:smpte:usn:net.posick:type=instance;HelloWorld" "proto=mdcp" "path=/HelloWorld")

; SMPTE ST2071 Device and Service Base Discovery PTR RRs
_mdc._tcp   1M IN PTR Server._org.smpte.st2071.device:device_v1.0._sub._mdc._tcp
_mdc._tcp   1M IN PTR LegacyInstance._org.smpte.st2071.service:service_v1.0._sub._mdc._tcp
_mdc._tcp   1M IN PTR Legacy._net.posick:legacy_v1.0._sub._mdc._tcp
_mdc._tcp   1M IN PTR LegacyFeature1._net.posick:legacy_v1.0._sub._mdc._tcp
_mdc._tcp   1M IN PTR HelloWorldInstance._org.smpte.st2071.service:service_v1.0._sub._mdc._tcp
_mdc._tcp   1M IN PTR HelloWorld._org.example:helloworld_v1.0._sub._mdc._tcp

; Pointers to the Base Device and Service interfaces
_org.smpte.st2071.device:device_v1.0._sub._mdc._tcp   1M IN PTR Server._org.smpte.st2071.device:device_v1.0._sub._mdc._tcp
_org.smpte.st2071.service:service_v1.0._sub._mdc._tcp 1M IN PTR LegacyInstance._org.smpte.st2071.service:service_v1.0._sub._mdc._tcp
_org.smpte.st2071.service:service_v1.0._sub._mdc._tcp 1M IN PTR HelloWorldInstance._org.smpte.st2071.service:service_v1.0._sub._mdc._tcp

; Pointers to the Capabilities/features
_net.posick:legacy_v1.0._sub._mdc._tcp                1M IN PTR Legacy._net.posick:legacy_v1.0._sub._mdc._tcp
_net.posick:legacy_v1.0#Feature1._sub._mdc._tcp       1M IN PTR LegacyFeature1._net.posick:legacy_v1.0._sub._mdc._tcp

_org.example:helloworld_v1.0._sub._mdc._tcp           1M IN PTR HelloWorld._org.example:helloworld_v1.0._sub._mdc._tcp
```

=======
The registration of all services using the `_mdc._tcp` name facilitates the search of all registered ST2071 devices, services and Capability enpoints.
>>>>>>> branch 'master' of https://github.com/posicks/mdnsjava.git
