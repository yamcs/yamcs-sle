package org.yamcs.sle;


import org.yamcs.YamcsServer;
import org.yamcs.YamcsServerInstance;
import org.yamcs.api.Observer;
import org.yamcs.http.BadRequestException;
import org.yamcs.http.Context;
import org.yamcs.http.NotFoundException;
import org.yamcs.management.LinkManager;
import org.yamcs.sle.api.AbstractSleApi;
import org.yamcs.sle.api.OfflineRange;
import org.yamcs.sle.api.RequestOfflineDataRequest;
import org.yamcs.tctm.Link;

import com.google.protobuf.Empty;

public class SleApi extends AbstractSleApi<Context> {

    @Override
    public void requestOfflineData(Context ctx, RequestOfflineDataRequest request, Observer<Empty> observer) {
        for (OfflineRange range : request.getRangesList()) {
            if (!range.hasStart()) {
                throw new BadRequestException("Missing start");
            }
            if (!range.hasStop()) {
                throw new BadRequestException("Missing stop");
            }
        }

        LinkManager lmgr = verifyInstanceObj(request.getInstance()).getLinkManager();
        Link link = lmgr.getLink(request.getLinkName());

        if (link == null) {
            throw new NotFoundException("No such link");
        }

        if (!(link instanceof OfflineTmFrameLink)) {
            throw new BadRequestException("This is not a SLE Offline TM Link");
        }
        if (link.isDisabled()) {
            throw new BadRequestException("Link unavailable");
        }
        OfflineTmFrameLink otfl = (OfflineTmFrameLink) link;
        otfl.addRequests(request.getRangesList());
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
