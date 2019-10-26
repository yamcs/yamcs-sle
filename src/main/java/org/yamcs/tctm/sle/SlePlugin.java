package org.yamcs.tctm.sle;

import org.yamcs.Plugin;
import org.yamcs.Spec;
import org.yamcs.Spec.OptionType;

public class SlePlugin implements Plugin {
    private String version;
    
    public SlePlugin() {
        Package pkg = getClass().getPackage();
        version = (pkg != null) ? pkg.getImplementationVersion() : null;
        
        Spec spec = new Spec();
        spec.addOption("tag", OptionType.STRING);
        spec.addOption("displayPath", OptionType.STRING);
        
    }
    @Override
    public String getName() {
        return "yamcs-sle";
    }

    @Override
    public String getDescription() {
        return "Data links for connecting Yamcs to SLE (Space Link Extension) providers such as Ground Stations";
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public String getVendor() {
        return "Space Applications Services";
    }

}
