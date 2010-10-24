/*
 * SIP Communicator, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package net.java.sip.communicator.impl.protocol.jabber;

import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.jinglesdp.JingleUtils;
import net.java.sip.communicator.service.neomedia.MediaStreamTarget;
import net.java.sip.communicator.service.neomedia.MediaType;
import net.java.sip.communicator.service.neomedia.StreamConnector;
import net.java.sip.communicator.service.protocol.OperationFailedException;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smackx.ServiceDiscoveryManager;
import org.xmpp.jnodes.smack.JingleChannelIQ;
import org.xmpp.jnodes.smack.SmackServiceNode;
import org.xmpp.jnodes.smack.TrackerEntry;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link net.java.sip.communicator.impl.protocol.jabber.TransportManagerJabberImpl} implementation that would only gather a
 * single candidate pair (i.e. RTP and RTCP).
 *
 * @author Emil Ivov
 * @author Lyubomir Marinov
 */
public class RawUdpJNTransportManager
        extends TransportManagerJabberImpl {

    private static ConcurrentHashMap<XMPPConnection, SmackServiceNode> services = new ConcurrentHashMap<XMPPConnection, SmackServiceNode>();

    /**
     * The list of <tt>ContentPacketExtension</tt>s which represents the local
     * counterpart of the negotiation between the local and the remote peers.
     */
    private List<ContentPacketExtension> local;

    /**
     * The HashMap of <tt>JingleChannelIQ</tt>s which represents the local
     * counterpart of the negotiation between the local and the remote peers.
     */
    private ConcurrentHashMap<MediaType, JingleChannelIQ> channels = new ConcurrentHashMap<MediaType, JingleChannelIQ>();


    /**
     * The collection of <tt>ContentPacketExtension</tt>s which represents the
     * remote counterpart of the negotiation between the local and the remote
     * peers.
     */
    private Iterable<ContentPacketExtension> remote;

    /**
     * Creates a new instance of this transport manager, binding it to the
     * specified peer.
     *
     * @param callPeer the {@link net.java.sip.communicator.service.protocol.CallPeer} whose traffic we will be taking
     *                 care of.
     */
    public RawUdpJNTransportManager(CallPeerJabberImpl callPeer) {
        super(callPeer);
    }

    /**
     * Implements {@link net.java.sip.communicator.impl.protocol.jabber.TransportManagerJabberImpl#getStreamTarget(net.java.sip.communicator.service.neomedia.MediaType)}.
     * Gets the <tt>MediaStreamTarget</tt> to be used as the <tt>target</tt> of
     * the <tt>MediaStream</tt> with a specific <tt>MediaType</tt>.
     *
     * @param mediaType the <tt>MediaType</tt> of the <tt>MediaStream</tt> which
     *                  is to have its <tt>target</tt> set to the returned
     *                  <tt>MediaStreamTarget</tt>
     * @return the <tt>MediaStreamTarget</tt> to be used as the <tt>target</tt>
     *         of the <tt>MediaStream</tt> with the specified <tt>MediaType</tt>
     * @see net.java.sip.communicator.impl.protocol.jabber.TransportManagerJabberImpl#getStreamTarget(net.java.sip.communicator.service.neomedia.MediaType)
     */
    public MediaStreamTarget getStreamTarget(MediaType mediaType) {
        MediaStreamTarget streamTarget = null;

        if (remote != null) {
            for (ContentPacketExtension content : remote) {
                System.out.println("Target for Content: " + content.getName());
                RtpDescriptionPacketExtension rtpDescription
                        = content.getFirstChildOfType(
                        RtpDescriptionPacketExtension.class);
                MediaType contentMediaType
                        = MediaType.parseString(rtpDescription.getMedia());

                System.out.println("Target of: " + contentMediaType.toString());

                if (mediaType.equals(contentMediaType)) {
                    if (!channels.containsKey(mediaType)) {
                        streamTarget = JingleUtils.extractDefaultTarget(content);
                    } else {
                        final JingleChannelIQ ciq = channels.get(mediaType);
                        streamTarget = new MediaStreamTarget(new InetSocketAddress(ciq.getHost(), ciq.getLocalport()), new InetSocketAddress(ciq.getHost(), ciq.getLocalport() + 1));
                    }
                    break;
                }
            }
        }
        return streamTarget;
    }

    /**
     * Implements {@link net.java.sip.communicator.impl.protocol.jabber.TransportManagerJabberImpl#getXmlNamespace()}. Gets the
     * XML namespace of the Jingle transport implemented by this
     * <tt>TransportManagerJabberImpl</tt>.
     *
     * @return the XML namespace of the Jingle transport implemented by this
     *         <tt>TransportManagerJabberImpl</tt>
     * @see net.java.sip.communicator.impl.protocol.jabber.TransportManagerJabberImpl#getXmlNamespace()
     */
    public String getXmlNamespace() {
        return ProtocolProviderServiceJabberImpl.URN_XMPP_JINGLE_RAW_UDP_0;
    }

    /**
     * Starts transport candidate harvest. This method should complete rapidly
     * and, in case of lengthy procedures like STUN/TURN/UPnP candidate harvests
     * are necessary, they should be executed in a separate thread. Candidate
     * harvest would then need to be concluded in the {@link #wrapupCandidateHarvest()}
     * method which would be called once we absolutely need the candidates.
     *
     * @param theirOffer a media description offer that we've received from the
     *                   remote party and that we should use in case we need to know what
     *                   transports our peer is using.
     * @param ourAnswer  the content descriptions that we should be adding our
     *                   transport lists to (although not necessarily in this very instance).
     * @throws net.java.sip.communicator.service.protocol.OperationFailedException
     *          if we fail to allocate a port number.
     */
    public void startCandidateHarvest(List<ContentPacketExtension> theirOffer,
                                      List<ContentPacketExtension> ourAnswer,
                                      TransportInfoSender transportInfoSender)
            throws OperationFailedException {

        this.remote = theirOffer;

        for (ContentPacketExtension content : theirOffer) {
            RtpDescriptionPacketExtension rtpDesc
                    = content.getFirstChildOfType(
                    RtpDescriptionPacketExtension.class);

            IceUdpTransportPacketExtension iceExtension = content.getFirstChildOfType(IceUdpTransportPacketExtension.class);
            if (iceExtension == null) {
                iceExtension = content.getFirstChildOfType(RawUdpTransportPacketExtension.class);
            }

            CandidatePacketExtension candidate = null;

            if (iceExtension != null) {
                candidate = iceExtension.getFirstChildOfType(CandidatePacketExtension.class);
            }

            RawUdpTransportPacketExtension ourTransport
                    = createTransport(rtpDesc, (candidate != null && !CandidateType.relay.equals(candidate.getType())));

            //now add our transport to our answer
            ContentPacketExtension cpExt
                    = findContentByName(ourAnswer, content.getName());

            //it might be that we decided not to reply to this content
            if (cpExt == null) {
                continue;
            }

            cpExt.addChildExtension(ourTransport);
        }

        this.local = ourAnswer;
    }

    /**
     * Starts transport candidate harvest. This method should complete rapidly
     * and, in case of lengthy procedures like STUN/TURN/UPnP candidate harvests
     * are necessary, they should be executed in a separate thread. Candidate
     * harvest would then need to be concluded in the {@link #wrapupCandidateHarvest()}
     * method which would be called once we absolutely need the candidates.
     *
     * @param ourOffer the content list that should tell us how many stream
     *                 connectors we actually need.
     * @throws net.java.sip.communicator.service.protocol.OperationFailedException
     *          in case we fail allocating ports
     */
    public void startCandidateHarvest(List<ContentPacketExtension> ourOffer)
            throws OperationFailedException {
        for (ContentPacketExtension content : ourOffer) {
            RtpDescriptionPacketExtension rtpDesc
                    = content.getFirstChildOfType(
                    RtpDescriptionPacketExtension.class);

            RawUdpTransportPacketExtension ourTransport
                    = createTransport(rtpDesc, true);

            //now add our transport to our offer
            ContentPacketExtension cpExt
                    = findContentByName(ourOffer, content.getName());

            cpExt.addChildExtension(ourTransport);
        }

        this.local = ourOffer;
    }

    /**
     * Creates a raw UDP transport element according to the specified stream
     * <tt>connector</tt>.
     *
     * @param rtpDesc the connector that we'd like to describe within the
     *                transport element.
     * @return a {@link net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.RawUdpTransportPacketExtension} containing the RTP and
     *         RTCP candidates of the specified {@link net.java.sip.communicator.service.neomedia.StreamConnector}.
     */
    private RawUdpTransportPacketExtension createTransport(
            RtpDescriptionPacketExtension rtpDesc, final boolean requestRelay) throws OperationFailedException {
        RawUdpTransportPacketExtension ourTransport
                = new RawUdpTransportPacketExtension();

        String ip;
        int port;

        CandidateType ctype = CandidateType.host;
        XMPPConnection conn;
        JingleChannelIQ ciq = null;

        System.out.println("Trying To Use Jingle Nodes.");

        if (requestRelay) {

            System.out.println("Request Relay Required.");

            conn = this.getCallPeer().getProtocolProvider().getConnection();
            final SmackServiceNode service = services.get(conn);

            if (service != null) {

                System.out.println("Jingle Nodes Service Found.");

                final TrackerEntry preffered = service.getPreferedRelay();

                if (preffered != null) {
                    System.out.println("Jingle Nodes Channel Provider Found: " + preffered.getJid());
                    ciq = SmackServiceNode.getChannel(conn, preffered.getJid());
                    System.out.println("Jingle Nodes Channel Received: " + ciq.getHost() + ":" + ciq.getLocalport());
                } else {
                    System.out.println("Jingle Nodes Channel Provider NOT Found.");
                }
            } else {
                System.out.println("Jingle Nodes Service NOT Found.");
            }
        }

        if (ciq != null && ciq.getRemoteport() > 0) {
            ip = ciq.getHost();
            port = ciq.getRemoteport();
            ctype = CandidateType.relay;

            MediaType contentMediaType
                    = MediaType.parseString(rtpDesc.getMedia());

            channels.put(contentMediaType, ciq);
        } else {
            System.out.println("Error getting Relay. Using Default Addresses.");
            StreamConnector connector = getStreamConnector(
                    MediaType.parseString(rtpDesc.getMedia()));

            ip = connector.getDataSocket().getLocalAddress()
                    .getHostAddress();
            port = connector.getDataSocket().getLocalPort();
        }

        // create and add candidates that correspond to the stream connector
        // RTP
        CandidatePacketExtension rtpCand = new CandidatePacketExtension();
        rtpCand.setComponent(CandidatePacketExtension.RTP_COMPONENT_ID);
        rtpCand.setGeneration(getCurrentGeneration());
        rtpCand.setID(getNextID());
        rtpCand.setIP(ip);
        rtpCand.setPort(port);
        rtpCand.setType(ctype);

        ourTransport.addCandidate(rtpCand);

        // RTCP
        CandidatePacketExtension rtcpCand = new CandidatePacketExtension();
        rtcpCand.setComponent(CandidatePacketExtension.RTCP_COMPONENT_ID);
        rtcpCand.setGeneration(getCurrentGeneration());
        rtcpCand.setID(getNextID());
        rtcpCand.setIP(ip);
        rtcpCand.setPort(port + 1);
        rtcpCand.setType(ctype);

        ourTransport.addCandidate(rtcpCand);

        return ourTransport;
    }

    /**
     * Simply returns the list of local candidates that we gathered during the
     * harvest. This is a raw UDP transport manager so there's no real wrapping
     * up to do.
     *
     * @return the list of local candidates that we gathered during the harvest
     * @see net.java.sip.communicator.impl.protocol.jabber.TransportManagerJabberImpl#wrapupCandidateHarvest()
     */
    public List<ContentPacketExtension> wrapupCandidateHarvest() {
        return local;
    }

    @Override
    protected InetAddress getIntendedDestination(CallPeerJabberImpl peer) {

        if (channels.size() > 0) {
            try {
                return InetAddress.getByName(channels.elements().nextElement().getHost());
            } catch (UnknownHostException e) {
                // Do Nothing
            }
        }

        if (remote != null) {
            for (ContentPacketExtension content : remote) {
                IceUdpTransportPacketExtension iceExtension = content.getFirstChildOfType(IceUdpTransportPacketExtension.class);

                CandidatePacketExtension candidate = null;

                if (iceExtension == null) {
                    iceExtension = content.getFirstChildOfType(RawUdpTransportPacketExtension.class);
                }

                if (iceExtension != null) {
                    candidate = iceExtension.getFirstChildOfType(CandidatePacketExtension.class);
                }

                if (candidate != null) {
                    try {
                        return InetAddress.getByName(candidate.getIP());
                    } catch (UnknownHostException e) {
                        //Do Nothing
                    }
                }
            }
        }
        return super.getIntendedDestination(peer);
    }

    /**
     * Starts the connectivity establishment of this
     * <tt>TransportManagerJabberImpl</tt> i.e. checks the connectivity between
     * the local and the remote peers given the remote counterpart of the
     * negotiation between them.
     *
     * @param remote the collection of <tt>ContentPacketExtension</tt>s which
     *               represents the remote counterpart of the negotiation between the local
     *               and the remote peer
     * @return <tt>true</tt> if connectivity establishment has been started in
     *         response to the call; otherwise, <tt>false</tt>.
     *         <tt>TransportManagerJabberImpl</tt> implementations which do not perform
     *         connectivity checks (e.g. raw UDP) should return <tt>true</tt>. The
     *         default implementation does not perform connectivity checks and always
     *         returns <tt>true</tt>.
     */
    public boolean startConnectivityEstablishment(
            Iterable<ContentPacketExtension> remote) {
        this.remote = remote;
        return true;
    }

    public synchronized static void initialize(final XMPPConnection connection) {
        if (!services.containsKey(connection)) {
            final SmackServiceNode service = new SmackServiceNode(connection, 60000);

            final SmackServiceNode.MappedNodes nodes = SmackServiceNode.searchServices(connection, 6, 3, 20, JingleChannelIQ.UDP);
            service.addEntries(nodes);

            services.put(connection, service);
        }
    }

    /**
     * Removes a content with a specific name from the transport-related part of
     * the session represented by this <tt>TransportManagerJabberImpl</tt> which
     * may have been reported through previous calls to the
     * <tt>startCandidateHarvest</tt> and
     * <tt>startConnectivityEstablishment</tt> methods.
     *
     * @param name the name of the content to be removed from the
     *             transport-related part of the session represented by this
     *             <tt>TransportManagerJabberImpl</tt>
     * @see TransportManagerJabberImpl#removeContent(String)
     */
    public void removeContent(String name) {
        if (local != null) {
            removeContent(local, name);
        }
        if (remote != null) {
            removeContent(remote, name);
        }
    }  

}
