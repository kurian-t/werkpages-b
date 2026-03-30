package org.ratemymanager.rest.handlers;

import java.util.HashMap;
import java.util.Map;

public class CompanyLogoUtils {

    private static final Map<String, String> DOMAIN_MAP = new HashMap<>();

    static {
        // Canadian banks
        put("td", "td.com"); put("td bank", "td.com"); put("toronto-dominion", "td.com"); put("toronto dominion", "td.com");
        put("rbc", "rbc.com"); put("royal bank", "rbc.com"); put("royal bank of canada", "rbc.com");
        put("bmo", "bmo.com"); put("bank of montreal", "bmo.com");
        put("scotiabank", "scotiabank.com"); put("bank of nova scotia", "scotiabank.com"); put("scotia", "scotiabank.com");
        put("cibc", "cibc.com"); put("canadian imperial bank", "cibc.com");
        put("national bank", "nbc.ca"); put("national bank of canada", "nbc.ca");
        put("atb", "atb.com"); put("atb financial", "atb.com");
        put("desjardins", "desjardins.com");
        put("hsbc", "hsbc.com");

        // Tech
        put("google", "google.com"); put("alphabet", "abc.xyz");
        put("microsoft", "microsoft.com");
        put("apple", "apple.com");
        put("amazon", "amazon.com"); put("aws", "aws.amazon.com");
        put("meta", "meta.com"); put("facebook", "meta.com");
        put("netflix", "netflix.com");
        put("ibm", "ibm.com");
        put("red hat", "redhat.com"); put("redhat", "redhat.com");
        put("blackberry", "blackberry.com");
        put("tungsten automation", "tungstenautomation.com"); put("tungsten", "tungstenautomation.com");
        put("kofax", "kofax.com");
        put("revvity", "revvity.com");
        put("shopify", "shopify.com");
        put("salesforce", "salesforce.com");
        put("oracle", "oracle.com");
        put("sap", "sap.com");
        put("cisco", "cisco.com");
        put("intel", "intel.com");
        put("dell", "dell.com");
        put("hp", "hp.com"); put("hewlett-packard", "hp.com"); put("hewlett packard", "hp.com");
        put("lenovo", "lenovo.com");
        put("tesla", "tesla.com");
        put("nvidia", "nvidia.com");
        put("amd", "amd.com");
        put("adobe", "adobe.com");
        put("atlassian", "atlassian.com");
        put("slack", "slack.com");
        put("twitter", "x.com"); put("x", "x.com");
        put("linkedin", "linkedin.com");
        put("uber", "uber.com");
        put("airbnb", "airbnb.com");
        put("spotify", "spotify.com");
        put("stripe", "stripe.com");
        put("paypal", "paypal.com");
        put("dropbox", "dropbox.com");
        put("zoom", "zoom.us");
        put("openai", "openai.com");
        put("anthropic", "anthropic.com");

        // Consulting / professional services
        put("deloitte", "deloitte.com");
        put("pwc", "pwc.com"); put("pricewaterhousecoopers", "pwc.com");
        put("ey", "ey.com"); put("ernst & young", "ey.com"); put("ernst and young", "ey.com");
        put("kpmg", "kpmg.com");
        put("accenture", "accenture.com");
        put("mckinsey", "mckinsey.com");
        put("bcg", "bcg.com"); put("boston consulting group", "bcg.com");
        put("bain", "bain.com");
        put("capgemini", "capgemini.com");
        put("cgi", "cgi.com");

        // Finance / payments
        put("visa", "visa.com");
        put("mastercard", "mastercard.com");
        put("amex", "americanexpress.com"); put("american express", "americanexpress.com");
        put("goldman sachs", "goldmansachs.com");
        put("jpmorgan", "jpmorgan.com"); put("jp morgan", "jpmorgan.com"); put("jpmorgan chase", "jpmorgan.com");
        put("morgan stanley", "morganstanley.com");
        put("bank of america", "bankofamerica.com");
        put("wells fargo", "wellsfargo.com");
        put("citibank", "citi.com"); put("citi", "citi.com");
        put("blackrock", "blackrock.com");
        put("manulife", "manulife.com");
        put("sun life", "sunlife.com"); put("sunlife", "sunlife.com");
        put("intact", "intact.ca");
        put("great-west life", "gwl.ca"); put("great west life", "gwl.ca"); put("canada life", "canadalife.com");
        put("power corporation", "powercorporation.com");
        put("brookfield", "brookfield.com");

        // Telecom
        put("bell", "bell.ca"); put("bell canada", "bell.ca");
        put("rogers", "rogers.com");
        put("telus", "telus.com");
        put("shaw", "shaw.ca");
        put("freedom mobile", "freedommobile.ca");

        // Loblaw parent only — banners (Zehrs, No Frills, Superstore, etc.) excluded as Clearbit returns wrong logos
        put("loblaws", "loblaw.ca"); put("loblaw", "loblaw.ca"); put("loblaw companies", "loblaw.ca");
        put("zehrs", "zehrs.ca");
        put("shoppers drug mart", "shoppersdrugmart.ca"); put("shoppers", "shoppersdrugmart.ca");
        put("pharmaprix", "pharmaprix.ca");

        // Sobeys banners
        put("sobeys", "sobeys.com");
        put("safeway", "safeway.com");
        put("iga", "iga.net");
        put("foodland", "sobeys.com");
        put("freshco", "sobeys.com");
        put("price chopper", "sobeys.com");
        put("thrifty foods", "sobeys.com");
        put("empire company", "empireco.ca");

        // Other Canadian retail / food
        put("metro", "metro.ca"); put("food basics", "metro.ca"); put("super c", "metro.ca");
        put("canadian tire", "canadiantire.ca");
        put("sport chek", "sportchek.ca");
        put("marks", "marks.com"); put("mark's", "marks.com");
        put("dollarama", "dollarama.com");
        put("giant tiger", "gianttiger.com");
        put("winners", "winners.ca");
        put("homesense", "homesense.ca");
        put("tim hortons", "timhortons.com"); put("tims", "timhortons.com");
        put("mcdonald's", "mcdonalds.com"); put("mcdonalds", "mcdonalds.com");
        put("starbucks", "starbucks.com");
        put("subway", "subway.com");
        put("pizza pizza", "pizzapizza.ca");
        put("boston pizza", "bostonpizza.com");
        put("a&w", "aw.ca");
        put("harvey's", "harveys.ca");
        put("swiss chalet", "swisschalet.com");
        put("rona", "rona.ca");
        put("home depot", "homedepot.ca");
        put("lowes", "lowes.ca"); put("lowe's", "lowes.ca");
        put("best buy", "bestbuy.ca");
        put("staples", "staples.ca");
        put("indigo", "indigo.ca"); put("chapters", "indigo.ca"); put("chapters indigo", "indigo.ca");
        put("aritzia", "aritzia.com");
        put("lululemon", "lululemon.com");
        put("roots", "roots.com");

        // Canadian industry
        put("bombardier", "bombardier.com");
        put("cpr", "cpr.ca"); put("canadian pacific", "cpr.ca"); put("cp rail", "cpr.ca");
        put("cn", "cn.ca"); put("canadian national", "cn.ca"); put("cn rail", "cn.ca");
        put("air canada", "aircanada.com");
        put("westjet", "westjet.com");
        put("porter airlines", "flyporter.com"); put("porter", "flyporter.com");
        put("hydro one", "hydroone.com");
        put("ontario power generation", "opg.com"); put("opg", "opg.com");
        put("enbridge", "enbridge.com");
        put("tc energy", "tcenergy.com"); put("transcanada", "tcenergy.com");
        put("suncor", "suncor.com");
        put("cenovus", "cenovus.com");
        put("nutrien", "nutrien.com");
        put("magna", "magna.com"); put("magna international", "magna.com");
        put("linamar", "linamar.com");
        put("arctic wolf", "arcticwolf.com");
        put("intero integrity", "intero-integrity.com");
    }

    private static void put(String key, String domain) {
        DOMAIN_MAP.put(key, domain);
    }

    /**
     * Returns a Clearbit logo URL for known companies, or null for unknown ones.
     * Null means the frontend should fall back to a Google favicon or show nothing.
     */
    private static final String LOGO_DEV_TOKEN = "pk_MXSjJV-uTC6-L5D_FbXZUA";

    public static String resolveLogoUrl(String company) {
        if (company == null || company.isBlank()) return null;
        String key = company.toLowerCase().trim();
        String domain = DOMAIN_MAP.get(key);
        if (domain == null) {
            // Best-effort: derive a domain from the company name
            domain = key.replaceAll("[^a-z0-9]", "") + ".com";
        }
        return "https://img.logo.dev/" + domain + "?token=" + LOGO_DEV_TOKEN;
    }
}
