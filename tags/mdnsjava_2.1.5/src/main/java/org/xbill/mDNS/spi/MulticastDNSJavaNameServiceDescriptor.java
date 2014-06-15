// Copyright (c) 2013 Steve Posick (posicks@gmail.com)

package org.xbill.mDNS.spi;

import java.lang.reflect.Proxy;

import sun.net.spi.nameservice.*;

/**
 * The descriptor class for the mdnsjava name service provider.
 *
 * @author Brian Wellington
 * @author Paul Cowan (pwc21@yahoo.com)
 * @author Steve Posick (posicks@gmail.com)
 */

public class MulticastDNSJavaNameServiceDescriptor implements NameServiceDescriptor {

private static NameService nameService;

static {
	ClassLoader loader = NameService.class.getClassLoader();
	nameService = (NameService) Proxy.newProxyInstance(loader,
			new Class[] { NameService.class },
			new MulticastDNSJavaNameService());
}

/**
 * Returns a reference to a mdnsjava name server provider.
 */
public NameService
createNameService() {
	return nameService;
}

public String
getType() {
	return "mdns";
}

public String
getProviderName() {
	return "mdnsjava"; 
}

}
