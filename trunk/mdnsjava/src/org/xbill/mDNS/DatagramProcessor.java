package org.xbill.mDNS;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

import org.xbill.DNS.Options;

public class DatagramProcessor extends NetworkProcessor
{
    // The default UDP datagram payload size
    protected int maxPayloadSize = 512;

    protected boolean isMulticast = false;
    
    protected DatagramSocket socket;
    
    
    public DatagramProcessor(InetAddress ifaceAddress, InetAddress address, int port, PacketListener listener)
    throws IOException
    {
        super(ifaceAddress, address, port, listener);
        
        if (address != null)
        {
            isMulticast = address.isMulticastAddress();
        }
        
        NetworkInterface netIface = null;
        if (isMulticast)
        {
            MulticastSocket socket = new MulticastSocket(port);
            
            // Set the IP TTL to 255, per the mDNS specification [RFC 6762].
            socket.setLoopbackMode(true);
            socket.setReuseAddress(true);
            socket.setTimeToLive(255);
            
            socket.setInterface(ifaceAddress);
            
            socket.joinGroup(address);
            
            this.socket = socket;
        } else
        {
            socket = new DatagramSocket(new InetSocketAddress(ifaceAddress, port));
        }
        
        netIface = NetworkInterface.getByInetAddress(ifaceAddress);
        
        // Determine maximum mDNS Payload size
        if (netIface == null)
        {
            netIface = NetworkInterface.getByInetAddress(socket.getLocalAddress());
            if (netIface == null)
            {
                InetAddress addr = socket.getInetAddress();
                if (addr != null)
                {
                    netIface = NetworkInterface.getByInetAddress(addr);
                }
            }
        }
        
        if (netIface != null)
        {
            try
            {
                mtu = netIface.getMTU();
            } catch (SocketException e)
            {
                netIface = null;
                System.err.println("Error getting MTU from Network Interface " + netIface + ".");
            }
        }
        
        if (netIface == null)
        {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            int smallestMtu = DEFAULT_MTU;
            while (ifaces.hasMoreElements())
            {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isLoopback() && !iface.isVirtual() && iface.isUp())
                {
                    int mtu = iface.getMTU();
                    if (mtu < smallestMtu)
                    {
                        smallestMtu = mtu;
                    }
                }
            }
            mtu = smallestMtu;
        }
        
        maxPayloadSize = mtu - 40 /* IPv6 Header Size */ - 8 /* UDP Header */;
    }
    
    
    public void _send(byte[] data)
    throws IOException
    {
        if (exit)
        {
            return;
        }
        
        DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
        
        try
        {
            if (isMulticast)
            {
                // Set the IP TTL to 255, per the mDNS specification [RFC 6762].
                ((MulticastSocket) socket).setTimeToLive(255);
            }
            socket.send(packet);
        } catch (IOException e)
        {
            if (Options.check("mdns_verbose"))
            {
                System.err.println("Error sending datagram to \"" + packet.getSocketAddress() + "\".");
                e.printStackTrace(System.err);
            }
            
            if ("No route to host".equalsIgnoreCase(e.getMessage()))
            {
                close();
            }
            
            IOException ioe = new IOException("Exception \"" + e.getMessage() + "\" occured while sending datagram to \"" + packet.getSocketAddress() + "\".", e);
            ioe.setStackTrace(e.getStackTrace());
            throw ioe;
        }
    }
    
    
    public void run()
    {
        byte[] buffer = new byte[this.mtu];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        while (!exit)
        {
            try
            {
                socket.receive(datagram);
                final Packet packet = new Packet(datagram);
                threadPool.execute(new Runnable()
                {
                    public void run()
                    {
                        listener.packetReceived(DatagramProcessor.this, packet);
                    }
                });
            } catch (SecurityException e)
            {
                if (Options.check("mdns_verbose"))
                {
                    System.err.println("Security issue receiving data from \"" + address + "\" - " + e.getMessage());
                    e.printStackTrace(System.err);
                }
            } catch (Exception e)
            {
                if (!(socket.isClosed() && exit))
                {
                    if (Options.check("mdns_verbose"))
                    {
                        System.err.println("Error receiving data from \"" + address + "\" - " + e.getMessage());
                        e.printStackTrace(System.err);
                    }
                }
            }
        }
    }
    
    
    protected void finalize()
    throws Throwable
    {
        close();
        super.finalize();
    }

    
    public void close()
    throws IOException
    {
        exit = true;
        
        if (isMulticast)
        {
            try
            {
                ((MulticastSocket) socket).leaveGroup(address);
            } catch (SecurityException e)
            {
                if (Options.check("mdns_verbose"))
                {
                    System.err.println("Security issue leaving Multicast Group \"" + address.getAddress() + "\" - " + e.getMessage());
                    e.printStackTrace(System.err);
                }
            } catch (Exception e)
            {
                if (Options.check("mdns_verbose"))
                {
                    System.err.println("Error leaving Multicast Group \"" + address.getAddress() + "\" - " + e.getMessage());
                    e.printStackTrace(System.err);
                }
            }
        }
        
        socket.close();
    }
    
    
    public boolean isMulticast()
    {
        return isMulticast;
    }


    public void setAddress(InetAddress address)
    {
        super.setAddress(address);
        this.isMulticast = address.isMulticastAddress();
    }
    
    
    public int getMaxPayloadSize()
    {
        return maxPayloadSize;
    }
}
