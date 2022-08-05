package org.yamcs.sle;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.yamcs.YamcsServer;
import org.yamcs.YamcsServerInstance;
import org.yamcs.api.Observer;
import org.yamcs.http.BadRequestException;
import org.yamcs.http.Context;
import org.yamcs.http.NotFoundException;
import org.yamcs.jsle.CcsdsTime;
import org.yamcs.jsle.ParameterName;
import org.yamcs.jsle.SleEnum;
import org.yamcs.jsle.SleException;
import org.yamcs.management.LinkManager;
import org.yamcs.security.SystemPrivilege;
import org.yamcs.sle.api.AbstractSleApi;
import org.yamcs.sle.api.CltuThrowEventRequest;
import org.yamcs.sle.api.CltuThrowEventResponse;
import org.yamcs.sle.api.GetParameterRequest;
import org.yamcs.sle.api.GetParameterResponse;
import org.yamcs.sle.api.RequestOfflineDataRequest;
import org.yamcs.tctm.Link;

import com.google.protobuf.Empty;

public class SleApi extends AbstractSleApi<Context> {

    @Override
    public void requestOfflineData(Context ctx, RequestOfflineDataRequest request, Observer<Empty> observer) {
        Link link = verifyLink(ctx, request.getInstance(), request.getLinkName());

        if (!request.hasStart()) {
            throw new BadRequestException("Missing start");
        }
        if (!request.hasStop()) {
            throw new BadRequestException("Missing stop");
        }

        CcsdsTime start = CcsdsTime.fromUnix(request.getStart().getSeconds(), request.getStart().getNanos());
        CcsdsTime stop = CcsdsTime.fromUnix(request.getStop().getSeconds(), request.getStop().getNanos());

        if (start.compareTo(stop) >= 0) {
            throw new BadRequestException("start has to be strictly smaller than stop");
        }

        if (!(link instanceof OfflineTmSleLink)) {
            throw new BadRequestException("This is not an OfflineTmSleLink");
        }

        if (link.isDisabled()) {
            throw new BadRequestException("Link unavailable");
        }
        OfflineTmSleLink otfl = (OfflineTmSleLink) link;
        otfl.addRequest(start, stop);
        observer.complete(Empty.getDefaultInstance());
    }

    public static YamcsServerInstance verifyInstanceObj(String instance) {

        YamcsServerInstance ysi = YamcsServer.getServer().getInstance(instance);
        if (ysi == null) {
            throw new NotFoundException("No instance named '" + instance + "'");
        }
        return ysi;
    }

    @Override
    public void cltuThrowEvent(Context ctx, CltuThrowEventRequest request, Observer<CltuThrowEventResponse> observer) {
        Link link = verifyLink(ctx, request.getInstance(), request.getLinkName());

        if (!(link instanceof TcSleLink)) {
            throw new BadRequestException("This is not a TcSleLink");
        }
        TcSleLink tcSleLink = (TcSleLink) link;
        if (!request.hasEventIdentifier()) {
            throw new BadRequestException("eventIdentifier is mandatory");
        }
        int eventIdentifier = request.getEventIdentifier();
        byte[] eventQualifier;


        if (request.hasEventQualifier()) {
            if (request.hasEventQualifierBinary()) {
                throw new BadRequestException("Cannot set both eventQualifier and eventQualifierBinary");
            }
            eventQualifier = request.getEventQualifier().getBytes(StandardCharsets.UTF_8);
        } else if (request.hasEventQualifierBinary()) {
            eventQualifier = request.getEventQualifierBinary().toByteArray();
        } else {
            throw new BadRequestException("One of the eventQualifier or eventQualifierBinary has to be set");
        }

        try {
            tcSleLink.throwEvent(eventIdentifier, eventQualifier).whenComplete((v, t) -> {
                CltuThrowEventResponse.Builder rb = CltuThrowEventResponse.newBuilder()
                        .setEventIdentifier(eventIdentifier);
                if (t != null) {
                    observer.complete(rb.setError(t.toString()).build());
                } else {
                    observer.complete(rb.build());
                }
            });
        } catch (SleException e) {
            observer.complete(CltuThrowEventResponse.newBuilder().setError(e.getMessage()).build());
        }
    }

    @Override
    public void getParameter(Context ctx, GetParameterRequest request, Observer<GetParameterResponse> observer) {
        Link link = verifyLink(ctx, request.getInstance(), request.getLinkName());

        if (!(link instanceof SleLink)) {
            throw new BadRequestException("This is not a SLE Link");
        }

        SleLink sleLink = (SleLink) link;

        if (!request.hasParameterName()) {
            throw new BadRequestException("parameterName is mandatory");
        }
        String pn = request.getParameterName();
        ParameterName paraName;
        try {
            paraName = ParameterName.valueOf(pn);
        } catch (IllegalArgumentException e) {
            String validNames = Arrays.stream(ParameterName.values()).map(p -> p.name())
                    .collect(Collectors.joining(", "));
            throw new BadRequestException("Invalid parameter " + pn + ". Valid parameters are: " + validNames);
        }
        try {
            sleLink.getParameter(paraName).whenComplete((sleParameter, t) -> {
                GetParameterResponse.Builder gprb = GetParameterResponse.newBuilder().setParameterName(pn);
                if (t != null) {
                    observer.complete(gprb.setError(t.toString()).build());
                } else {
                    System.out.println("sleparameter: " + sleParameter.getParameterValue() + " of class: "
                            + sleParameter.getParameterValue().getClass());
                    fillParameterValue(gprb, sleParameter.getParameterValue());
                    observer.complete(gprb.build());
                }
            });
        } catch (SleException e) {
            observer.complete(GetParameterResponse.newBuilder().setParameterName(pn).setError(e.getMessage()).build());
        }
    }

    private Link verifyLink(Context ctx, String instance, String linkName) {
        ctx.checkSystemPrivilege(SystemPrivilege.ControlLinks);

        LinkManager lmgr = verifyInstanceObj(instance).getLinkManager();
        Link link = lmgr.getLink(linkName);

        if (link == null) {
            throw new NotFoundException("No such link");
        }

        if (link.isDisabled()) {
            throw new BadRequestException("Link unavailable");
        }
        return link;
    }

    private void fillParameterValue(GetParameterResponse.Builder gprb, Object pv) {
        if (pv == null) {
            return;
        }
        if (pv instanceof SleEnum) {
            SleEnum sepv = (SleEnum) pv;
            gprb.setStringValue(sepv.name());
            gprb.setIntValue(sepv.id());
        } else if (pv instanceof Integer) {
            gprb.setIntValue((Integer) pv);
        } else if (pv instanceof Long) {
            long l = (Long) pv;
            if (l > Integer.MAX_VALUE || l < Integer.MIN_VALUE) {
                gprb.setLongValue(l);
            } else {
                gprb.setIntValue((int) l);
            }
        } else {
            gprb.setStringValue(pv.toString());
        }
    }
}
