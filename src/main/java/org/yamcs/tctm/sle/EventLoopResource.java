package org.yamcs.tctm.sle;

import io.netty.channel.nio.NioEventLoopGroup;

/**
 * singleton for netty worker group. In the future we may have an option to create different worker groups for different SLE connections but for now we stick to one. 
 * @author nm
 *
 */
public class EventLoopResource {
    static NioEventLoopGroup nelg = new NioEventLoopGroup();
    
    static NioEventLoopGroup getEventLoop() {
        return nelg;
    }
}
