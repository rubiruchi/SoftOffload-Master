package net.floodlightcontroller.offloading;

import java.util.ArrayList;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;

import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPacketOut;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFStatisticsRequest;
import org.openflow.protocol.OFType;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.statistics.OFFlowStatisticsReply;
import org.openflow.protocol.statistics.OFFlowStatisticsRequest;
import org.openflow.protocol.statistics.OFStatistics;
import org.openflow.protocol.statistics.OFStatisticsType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SwitchFlowStatistics implements Runnable {

    protected static Logger log = LoggerFactory.getLogger(OffloadingProtocolServer.class);

    private IFloodlightProviderService floodlightProvider;
    // private final ExecutorService executor;

    // private List<OFFlowStatisticsReply> statsReply;
    private Timer timer;
    private long interval;

    // default max rate threshold
    static private final float RATE_THRESHOLD = 10000;

    private class PrintTask extends TimerTask {
        public void run() {
            printStatistics();
        }
    }

    public SwitchFlowStatistics(IFloodlightProviderService fProvider,
            ExecutorService executor, int printInterval) {
        this.floodlightProvider = fProvider;
        // this.executor = executor;
        this.timer = new Timer();
        this.interval = (long)printInterval;
    }



    @Override
    public void run() {
        timer.schedule(new PrintTask(), (long)2000, this.interval*1000);
    }



    private void printStatistics() {
        // statsReply = new ArrayList<OFFlowStatisticsReply>();
        List<OFStatistics> values = null;
        Future<List<OFStatistics>> future;
        OFFlowStatisticsReply reply;
        OFMatch match;
        float rate;


        // get switch
        Map<Long,IOFSwitch> swMap = floodlightProvider.getAllSwitchMap();

        for (IOFSwitch sw: swMap.values()) {
            try {
                OFStatisticsRequest req = new OFStatisticsRequest();
                req.setStatisticType(OFStatisticsType.FLOW);
                int requestLength = req.getLengthU();
                OFFlowStatisticsRequest specificReq = new OFFlowStatisticsRequest();
                specificReq.setMatch(new OFMatch().setDataLayerType((short)0x0800));
                specificReq.setTableId((byte)0xff);

                // using OFPort.OFPP_NONE(0xffff) as the outport
                specificReq.setOutPort(OFPort.OFPP_NONE.getValue());
                req.setStatistics(Collections.singletonList((OFStatistics) specificReq));
                requestLength += specificReq.getLength();
                req.setLengthU(requestLength);

                // make the query
                future = sw.queryStatistics(req);
                values = future.get(3, TimeUnit.SECONDS);
                if (values != null) {
                    for (OFStatistics stat: values) {
                        // statsReply.add((OFFlowStatisticsReply) stat);
                        reply = (OFFlowStatisticsReply) stat;
                        rate = (float) reply.getByteCount()
                                  / ((float) reply.getDurationSeconds()
                                  + ((float) reply.getDurationNanoseconds() / 1000000000));
                        match = reply.getMatch();
                        // actions list is empty means the current flow action is to drop
                        if (rate >= RATE_THRESHOLD && !reply.getActions().isEmpty()) {
                            // log.info(reply.toString());

                            System.out.println(match.getNetworkDestination());

                            byteArrayToStringMac(match.getDataLayerSource());
                            log.info("Flow {} -> {}",
                                byteArrayToStringMac(match.getDataLayerSource()),
                                byteArrayToStringMac(match.getDataLayerDestination()));

                            log.info("FlowRate = {}bytes/s: suspicious flow, " +
                                    "drop matched pkts", Float.toString(rate));

                            // modify flow action to drop
                            setOFFlowActionToDrop(match, sw);
                        }
                    }
                }


            } catch (Exception e) {
                log.error("Failure retrieving statistics from switch " + sw, e);
            }
        }
    }

    private void setOFFlowActionToDrop(OFMatch match, IOFSwitch sw) throws UnknownHostException {

        OFFlowMod flowMod = (OFFlowMod) floodlightProvider.getOFMessageFactory().getMessage(OFType.FLOW_MOD);
        // set no action to drop
        List<OFAction> actions = new ArrayList<OFAction>();

        pack(InetAddress.getByName("192.168.0.100").getAddress());

        // set flow_mod
        flowMod.setOutPort(OFPort.OFPP_NONE);
        flowMod.setMatch(match);
        // this buffer_id is needed for avoiding a BAD_REQUEST error
        flowMod.setBufferId(OFPacketOut.BUFFER_ID_NONE);
        flowMod.setHardTimeout((short) 0);
        flowMod.setIdleTimeout((short) 20);
        flowMod.setActions(actions);
        flowMod.setCommand(OFFlowMod.OFPFC_MODIFY);

        // send flow_mod
        if (sw == null) {
            log.debug("Switch is not connected!");
            return;
        }
        try {
            sw.write(flowMod, null);
            sw.flush();
        } catch (IOException e) {
            log.error("tried to write flow_mod to {} but failed: {}",
                        sw.getId(), e.getMessage());
        } catch (Exception e) {
            log.error("Failure to modify flow entries", e);
        }
    }

    private String byteArrayToStringMac(byte[] mac) {
        StringBuilder sb = new StringBuilder(18);

        for (byte b: mac) {
            if (sb.length() > 0)
                sb.append(':');
            sb.append(String.format("%02x", b));
        }

        return sb.toString();
    }

    private int pack(byte[] bytes) {
        int val = 0;
        for (int i = 0; i < bytes.length; i++) {
          val <<= 8;
          val |= bytes[i] & 0xff;
        }
        return val;
    }

}