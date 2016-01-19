package net.posick.mDNS.net;

public interface PacketListener
{
    void packetReceived(Packet packet);
}