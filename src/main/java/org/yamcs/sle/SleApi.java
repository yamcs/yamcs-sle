package org.yamcs.sle;

import org.yamcs.YamcsServer;
import org.yamcs.YamcsServerInstance;
import org.yamcs.api.Observer;
import org.yamcs.http.BadRequestException;
import org.yamcs.http.Context;
import org.yamcs.http.NotFoundException;
import org.yamcs.management.LinkManager;
import org.yamcs.sle.api.AbstractSleApi;
import org.yamcs.sle.api.RequestOfflineDataRequest;
import org.yamcs.tctm.Link;

import com.google.protobuf.Empty;

public class SleApi extends AbstractSleApi<Context> {

    @Override
    public void requestOfflineData(Context ctx, RequestOfflineDataRequest request, Observer<Empty> observer) {
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

        LinkManager lmgr = verifyInstanceObj(request.getInstance()).getLinkManager();
        Link link = lmgr.getLink(request.getLinkName());

        if (link == null) {
            throw new NotFoundException("No such link");
        }

        if (!(link instanceof OfflineTmSleLink)) {
            throw new BadRequestException("This is not a OfflineTmSleLink");
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
}
