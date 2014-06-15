package org.xbill.mDNS;

import org.xbill.DNS.Name;

public interface Constants
{
    public static final long DEFAULT_RR_WITHOUT_HOST_TTL = 4500; // 75 Minutes
    
    public static final long DEFAULT_RR_WITH_HOST_TTL = 120; // 2 Minutes

    public static final long DEFAULT_OTHER_TTL = DEFAULT_RR_WITHOUT_HOST_TTL;

    public static final long DEFAULT_SRV_TTL = DEFAULT_RR_WITH_HOST_TTL;

    public static final long DEFAULT_TXT_TTL = DEFAULT_RR_WITHOUT_HOST_TTL;

    public static final long DEFAULT_A_TTL = DEFAULT_RR_WITH_HOST_TTL;

    public static final long DEFAULT_PTR_TTL = DEFAULT_RR_WITHOUT_HOST_TTL;
    
    public static final String LINK_LOCAL_DOMAIN = "local.";
    
    public static final Name[] ALL_MULTICAST_DNS_DOMAINS = new Name[] 
    {
        Name.fromConstantString(LINK_LOCAL_DOMAIN),
        Name.fromConstantString("254.169.in-addr.arpa."),
        Name.fromConstantString("8.e.f.ip6.arpa."),
        Name.fromConstantString("9.e.f.ip6.arpa."),
        Name.fromConstantString("a.e.f.ip6.arpa."),
        Name.fromConstantString("b.e.f.ip6.arpa.")
    };

    /** The multicast domains.  These domains must be sent to the IPv4 or IPv6 mDNS address */
    public static final Name[] IPv4_MULTICAST_DOMAINS = new Name[] 
    {
        Name.fromConstantString(LINK_LOCAL_DOMAIN),
        Name.fromConstantString("254.169.in-addr.arpa."),
    };
    
    public static final Name[] IPv6_MULTICAST_DOMAINS = new Name[] 
    {
        Name.fromConstantString(LINK_LOCAL_DOMAIN),
        Name.fromConstantString("8.e.f.ip6.arpa."),
        Name.fromConstantString("9.e.f.ip6.arpa."),
        Name.fromConstantString("a.e.f.ip6.arpa."),
        Name.fromConstantString("b.e.f.ip6.arpa.")
    };
    
    /** The default port to send queries to */
    public static final int DEFAULT_PORT = 5353;

    /** The default address to send IPv4 queries to */
    public static final String DEFAULT_IPv4_ADDRESS = "224.0.0.251";
    
    /** The default address to send IPv6 queries to */
    public static final String DEFAULT_IPv6_ADDRESS = "FF02::FB";
    
    /** The domain name used by DNS-Based Service Discovery (DNS-SD) [RFC 6763] to list default browse domains */
    public static final String DEFAULT_BROWSE_DOMAIN_NAME = "db._dns-sd._udp";

    /** The domain name used by DNS-Based Service Discovery (DNS-SD) [RFC 6763] to list browse domains */
    public static final String BROWSE_DOMAIN_NAME = "b._dns-sd._udp";

    /** The domain name used by DNS-Based Service Discovery (DNS-SD) [RFC 6763] to list legacy browse domains */
    public static final String LEGACY_BROWSE_DOMAIN_NAME = "lb._dns-sd._udp";
    
    /** The domain name used by DNS-Based Service Discovery (DNS-SD) [RFC 6763] to list default registration domains */
    public static final String DEFAULT_REGISTRATION_DOMAIN_NAME = "dr._dns-sd._udp";

    /** The domain name used by DNS-Based Service Discovery (DNS-SD) [RFC 6763] to list registration domains */
    public static final String REGISTRATION_DOMAIN_NAME = "r._dns-sd._udp";

    /** The domain names used by DNS-Based Service Discovery (DNS-SD) [RFC 6763] to iterate registered service types */
    public static final String SERVICES_NAME = "_services._dns-sd._udp";

    /** The Cache Flush flag used in Multicast DNS (mDNS) [RFC 6762] query responses */
    public static final int CACHE_FLUSH = 0x8000;
}
