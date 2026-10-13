package com.java.myapp;

/** Preset portal endpoints selected centrally before each tool run. */
public final class PortalUrls {
    private PortalUrls() { }
    public static String login() {return preset("login", "https://proms.truecorp.co.th/proms/login/", "http://promsweb.true.th/proms/login");}
    public static String pending() {return preset("pending", "https://proms.truecorp.co.th/proms/pendingapprove", "http://promsweb.true.th/proms/pendingapprove");}
    public static String workOrder() {return preset("workorder", "https://proms.truecorp.co.th/proms/workorder", "http://promsweb.true.th/proms/workorder");}
    private static String preset(String key,String external,String internal) {
        String selected=System.getProperty("trueops.portal."+key,external);
        if (!selected.equals(external) && !selected.equals(internal)) throw new IllegalStateException("Select External or Internal in Portal settings before running.");
        return selected;
    }
}
